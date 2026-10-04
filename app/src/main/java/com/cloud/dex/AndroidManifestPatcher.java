package com.cloud.dex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AndroidManifest.xml 权限检测 / 补齐工具。
 *
 * <p>APK 里的 AndroidManifest.xml 是编译后的二进制 XML（AXML）。本类负责在“注入 xiao.dex”
 * 流程中，检查目标 APK 是否声明了 xiao.dex 运行所需的权限，若缺失则把对应的
 * {@code <uses-permission android:name="..."/>} 追加进清单，并重建可被系统正常解析的 AXML 字节。</p>
 *
 * <p>实现要点：
 * <ol>
 *   <li>解析 AXML 的字符串池、资源映射表（ResourceMap）与 XML 元素；</li>
 *   <li>读取清单中已声明的 {@code <uses-permission>}；</li>
 *   <li>对缺失权限，**克隆**一个已有的单属性 {@code <uses-permission>} 元素，
 *       只改写其 {@code android:name} 指向的字符串索引——这样可最大程度复刻 aapt
 *       生成的元素结构（标签名索引、命名空间、属性名索引及其 ResourceId 0x01010003），
 *       保证系统解析结果与正常打包完全一致；</li>
 *   <li>把新增权限字符串追加到字符串池末尾后重建字符串池与整个 AXML。</li>
 * </ol>
 * </p>
 *
 * <p>注意：字符串池的 style 数据在本实现中未做搬迁（清单文件通常 styleCount == 0），
 * 若遇到带 style 的字符串池会抛出异常，由调用方降级处理（保持原清单不变）。</p>
 */
public final class AndroidManifestPatcher {

    private AndroidManifestPatcher() {
    }

    // ── AXML 常量 ──
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int RES_XML_END_ELEMENT_TYPE = 0x0103;
    private static final int RES_XML_RESOURCE_MAP_TYPE = 0x0180;

    private static final int UTF8_FLAG = 0x00000100;
    private static final int TYPE_STRING = 0x03;

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String TAG_MANIFEST = "manifest";
    private static final String TAG_USES_PERMISSION = "uses-permission";
    private static final String ATTR_NAME = "name";

    /** 权限检测 / 补齐的结果。 */
    public static final class Result {
        /** 处理后（可能被补权限）的 AndroidManifest.xml 字节。 */
        public byte[] data;
        /** 原清单中已声明的权限。 */
        public List<String> existing = new ArrayList<>();
        /** 本次新补充的权限。 */
        public List<String> added = new ArrayList<>();

        public boolean changed() {
            return added != null && !added.isEmpty();
        }
    }

    // ── 内部：chunk 描述 ──
    private static final class Chunk {
        int type;
        int offset;
        byte[] raw;
    }

