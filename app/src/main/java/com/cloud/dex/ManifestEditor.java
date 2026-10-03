package com.cloud.dex;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 目标 APK 的 AndroidManifest.xml 权限检测与自动补齐工具。
 *
 * 直接操作二进制 AXML 格式，无需依赖 aapt 或 apktool。
 * 在注入 xiao.dex 前调用，确保目标 APK 具备所依赖的权限。
 */
public class ManifestEditor {

    private static final String TAG = "ManifestEditor";

    // ──────────────────────────────────────────────
    //  xiao.dex 运行时依赖的权限 ── 按需修改此列表
    // ──────────────────────────────────────────────
    public static final String[] REQUIRED_PERMISSIONS = {
            "android.permission.INTERNET",                // 卡密验证等网络请求
            "android.permission.ACCESS_NETWORK_STATE",    // 检测网络状态
            "android.permission.READ_EXTERNAL_STORAGE",   // 文件存储 (API<33)
            "android.permission.WRITE_EXTERNAL_STORAGE",  // 写入 /storage/emulated/0/Cloud/uuid.data
            "android.permission.MANAGE_EXTERNAL_STORAGE"  // 全部文件管理 (API≥30)
    };

    // ── AXML 常量 ──
    private static final int AXML_MAGIC         = 0x00080003;
    private static final int CHUNK_STRING_POOL  = 0x001C0001;
    private static final int CHUNK_RESOURCE_MAP = 0x00080180;
    private static final int CHUNK_START_NS     = 0x00100100;
    private static final int CHUNK_END_NS       = 0x00100101;
    private static final int CHUNK_START_ELEM   = 0x00100102;
    private static final int CHUNK_END_ELEM     = 0x00100103;

    private static final int ATTR_TYPE_STRING   = 0x03000008;

    // ── 通用 string pool 索引 ──
    // 绝大多数 AXML 中 android 命名空间 URI 字符串和 uses-permission 元素名是固定的；
    // 这里不假设固定索引，而在解析时动态查找。为方便回写，会缓存下来。

    // ──────────────────────────────────────────────
    //  公开入口
    // ──────────────────────────────────────────────

    /**
     * 解析目标 APK 内已声明的权限列表。
     */
    public static Set<String> getExistingPermissions(byte[] axml) {
        Set<String> perms = new HashSet<>();
        ParseContext ctx = new ParseContext(axml);
        while (ctx.hasMore()) {
            int chunkType = ctx.peekChunkType();
            int chunkSize = ctx.peekChunkSize();
            if (chunkType == CHUNK_STRING_POOL) {
                ctx.parseStringPool();
            } else if (chunkType == CHUNK_RESOURCE_MAP) {
                ctx.skip(chunkSize);
            } else if (chunkType == CHUNK_START_ELEM) {
                ctx.parseStartElement(perms, null);
            } else {
                ctx.skip(chunkSize);
            }
        }
        return perms;
    }

    /**
     * 检测并补齐缺失的权限。
     *
     * @param axml 原始 AndroidManifest.xml 的字节数组
     * @return 补齐后的字节数组；如果已完备则返回原数组 (非 null)
     */
    public static byte[] ensurePermissions(byte[] axml) {
        Set<String> existing = getExistingPermissions(axml);
        Set<String> missing = new HashSet<>(Arrays.asList(REQUIRED_PERMISSIONS));
        missing.removeAll(existing);

        if (missing.isEmpty()) {
            Log.d(TAG, "权限已齐全，无需补齐");
            return axml;  // 无需修改
        }

        Log.i(TAG, "缺失权限: " + missing + "，开始自动补齐");
        return addPermissions(axml, missing);
    }

    // ──────────────────────────────────────────────
    //  权限补齐核心：修改 AXML 字节
    // ──────────────────────────────────────────────

