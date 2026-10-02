package com.cloud.dex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 二进制 AndroidManifest.xml 权限检测与补丁工具（规范化 AXML 解析/重写实现）。
 *
 * 支持的 AXML 结构（Android 5 ~ 16）：
 *  - 头部：0x0003(header) + 0x0001(STRING_POOL) + 0x0180(RESOURCE_MAP) + 0x0100(START_NS) ...
 *  - STRING_POOL 支持 UTF-8 与 UTF-16 两种编码
 *  - Res_value：size(2) + res0(1) + dataType(1) + data(4)
 *  - 可插入新的 <uses-permission android:name="..."/> 节点
 *
 * 所有方法失败均安全返回（不抛异常），保证不影响注入主流程。
 */
public final class ManifestPermissionPatcher {

    private ManifestPermissionPatcher() {}

    /** xiao.dex 运行所需权限 */
    private static final String[] REQUIRED_PERMISSIONS = {
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
    };

    // AXML chunk types
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_XML_START_NAMESPACE_TYPE = 0x0100;
    private static final int RES_XML_END_NAMESPACE_TYPE = 0x0101;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int RES_XML_END_ELEMENT_TYPE = 0x0103;
    private static final int RES_XML_CDATA_TYPE = 0x0104;

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    // ═══════════════════════════════════════════════════════════
    //  公共 API
    // ═══════════════════════════════════════════════════════════

    /** @return 目标 APK 中缺失的权限列表；为空表示全部已声明。 */
    public static List<String> getMissingPermissions(String apkPath) {
        List<String> missing = new ArrayList<>();
        try {
            byte[] manifest = readManifestBytes(apkPath);
            if (manifest == null) return missing;
            Set<String> existing = parsePermissions(manifest);
            for (String p : REQUIRED_PERMISSIONS) {
                if (!existing.contains(p)) missing.add(p);
            }
        } catch (Throwable ignored) {
        }
        return missing;
    }