    /**
     * 确保清单声明了所有 required 权限；缺失的会被追加。
     *
     * @param manifest 原始 AndroidManifest.xml（AXML）字节
     * @param required 需要的权限全名列表，如 {@code android.permission.INTERNET}
     * @return 结果；若无需修改，{@link Result#data} 与原清单相同
     * @throws IOException 清单非法、字符串池含 style 或缺少可克隆元素时抛出
     */
    public static Result ensurePermissions(byte[] manifest, List<String> required) throws IOException {
        if (manifest == null || manifest.length < 8) {
            throw new IOException("AndroidManifest 数据为空或过短");
        }
        int magic = u16(manifest, 0);
        if (magic != RES_XML_TYPE) {
            throw new IOException("不是二进制 XML(type=0x" + Integer.toHexString(magic) + ")，可能并非编译后的清单");
        }

        List<Chunk> chunks = parseChunks(manifest, 8);
        Chunk sp = null;
        Chunk resMap = null;
        List<Chunk> others = new ArrayList<>();
        for (Chunk c : chunks) {
            if (c.type == RES_STRING_POOL_TYPE && sp == null) {
                sp = c;
            } else if (c.type == RES_XML_RESOURCE_MAP_TYPE) {
                resMap = c;
            } else {
                others.add(c);
            }
        }
        if (sp == null) {
            throw new IOException("未找到字符串池");
        }

        // ── 解析字符串池 ──
        int count = i32(sp.raw, 8);
        int styleCount = i32(sp.raw, 12);
        int flags = i32(sp.raw, 16);
        int stringsStart = i32(sp.raw, 20);
        boolean isUtf8 = (flags & UTF8_FLAG) != 0;
        if (styleCount != 0) {
            throw new IOException("字符串池包含 style 数据(styleCount=" + styleCount + ")，暂不支持改写");
        }
        if (count < 0 || count > 1_000_000) {
            throw new IOException("字符串池数量异常: " + count);
        }
        String[] strings = new String[count];
        for (int i = 0; i < count; i++) {
            int off = i32(sp.raw, 28 + i * 4);
            strings[i] = decodeString(sp.raw, stringsStart + off, isUtf8);
        }

        // ── 扫描元素：已声明权限 / manifest 位置 / 可克隆模板 ──
        List<String> existing = new ArrayList<>();
        int manifestIdx = -1;
        Chunk sampleStart = null;
        Chunk sampleEnd = null;

        for (int i = 0; i < others.size(); i++) {
            Chunk c = others.get(i);
            if (c.type != RES_XML_START_ELEMENT_TYPE) {
                continue;
            }
            int nameIdx = i32(c.raw, 20);
            String tag = (nameIdx >= 0 && nameIdx < strings.length) ? strings[nameIdx] : null;
            if (TAG_MANIFEST.equals(tag) && manifestIdx < 0) {
                manifestIdx = i;
            }
            if (!TAG_USES_PERMISSION.equals(tag)) {
                continue;
            }

            int attrCount = u16(c.raw, 28);
            for (int a = 0; a < attrCount; a++) {
                int base = 36 + a * 20;
                int aName = i32(c.raw, base + 4);
                if (aName < 0 || aName >= strings.length || !ATTR_NAME.equals(strings[aName])) {
                    continue;
                }
                int raw = i32(c.raw, base + 8);
                int dataType = c.raw[base + 15] & 0xFF;
                int data = i32(c.raw, base + 16);
                int sidx = raw >= 0 ? raw : (dataType == TYPE_STRING ? data : -1);
                if (sidx >= 0 && sidx < strings.length) {
                    existing.add(strings[sidx]);
                }
            }

            // 选一个“只有 1 个属性”的 uses-permission 作为克隆模板
            if (sampleStart == null && attrCount == 1
                    && i + 1 < others.size()
                    && others.get(i + 1).type == RES_XML_END_ELEMENT_TYPE) {
                sampleStart = c;
                sampleEnd = others.get(i + 1);
            }
        }

        // ── 计算缺失权限 ──
        Set<String> existingSet = new LinkedHashSet<>(existing);
        List<String> missing = new ArrayList<>();
        for (String p : required) {
            if (p != null && !p.isEmpty() && !existingSet.contains(p)) {
                missing.add(p);
            }
        }

        Result result = new Result();
        result.existing = existing;
        if (missing.isEmpty()) {
            result.data = manifest;
            result.added = new ArrayList<>();
            return result;
        }
        if (sampleStart == null) {
            throw new IOException("清单中找不到可克隆的单属性 uses-permission 元素");
        }

        // ── 追加字符串 ──
        List<String> newStrings = new ArrayList<>(Arrays.asList(strings));
        for (String p : missing) {
            if (!newStrings.contains(p)) {
                newStrings.add(p);
            }
        }

        byte[] newPool = buildStringPool(newStrings, isUtf8);

        // ── 组装新的 AXML ──
        ByteArrayOutputStream body = new ByteArrayOutputStream(manifest.length + missing.size() * 80 + 256);
        body.write(newPool, 0, newPool.length);
        if (resMap != null) {
            body.write(resMap.raw, 0, resMap.raw.length);
        }
        for (int i = 0; i < others.size(); i++) {
            Chunk c = others.get(i);
            body.write(c.raw, 0, c.raw.length);
            if (i == manifestIdx) {
                for (String p : missing) {
                    int strIdx = newStrings.indexOf(p);
                    byte[] startChunk = clonePermissionElement(sampleStart.raw, strings, strIdx);
                    body.write(startChunk, 0, startChunk.length);
                    body.write(sampleEnd.raw, 0, sampleEnd.raw.length);
                }
            }
        }

        byte[] bodyBytes = body.toByteArray();
        int total = 8 + bodyBytes.length;
        byte[] out = new byte[total];
        writeU16(out, 0, RES_XML_TYPE);
        writeU16(out, 2, 8);
        writeI32(out, 4, total);
        System.arraycopy(bodyBytes, 0, out, 8, bodyBytes.length);

        result.data = out;
        result.added = missing;
        return result;
    }

    /** 只读取清单已声明的权限（不做修改）。 */
    public static List<String> readPermissions(byte[] manifest) throws IOException {
        Result r = ensurePermissions(manifest, java.util.Collections.<String>emptyList());
        return r.existing;
    }

    /**
     * 克隆一个已存在的 {@code <uses-permission>} START 元素，只把 {@code android:name}
     * 属性指向的字符串索引改写为 {@code newStrIdx}。END 元素结构不含会变化的字符串引用，
     * 可直接复用。
     */
    private static byte[] clonePermissionElement(byte[] sample, String[] strings, int newStrIdx) throws IOException {
        byte[] raw = sample.clone();
        int attrCount = u16(raw, 28);
        for (int a = 0; a < attrCount; a++) {
            int base = 36 + a * 20;
            int aName = i32(raw, base + 4);
            if (aName >= 0 && aName < strings.length && ATTR_NAME.equals(strings[aName])) {
                writeI32(raw, base + 8, newStrIdx);   // rawValue（字符串池索引）
                raw[base + 15] = (byte) TYPE_STRING;  // dataType
                writeI32(raw, base + 16, newStrIdx);  // typedValue.data
                return raw;
            }
        }
        throw new IOException("克隆模板缺少 android:name 属性");
    }