    private static byte[] addPermissions(byte[] axml, Set<String> permissions) {
        // 第一步：深度解析 string pool 和 XML 结构
        DeepParseResult parse = deepParse(axml);

        // 第二步：为每个缺失权限在 string pool 中添加字符串
        List<String> newStrings = new ArrayList<>();
        for (String perm : permissions) {
            if (!parse.stringPool.contains(perm)) {
                newStrings.add(perm);
            }
        }
        // 同时确保 "http://schemas.android.com/apk/res/android" 和 "uses-permission" 和 "name" 在池中
        String androidNs = "http://schemas.android.com/apk/res/android";
        String elemName  = "uses-permission";
        String attrName  = "name";
        for (String s : new String[]{androidNs, elemName, attrName}) {
            if (!parse.stringPool.contains(s) && !newStrings.contains(s)) {
                newStrings.add(0, s);   // 优先加入，保证在前面
            }
        }

        // 第三步：构建输出
        int oldPoolSize = parse.stringPoolChunkSize;
        int newPoolByteSize = estimateNewPoolSize(parse, newStrings);
        int poolDelta = newPoolByteSize - oldPoolSize;

        int totalSize = axml.length + poolDelta
                + (permissions.size() * (START_ELEM_SIZE + END_ELEM_SIZE));

        ByteBuffer out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);

        // 3a. 复制头部并更新 totalSize
        out.put(axml, 0, 4);                       // magic
        out.putInt(totalSize);                      // 新 fileSize

        // 3b. 写新 string pool
        writeNewStringPool(out, parse, newStrings);

        // 3c. 复制 string pool 之后的内容，在插入点前
        int afterPool = parse.stringPoolStart + oldPoolSize;
        int insertAt   = parse.insertionOffset;

        // 复制 string pool 后到插入点之间的内容（region after pool, before insert point）
        int copyLen = insertAt - afterPool;
        if (copyLen > 0) {
            out.put(axml, afterPool, copyLen);
        }

        // 3d. 写入新的 uses-permission 元素
        //    需要 androidNs 和 elemName 在池中的索引
        int nsIdx   = findStringIndex(parse, newStrings, androidNs);
        int elemIdx = findStringIndex(parse, newStrings, elemName);
        int attrIdx = findStringIndex(parse, newStrings, attrName);

        for (String perm : permissions) {
            int permIdx = findStringIndex(parse, newStrings, perm);
            writeUsesPermission(out, nsIdx, elemIdx, attrIdx, permIdx, insertAt);
        }

        // 3e. 复制插入点之后的剩余内容
        int remaining = axml.length - insertAt;
        if (remaining > 0) {
            out.put(axml, insertAt, remaining);
        }