    /** 从 APK 中读取 AndroidManifest.xml 原始字节。 */
    public static byte[] readManifestBytes(String apkPath) {
        try (ZipFile zf = new ZipFile(apkPath)) {
            ZipEntry entry = zf.getEntry("AndroidManifest.xml");
            if (entry == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
            try (InputStream is = zf.getInputStream(entry)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** 解析已声明的所有 uses-permission 权限名。 */
    public static Set<String> parsePermissions(byte[] data) {
        Set<String> out = new HashSet<>();
        try {
            Axml axml = Axml.parse(data);
            for (Axml.Element el : axml.elements) {
                if ("uses-permission".equals(el.name) || "uses-permission-sdk-23".equals(el.name)) {
                    String v = el.attrString(axml.strings, "name");
                    if (v != null) out.add(v);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 向二进制 AndroidManifest.xml 中插入新的 uses-permission 声明。
     *
     * @param originalManifest 原始 AndroidManifest.xml 字节
     * @param permissionsToAdd 要添加的权限名列表
     * @return 修改后的字节数组；失败返回 null
     */
    public static byte[] injectPermissions(byte[] originalManifest, List<String> permissionsToAdd) {
        if (originalManifest == null || permissionsToAdd == null || permissionsToAdd.isEmpty()) {
            return originalManifest;
        }
        try {
            Axml axml = Axml.parse(originalManifest);

            // 已有权限（避免重复插入）
            Set<String> existing = new HashSet<>();
            for (Axml.Element el : axml.elements) {
                if ("uses-permission".equals(el.name)) {
                    String v = el.attrString(axml.strings, "name");
                    if (v != null) existing.add(v);
                }
            }

            List<String> toAdd = new ArrayList<>();
            for (String p : permissionsToAdd) {
                if (p != null && !p.isEmpty() && !existing.contains(p)) toAdd.add(p);
            }
            if (toAdd.isEmpty()) return originalManifest;

            // 准备字符串池索引
            int nsAndroidIdx = axml.strings.ensure(ANDROID_NS);
            int nameIdx = axml.strings.ensure("name");
            int tagIdx = axml.strings.ensure("uses-permission");

            // 在第一个非 uses-permission 的顶层元素（通常是 <application> 或 <permission>）前插入
            int insertBefore = axml.firstBinaryChildIndexForInsert();

            // 生成新节点
            List<byte[]> newNodes = new ArrayList<>();
            for (String perm : toAdd) {
                int permIdx = axml.strings.ensure(perm);
                newNodes.add(Axml.buildUsesPermission(nsAndroidIdx, nameIdx, tagIdx, permIdx));
            }

            // 重建字符串池并按偏移映射重写所有引用
            byte[] newPool = axml.strings.serialize();
            int poolOldSize = axml.poolChunkSize;
            int poolDelta = newPool.length - poolOldSize;

            ByteArrayOutputStream out = new ByteArrayOutputStream(originalManifest.length + poolDelta + 4096);

            // 1) XML 根头
            write16(out, RES_XML_TYPE);
            write16(out, 8);
            write32(out, 0); // 占位，最后回填

            // 2) 字符串池
            out.write(newPool, 0, newPool.length);

            // 3) 其余 chunk：字符串引用索引 <池扩容起点 的不变，>= 的需 +delta
            int shiftThreshold = axml.strings.originalCount;
            for (Axml.Node node : axml.nodes) {
                if (node instanceof Axml.PoolNode) continue;
                if (node == axml.firstInsertBeforeNode) {
                    for (byte[] nb : newNodes) out.write(nb, 0, nb.length);
                }
                byte[] bytes = node.bytes(shiftThreshold, poolDelta);
                out.write(bytes, 0, bytes.length);
            }
            // 若插入点未命中（末尾），直接追加
            if (axml.firstInsertBeforeNode == null) {
                for (byte[] nb : newNodes) out.write(nb, 0, nb.length);
            }

            byte[] result = out.toByteArray();
            // 回填根 chunk 总长度
            int total = result.length;
            result[4] = (byte) (total & 0xFF);
            result[5] = (byte) ((total >> 8) & 0xFF);
            result[6] = (byte) ((total >> 16) & 0xFF);
            result[7] = (byte) ((total >> 24) & 0xFF);

            return result;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 为 Android 10 追加 requestLegacyExternalStorage="true"（<application> 上）。失败返回原数组。 */
    public static byte[] addLegacyStorageFlag(byte[] originalManifest) {
        // 该标志在注入器主流程中未强制使用；保持 API 兼容。
        return originalManifest;
    }

    // ═══════════════════════════════════════════════════════════
    //  AXML 模型 / 解析器
    // ═══════════════════════════════════════════════════════════

    /** AXML 解析结果：字符串池 + 顺序节点列表 + 元素视图 */
    static final class Axml {
        StringPool strings;
        List<Node> nodes = new ArrayList<>();
        List<Element> elements = new ArrayList<>();
        int poolChunkSize;
        Node firstInsertBeforeNode;

        static Axml parse(byte[] data) {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            Axml a = new Axml();

            int rootType = bb.getShort() & 0xFFFF;
            if (rootType != RES_XML_TYPE) throw new IllegalArgumentException("not AXML");
            int rootHeader = bb.getShort() & 0xFFFF;
            int rootSize = bb.getInt();
            int end = Math.min(rootSize > 0 ? rootSize : data.length, data.length);

            // 元素栈：用 name 维护 uses-permission 上下文
            List<Element> stack = new ArrayList<>();

            int pos = rootHeader;
            while (pos + 8 <= end) {
                bb.position(pos);
                int type = bb.getShort() & 0xFFFF;
                int headerSize = bb.getShort() & 0xFFFF;
                int chunkSize = bb.getInt();
                if (chunkSize < 8 || pos + chunkSize > data.length) break;

                switch (type) {
                    case RES_STRING_POOL_TYPE: {
                        byte[] chunk = Arrays.copyOfRange(data, pos, pos + chunkSize);
                        a.strings = StringPool.parse(chunk);
                        a.poolChunkSize = chunkSize;
                        a.nodes.add(new PoolNode(chunk));
                        break;
                    }
                    case RES_XML_START_ELEMENT_TYPE: {
                        byte[] chunk = Arrays.copyOfRange(data, pos, pos + chunkSize);
                        Element el = Element.parse(chunk, a.strings, pos);
                        a.nodes.add(el);
                        el.parentDepth = stack.size();
                        if (!stack.isEmpty()) el.parentName = stack.get(stack.size() - 1).name;
                        stack.add(el);
                        a.elements.add(el);
                        break;
                    }
                    case RES_XML_END_ELEMENT_TYPE: {
                        byte[] chunk = Arrays.copyOfRange(data, pos, pos + chunkSize);
                        a.nodes.add(new PlainNode(chunk, RES_XML_END_ELEMENT_TYPE));
                        if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                        break;
                    }
                    default: {
                        byte[] chunk = Arrays.copyOfRange(data, pos, pos + chunkSize);
                        a.nodes.add(new PlainNode(chunk, type));
                        break;
                    }
                }
                pos += chunkSize;
            }

            // 确定插入点：第一个「顶层、非 uses-permission」的 START_ELEMENT
            for (Node n : a.nodes) {
                if (n instanceof Element) {
                    Element el = (Element) n;
                    if (el.parentDepth == 0 && !"uses-permission".equals(el.name)
                            && !"uses-permission-sdk-23".equals(el.name)) {
                        a.firstInsertBeforeNode = n;
                        break;
                    }
                }
            }
            return a;
        }

        int firstBinaryChildIndexForInsert() { return 0; }
    }

    /** 顺序节点基类；bytes() 允许按池扩容位移重写内部字符串引用。 */
    abstract static class Node {
        abstract byte[] bytes(int shiftThreshold, int delta);
    }

    static final class PoolNode extends Node {
        final byte[] chunk;
        PoolNode(byte[] c) { this.chunk = c; }
        byte[] bytes(int shiftThreshold, int delta) { return chunk; }
    }

    static final class PlainNode extends Node {
        final byte[] chunk;
        final int type;
        PlainNode(byte[] c, int t) { this.chunk = c; this.type = t; }
        byte[] bytes(int shiftThreshold, int delta) { return chunk; }
    }

    /** START_ELEMENT 节点 */
    static final class Element extends Node {
        byte[] chunk;
        String name;
        int parentDepth;
        String parentName;
        int[] attrNameIdx;
        int[] attrNsIdx;
        int[] attrStrValueIdx; // 仅 TYPE_STRING 时有意义，其它为 -1
        int[] attrType;        // Res_value.dataType
        int[] attrData;        // Res_value.data
        int nameIdx;
        int nsIdx;

        static Element parse(byte[] chunk, StringPool pool, int fileOffset) {
            ByteBuffer bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
            bb.getShort(); // type
            bb.getShort(); // headerSize
            bb.getInt();   // chunkSize
            bb.getInt();   // lineNumber
            bb.getInt();   // comment
            int nsIdx = bb.getInt();
            int nameIdx = bb.getInt();
            int attrStart = bb.getShort() & 0xFFFF;  // 相对 attrExt 起点(=chunk+16)的偏移
            int attrSize = bb.getShort() & 0xFFFF;
            int attrCount = bb.getShort() & 0xFFFF;
            bb.getShort(); // idIndex
            bb.getShort(); // classIndex
            bb.getShort(); // styleIndex

            Element el = new Element();
            el.chunk = chunk;
            el.nsIdx = nsIdx;
            el.nameIdx = nameIdx;
            if (pool != null) el.name = pool.get(nameIdx);

            el.attrNameIdx = new int[attrCount];
            el.attrNsIdx = new int[attrCount];
            el.attrStrValueIdx = new int[attrCount];
            el.attrType = new int[attrCount];
            el.attrData = new int[attrCount];
            if (attrSize <= 0) attrSize = 20;
            // AOSP 规范：属性区 = chunk 起点 + 16(ResXMLTree_node) + attributeStart
            int attrBase = 16 + attrStart;
            for (int i = 0; i < attrCount; i++) {
                int base = attrBase + i * attrSize;
                if (base + attrSize > chunk.length) break;
                bb.position(base);
                int ns = bb.getInt();
                int nm = bb.getInt();
                bb.getInt(); // rawValue
                bb.getShort(); // Res_value.size
                bb.get();      // res0
                int dataType = bb.get() & 0xFF;
                int dataVal = bb.getInt();

                el.attrNsIdx[i] = ns;
                el.attrNameIdx[i] = nm;
                el.attrStrValueIdx[i] = (dataType == 0x03) ? dataVal : -1;
                el.attrType[i] = dataType;
                el.attrData[i] = dataVal;
            }
            return el;
        }

        String attrString(StringPool pool, String attrName) {
            if (pool == null) return null;
            for (int i = 0; i < attrNameIdx.length; i++) {
                String n = pool.get(attrNameIdx[i]);
                if (attrName.equals(n)) {
                    if (attrStrValueIdx[i] >= 0) return pool.get(attrStrValueIdx[i]);
                }
            }
            return null;
        }

        @Override
        byte[] bytes(int shiftThreshold, int delta) {
            if (delta == 0) return chunk;
            byte[] out = Arrays.copyOf(chunk, chunk.length);
            ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
            // nsIdx / nameIdx
            remap(bb, out, 8, shiftThreshold, delta);
            remap(bb, out, 12, shiftThreshold, delta);
            int attrStart = (bb.getShort(14) & 0xFFFF);
            int attrSize = bb.getShort(16) & 0xFFFF;
            int attrCount = bb.getShort(18) & 0xFFFF;
            if (attrSize <= 0) attrSize = 20;
            if (attrStart <= 0) attrStart = 20;
            for (int i = 0; i < attrCount; i++) {
                int base = attrStart + i * attrSize;
                if (base + attrSize > out.length) break;
                remap(bb, out, base, shiftThreshold, delta);          // ns
                remap(bb, out, base + 4, shiftThreshold, delta);      // name
                // rawValue 是字符串索引，也需重映射
                remap(bb, out, base + 8, shiftThreshold, delta);
                // typedValue.data（当 dataType==0x03 时是字符串索引）
                int dataType = out[base + 8 + 4 + 1 + 1] & 0xFF;
                if (dataType == 0x03) {
                    remap(bb, out, base + 8 + 4 + 4, shiftThreshold, delta);
                }
            }
            return out;
        }

        private static void remap(ByteBuffer bb, byte[] buf, int off, int threshold, int delta) {
            if (off + 4 > buf.length) return;
            int v = bb.getInt(off);
            if (v >= threshold) {
                int nv = v + delta;
                bb.putInt(off, nv);
            }
        }
    }

    /** 构建一个 <uses-permission android:name="perm"/> 节点字节 */
    static byte[] buildUsesPermission(int nsIdx, int nameIdx, int tagIdx, int permIdx) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(80);
        // START_TAG
        write16(o, RES_XML_START_ELEMENT_TYPE);
        write16(o, 16);
        write32(o, 56);      // chunkSize = 16 + 20(attr结构) + 20(attr结构)  => 见下
        write32(o, 0);       // lineNumber
        write32(o, -1);      // comment
        write32(o, -1);      // ns
        write32(o, tagIdx);  // name
        write16(o, 20);      // attributeStart (相对本 chunk 起点)
        write16(o, 20);      // attributeSize
        write16(o, 1);       // attributeCount
        write16(o, 0);       // idIndex
        write16(o, 0);       // classIndex
        write16(o, 0);       // styleIndex
        // --- attribute: android:name="perm" ---
        write32(o, nsIdx);
        write32(o, nameIdx);
        write32(o, -1);      // rawValue
        // Res_value: size(2)+res0(1)+dataType(1)+data(4)
        write16(o, 8);
        write8(o, 0);
        write8(o, 0x03);     // TYPE_STRING
        write32(o, permIdx);

        byte[] start = o.toByteArray();
        // 修正 chunkSize：总长 = 当前 start 长度 + END_TAG(24) = 56
        int total = start.length + 24;
        start[4] = (byte) (total & 0xFF);
        start[5] = (byte) ((total >> 8) & 0xFF);
        start[6] = (byte) ((total >> 16) & 0xFF);
        start[7] = (byte) ((total >> 24) & 0xFF);

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(start, 0, start.length);
        // END_TAG
        write16(all, RES_XML_END_ELEMENT_TYPE);
        write16(all, 16);
        write32(all, 24);
        write32(all, 0);     // lineNumber
        write32(all, -1);    // comment
        write32(all, -1);    // ns
        write32(all, tagIdx);// name
        return all.toByteArray();
    }

    // ═══════════════════════════════════════════════════════════
    //  字符池
    // ═══════════════════════════════════════════════════════════

    static final class StringPool {
        boolean isUtf8;
        List<String> list = new ArrayList<>();
        int originalCount;
        List<Integer> originalOffsets = new ArrayList<>();

        static StringPool parse(byte[] chunk) {
            ByteBuffer bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
            bb.getShort(); // type
            bb.getShort(); // headerSize
            bb.getInt();   // chunkSize
            int count = bb.getInt();
            int styleCount = bb.getInt();
            int flags = bb.getInt();
            int stringsStart = bb.getInt();
            bb.getInt();   // stylesStart

            StringPool sp = new StringPool();
            sp.originalCount = count;
            sp.isUtf8 = (flags & 0x00000100) != 0;

            List<Integer> offs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) offs.add(bb.getInt());
            sp.originalOffsets = offs;

            int dataBase = stringsStart;
            for (int i = 0; i < count; i++) {
                int off = offs.get(i);
                int p = dataBase + off;
                String s;
                if (sp.isUtf8) {
                    int[] r = readLen8(p, chunk);
                    int utf16Len = r[0];
                    p = r[1];
                    r = readLen8(p, chunk);
                    int byteLen = r[0];
                    p = r[1];
                    s = new String(chunk, p, byteLen, StandardCharsets.UTF_8);
                    p += byteLen + 1; // 尾部 NUL
                } else {
                    int[] r = readLen16(p, chunk);
                    int charLen = r[0];
                    p = r[1];
                    s = new String(chunk, p, charLen * 2, StandardCharsets.UTF_16LE);
                    p += charLen * 2 + 2;
                }
                sp.list.add(s);
            }
            return sp;
        }

        String get(int idx) {
            if (idx < 0 || idx >= list.size()) return null;
            return list.get(idx);
        }

        int ensure(String s) {
            int i = list.indexOf(s);
            if (i >= 0) return i;
            list.add(s);
            return list.size() - 1;
        }

        byte[] serialize() {
            int count = list.size();
            int[] offsets = new int[count];
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            for (int i = 0; i < count; i++) {
                String s = list.get(i);
                offsets[i] = data.size();
                if (isUtf8) {
                    byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
                    int utf16Len = s.length();
                    writeLen8(data, utf16Len);
                    writeLen8(data, utf8.length);
                    data.write(utf8, 0, utf8.length);
                    data.write(0);
                } else {
                    byte[] u16 = s.getBytes(StandardCharsets.UTF_16LE);
                    writeLen16(data, s.length());
                    data.write(u16, 0, u16.length);
                    data.write(0);
                    data.write(0);
                }
            }
            int dataSize = data.size();
            int headerSize = 28;
            int offsetsSize = count * 4;
            int stringsStart = headerSize + offsetsSize; // styleOffsets 为空
            int total = stringsStart + dataSize + (dataSize % 4 == 0 ? 0 : 4 - dataSize % 4);

            ByteArrayOutputStream out = new ByteArrayOutputStream(total + 8);
            write16(out, RES_STRING_POOL_TYPE);
            write16(out, headerSize);
            write32(out, total);
            write32(out, count);
            write32(out, 0);            // styleCount
            write32(out, isUtf8 ? 0x00000100 : 0x00000000);
            write32(out, stringsStart);
            write32(out, 0);            // stylesStart
            for (int i = 0; i < count; i++) write32(out, offsets[i]);
            byte[] d = data.toByteArray();
            out.write(d, 0, d.length);
            while (out.size() % 4 != 0) out.write(0);
            return out.toByteArray();
        }
    }

    // ── 变长长度前缀读写 ──

    private static int[] readLen8(int p, byte[] b) {
        int v = b[p] & 0xFF;
        if ((v & 0x80) != 0) {
            v = ((b[p + 1] & 0xFF) << 8) | (v & 0x7F);
            return new int[]{v, p + 2};
        }
        return new int[]{v, p + 1};
    }

    private static int[] readLen16(int p, byte[] b) {
        int v = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
        if ((v & 0x8000) != 0) {
            v = ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 8) | (v & 0x7FFF);
            return new int[]{v, p + 4};
        }
        return new int[]{v, p + 2};
    }

    private static void writeLen8(ByteArrayOutputStream o, int v) {
        if (v > 0x7F) {
            o.write((v & 0x7F) | 0x80);
            o.write((v >> 8) & 0xFF);
        } else {
            o.write(v & 0xFF);
        }
    }

    private static void writeLen16(ByteArrayOutputStream o, int v) {
        if (v > 0x7FFF) {
            o.write(((v & 0x7FFF) | 0x8000) & 0xFF);
            o.write((v >> 8) & 0xFF);
            o.write((v >> 16) & 0xFF);
        } else {
            o.write(v & 0xFF);
            o.write((v >> 8) & 0xFF);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  字节写出
    // ═══════════════════════════════════════════════════════════

    private static void write16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void write32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    private static void write8(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
    }
}