    // ── 字符串池重建 ──

    private static byte[] buildStringPool(List<String> strings, boolean isUtf8) throws IOException {
        int count = strings.size();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = data.size();
            byte[] enc = encodeString(strings.get(i), isUtf8);
            data.write(enc, 0, enc.length);
        }
        while (data.size() % 4 != 0) {
            data.write(0);
        }

        final int headerSize = 28;
        final int styleCount = 0;
        int stringsStart = headerSize + count * 4 + styleCount * 4;
        int chunkSize = stringsStart + data.size();

        byte[] out = new byte[chunkSize];
        writeU16(out, 0, RES_STRING_POOL_TYPE);
        writeU16(out, 2, headerSize);
        writeI32(out, 4, chunkSize);
        writeI32(out, 8, count);
        writeI32(out, 12, styleCount);
        // 追加字符串后不再是“已排序”，清掉 SORTED 标志；保留 UTF-8 标志
        writeI32(out, 16, isUtf8 ? UTF8_FLAG : 0);
        writeI32(out, 20, stringsStart);
        writeI32(out, 24, 0);
        for (int i = 0; i < count; i++) {
            writeI32(out, 28 + i * 4, offsets[i]);
        }
        byte[] dd = data.toByteArray();
        System.arraycopy(dd, 0, out, stringsStart, dd.length);
        return out;
    }

    private static byte[] encodeString(String s, boolean isUtf8) {
        if (isUtf8) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            int utf16Len = s.getBytes(StandardCharsets.UTF_16LE).length / 2;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            if (utf16Len > 0x7F) {
                o.write(((utf16Len >> 8) & 0x7F) | 0x80);
                o.write(utf16Len & 0xFF);
            } else {
                o.write(utf16Len);
            }
            if (utf8.length > 0x7F) {
                o.write(((utf8.length >> 8) & 0x7F) | 0x80);
                o.write(utf8.length & 0xFF);
            } else {
                o.write(utf8.length);
            }
            o.write(utf8, 0, utf8.length);
            o.write(0);
            return o.toByteArray();
        } else {
            byte[] utf16 = s.getBytes(StandardCharsets.UTF_16LE);
            int n = utf16.length / 2;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            // 长度是小端 u16；若最高位长度超过 0x7FFF，则首 u16 置 0x8000 标志并再接一个 u16
            if (n > 0x7FFF) {
                int first = ((n >> 16) & 0x7FFF) | 0x8000;
                int second = n & 0xFFFF;
                o.write(first & 0xFF);
                o.write((first >> 8) & 0xFF);
                o.write(second & 0xFF);
                o.write((second >> 8) & 0xFF);
            } else {
                o.write(n & 0xFF);
                o.write((n >> 8) & 0xFF);
            }
            o.write(utf16, 0, utf16.length);
            o.write(0);
            o.write(0);
            return o.toByteArray();
        }
    }

    private static String decodeString(byte[] buf, int off, boolean isUtf8) {
        if (isUtf8) {
            int p = off;
            int n = buf[p++] & 0xFF;
            if ((n & 0x80) != 0) {
                n = ((n & 0x7F) << 8) | (buf[p++] & 0xFF);
            }
            int blen = buf[p++] & 0xFF;
            if ((blen & 0x80) != 0) {
                blen = ((blen & 0x7F) << 8) | (buf[p++] & 0xFF);
            }
            return new String(buf, p, blen, StandardCharsets.UTF_8);
        } else {
            int p = off;
            int n = u16(buf, p);
            p += 2;
            if ((n & 0x8000) != 0) {
                n = ((n & 0x7FFF) << 16) | u16(buf, p);
                p += 2;
            }
            return new String(buf, p, n * 2, StandardCharsets.UTF_16LE);
        }
    }

    // ── chunk 遍历 ──

    private static List<Chunk> parseChunks(byte[] d, int start) {
        List<Chunk> out = new ArrayList<>();
        int pos = start;
        int n = d.length;
        while (pos + 8 <= n) {
            int size = i32(d, pos + 4);
            if (size < 8 || pos + size > n) {
                break;
            }
            Chunk c = new Chunk();
            c.type = u16(d, pos);
            c.offset = pos;
            c.raw = new byte[size];
            System.arraycopy(d, pos, c.raw, 0, size);
            out.add(c);
            pos += size;
        }
        return out;
    }

    // ── 小端读写 ──

    private static int u16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }

    private static void writeU16(byte[] d, int o, int v) {
        d[o] = (byte) (v & 0xFF);
        d[o + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void writeI32(byte[] d, int o, int v) {
        d[o] = (byte) (v & 0xFF);
        d[o + 1] = (byte) ((v >> 8) & 0xFF);
        d[o + 2] = (byte) ((v >> 16) & 0xFF);
        d[o + 3] = (byte) ((v >> 24) & 0xFF);
    }
}