        Log.i(TAG, "补齐完成: +" + permissions.size() + " 个权限, AXML " + axml.length + " → " + totalSize + " bytes");
        return out.array();
    }

    // ──────────────────────────────────────────────
    //  AXML 解析
    // ──────────────────────────────────────────────

    static class ParseContext {
        final byte[] data;
        int pos;

        ParseContext(byte[] data) { this.data = data; this.pos = 0; }

        boolean hasMore() { return pos < data.length; }
        int peekChunkType() { return ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(); }
        int peekChunkSize() { return ByteBuffer.wrap(data, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(); }

        void skip(int n) { pos += n; }

        void parseStringPool() {
            int type = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int size = ByteBuffer.wrap(data, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (type != CHUNK_STRING_POOL) return;
            pos += size;   // 跳过整个 chunk
        }

        /**
         * 轻量解析 START_ELEMENT，仅提取 uses-permission 的 name。
         */
        void parseStartElement(Set<String> perms, String parentName) {
            ByteBuffer bb = ByteBuffer.wrap(data, pos, 40).order(ByteOrder.LITTLE_ENDIAN);
            int type = bb.getInt();
            int size = bb.getInt();
            if (type != CHUNK_START_ELEM) {
                pos += size;
                return;
            }
            bb.getInt(); // lineNumber
            bb.getInt(); // comment
            int nsIdx  = bb.getInt();
            int nameIdx = bb.getInt();
            bb.getInt(); // flags
            int attrCount = bb.getInt();
            bb.getInt(); // classAttribute

            // 读取该元素在 string pool 中的名字，需要从外层传入 pool；轻量模式下暂跳过
            pos += size;
        }
    }

    /** 深度解析结果 */
    static class DeepParseResult {
        int stringPoolStart;          // string pool 在原始 axml 中的偏移
        int stringPoolChunkSize;      // 原始 string pool chunk 大小
        int stringCount;              // 原始字符串数量
        List<String> stringPool;      // 按索引排序的字符串列表
        int insertionOffset;          // 插入新权限的最佳偏移（第一个 uses-permission 之后，或 application 之前）
    }

    private static DeepParseResult deepParse(byte[] axml) {
        DeepParseResult r = new DeepParseResult();
        r.stringPool = new ArrayList<>();
        r.insertionOffset = -1;

        ByteBuffer bb = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);

        // 跳过 magic + fileSize (8 bytes)
        int pos = 8;

        // 解析 string pool
        int spType = bb.getInt(pos);
        if (spType != CHUNK_STRING_POOL) {
            throw new RuntimeException("AXML 格式异常：第一个 chunk 不是 StringPool");
        }
        r.stringPoolStart = pos;
        int spSize = bb.getInt(pos + 4);
        r.stringPoolChunkSize = spSize;
        int strCount = bb.getInt(pos + 8);
        r.stringCount = strCount;
        int styleCount = bb.getInt(pos + 12);
        int flags = bb.getInt(pos + 16);
        int stringsStart = bb.getInt(pos + 20);
        int stylesStart = bb.getInt(pos + 24);

        // 解析每个字符串
        boolean isUtf8 = (flags & 0x100) != 0;
        for (int i = 0; i < strCount; i++) {
            int offset = bb.getInt(pos + 28 + i * 4);
            int strAbs = pos + stringsStart + offset;
            String s;
            if (isUtf8) {
                // UTF-8 编码：前 1-2 字节是长度
                int len = axml[strAbs] & 0xFF;
                int skip = 1;
                if ((len & 0x80) != 0) {
                    len = ((len & 0x7F) << 8) | (axml[strAbs + 1] & 0xFF);
                    skip = 2;
                }
                s = new String(axml, strAbs + skip, len, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                // UTF-16 LE 编码：前 2 字节是字符数
                int charLen = ((axml[strAbs + 1] & 0xFF) << 8) | (axml[strAbs] & 0xFF);
                s = new String(axml, strAbs + 2, charLen * 2, java.nio.charset.StandardCharsets.UTF_16LE);
            }
            r.stringPool.add(s);
        }

        // 跳过 string pool，继续解析 XML 结构，找插入点
        pos += spSize;
        int lastPermissionAfter = -1;
        int applicationBefore = -1;

        int androidNsPoolIdx = r.stringPool.indexOf("http://schemas.android.com/apk/res/android");
        int usesPermPoolIdx = r.stringPool.indexOf("uses-permission");
        int applicationPoolIdx = r.stringPool.indexOf("application");

        while (pos < axml.length) {
            int chunkType = ByteBuffer.wrap(axml, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int chunkSize = ByteBuffer.wrap(axml, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

            if (chunkType == CHUNK_START_ELEM) {
                int nsIdx   = ByteBuffer.wrap(axml, pos + 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                int nameIdx = ByteBuffer.wrap(axml, pos + 20, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

                if (nsIdx == androidNsPoolIdx && nameIdx == usesPermPoolIdx) {
                    // uses-permission 元素：标记此位置之后
                    lastPermissionAfter = pos + chunkSize;
                } else if ((nsIdx == androidNsPoolIdx || nsIdx == -1) && nameIdx == applicationPoolIdx) {
                    // application 元素：以此为插入点
                    applicationBefore = pos;
                    // 找到 application 后就可以停止了
                    break;
                }
            }

            pos += chunkSize;
        }

        // 决定插入位置：优先在最后一个 uses-permission 之后
        if (lastPermissionAfter > 0) {
            r.insertionOffset = lastPermissionAfter;
        } else if (applicationBefore > 0) {
            r.insertionOffset = applicationBefore;
        } else {
            // 找不到插入点，放在 manifest 结束之前（倒数第二个 chunk）
            r.insertionOffset = axml.length - 24;  // 最后一个 END_ELEMENT 一般是 </manifest>
        }

        return r;
    }

    // ──────────────────────────────────────────────
    //  String pool 辅助
    // ──────────────────────────────────────────────

    private static int findStringIndex(DeepParseResult parse, List<String> newStrings, String target) {
        // 先在原始池中找
        int idx = parse.stringPool.indexOf(target);
        if (idx >= 0) return idx;

        // 再在新字符串列表中找
        idx = newStrings.indexOf(target);
        if (idx >= 0) return parse.stringCount + idx;

        throw new RuntimeException("字符串未加入 pool: " + target);
    }

    /**
     * 估算新 string pool 大小。
     * 新字符串以 UTF-8 编码（flags |= 0x100）。
     */
    private static int estimateNewPoolSize(DeepParseResult parse, List<String> newStrings) {
        int totalNew = parse.stringCount + newStrings.size();
        int headerSize = 28 + totalNew * 4;  // 28 头 + stringOffsets
        int dataSize = 0;
        // 原始所有字符串的 data size
        for (String s : parse.stringPool) {
            byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            dataSize += 2 + b.length + 1;  // 2-byte length + data + NUL
        }
        for (String s : newStrings) {
            byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            dataSize += 2 + b.length + 1;
        }
        return headerSize + dataSize;
    }

    private static void writeNewStringPool(ByteBuffer out, DeepParseResult parse, List<String> newStrings) {
        int totalCount = parse.stringCount + newStrings.size();
        int headerSize = 28 + totalCount * 4;
        int flags = 0x100;  // UTF-8

        // 计算每个字符串的 data offset 和 data 总大小
        int[] offsets = new int[totalCount];
        List<byte[]> encodedStrings = new ArrayList<>();

        int dataOffset = 0;
        for (String s : parse.stringPool) {
            offsets[encodedStrings.size()] = dataOffset;
            byte[] b = encodeString(s);
            encodedStrings.add(b);
            dataOffset += b.length;
        }
        for (String s : newStrings) {
            offsets[encodedStrings.size()] = dataOffset;
            byte[] b = encodeString(s);
            encodedStrings.add(b);
            dataOffset += b.length;
        }

        int totalSize = headerSize + dataOffset;

        // 写 chunk header
        out.putInt(CHUNK_STRING_POOL);   // type
        out.putInt(totalSize);           // size
        out.putInt(totalCount);          // stringCount
        out.putInt(0);                   // styleCount
        out.putInt(flags);               // flags (UTF-8)
        out.putInt(headerSize);          // stringsStart
        out.putInt(headerSize);          // stylesStart (no styles)

        // 写 offsets
        for (int i = 0; i < totalCount; i++) {
            out.putInt(offsets[i]);
        }

        // 写 string data
        for (byte[] b : encodedStrings) {
            out.put(b);
        }
    }

    /**
     * AXML UTF-8 编码：2 字节长度 (可变长，此处简化为 2 字节) + 原始 UTF-8 字节 + NUL
     */
    private static byte[] encodeString(String s) {
        byte[] utf8 = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int len = utf8.length;
        byte[] result = new byte[len + 3];  // 2-byte len + data + NUL
        if (len < 128) {
            result[0] = (byte) len;
            result[1] = 0;
        } else {
            result[0] = (byte) ((len & 0xFF00) >> 8 | 0x80);
            result[1] = (byte) (len & 0xFF);
        }
        System.arraycopy(utf8, 0, result, 2, len);
        result[result.length - 1] = 0;
        return result;
    }

    // ──────────────────────────────────────────────
    //  XML 元素写入
    // ──────────────────────────────────────────────

    private static final int START_ELEM_SIZE = 36 + 20;   // 36 header + 20 (1 attribute)
    private static final int END_ELEM_SIZE   = 24;

    private static void writeUsesPermission(ByteBuffer out, int nsIdx, int elemIdx, int attrIdx, int permIdx, int fakeLine) {
        // START_ELEMENT
        int startSize = START_ELEM_SIZE;
        out.putInt(CHUNK_START_ELEM);
        out.putInt(startSize);
        out.putInt(fakeLine);           // lineNumber
        out.putInt(0xFFFFFFFF);         // comment
        out.putInt(nsIdx);              // namespace
        out.putInt(elemIdx);            // element name "uses-permission"
        out.putInt(0x00140014);         // flags (ELEMENT_HAS_NS)
        out.putInt(1);                  // attributeCount
        out.putInt(0);                  // classAttribute

        // attribute: android:name="permission"
        out.putInt(nsIdx);              // attr namespace
        out.putInt(attrIdx);            // attr name "name"
        out.putInt(permIdx);            // rawValue string index
        out.putInt(ATTR_TYPE_STRING);   // type
        out.putInt(permIdx);            // data (string index)

        // END_ELEMENT
        int endSize = END_ELEM_SIZE;
        out.putInt(CHUNK_END_ELEM);
        out.putInt(endSize);
        out.putInt(fakeLine);
        out.putInt(0xFFFFFFFF);
        out.putInt(nsIdx);
        out.putInt(elemIdx);
    }
}