package com.cloud.dex;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
 *
 * 核心策略：保留原始 string pool 的编码（UTF-8/UTF-16）不动，
 * 仅追加新的权限字符串到池末尾，并在 XML 树中插入对应元素。
 */
public class ManifestEditor {

    private static final String TAG = "ManifestEditor";

    // ──────────────────────────────────────────────
    //  xiao.dex 运行时依赖的权限
    // ──────────────────────────────────────────────
    public static final String[] REQUIRED_PERMISSIONS = {
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE"
    };

    // ── AXML chunk type ──
    private static final int AXML_MAGIC         = 0x00080003;
    private static final int CHUNK_STRING_POOL  = 0x001C0001;
    private static final int CHUNK_START_NS     = 0x00100100;
    private static final int CHUNK_END_NS       = 0x00100101;
    private static final int CHUNK_START_ELEM   = 0x00100102;
    private static final int CHUNK_END_ELEM     = 0x00100103;

    private static final int ATTR_TYPE_STRING   = 0x03000008;

    // ──────────────────────────────────────────────
    //  公开入口
    // ──────────────────────────────────────────────

    /** 解析目标 APK 的 AndroidManifest.xml 中已声明的权限。 */
    public static Set<String> getExistingPermissions(byte[] axml) {
        Set<String> perms = new HashSet<>();
        PoolInfo poolInfo = parsePool(axml);
        if (poolInfo == null) return perms;

        int pos = 8 + poolInfo.chunkSize; // 跳过 header + string pool
        int androidNsIdx = poolInfo.strings.indexOf("http://schemas.android.com/apk/res/android");
        int usesPermIdx  = poolInfo.strings.indexOf("uses-permission");
        int nameIdx      = poolInfo.strings.indexOf("name");

        while (pos + 8 <= axml.length) {
            int type = getIntLE(axml, pos);
            int size = getIntLE(axml, pos + 4);
            if (size < 8) break;

            if (type == CHUNK_START_ELEM) {
                int ns   = getIntLE(axml, pos + 16);
                int name = getIntLE(axml, pos + 20);
                int attrs = getIntLE(axml, pos + 28);
                if (ns == androidNsIdx && name == usesPermIdx && attrs >= 1) {
                    int attrVal = getIntLE(axml, pos + 36 + 8); // attribute[0].rawValue at offset 36+8
                    if (attrVal >= 0 && attrVal < poolInfo.strings.size()) {
                        perms.add(poolInfo.strings.get(attrVal));
                    }
                }
            }
            pos += size;
        }
        return perms;
    }

    /**
     * 检测并补齐缺失的权限。
     * @return 补齐后字节；若无需修改则返回原数组。
     */
    public static byte[] ensurePermissions(byte[] axml) {
        Set<String> existing = getExistingPermissions(axml);
        Set<String> missing = new HashSet<>(Arrays.asList(REQUIRED_PERMISSIONS));
        missing.removeAll(existing);

        if (missing.isEmpty()) {
            Log.d(TAG, "权限已齐全，无需补齐");
            return axml;
        }
        Log.i(TAG, "缺失权限: " + missing + "，开始自动补齐");
        return addPermissions(axml, missing);
    }

    // ──────────────────────────────────────────────
    //  String Pool 解析（不破坏原始编码）
    // ──────────────────────────────────────────────

    static class PoolInfo {
        int chunkStart;         // pool chunk 在 axml 中的起始偏移
        int chunkSize;          // 原始 chunk 总大小
        int strCount;           // 原始字符串数
        int flags;              // 编码标志：0=UTF-16, 0x100=UTF-8
        int stringsStart;       // 字符串数据相对 chunk start 的偏移
        int stylesStart;        // 样式数据相对 chunk start 的偏移
        List<String> strings;   // 解码后的字符串列表（按索引）
        // 原始字符串在 chunk 内的字节偏移（相对 stringsStart）
        int[] rawOffsets;
        // 原始字符串编码后的字节长度（含长度前缀 + 数据 + NULL终止符）
        int[] rawByteLengths;
    }

    private static PoolInfo parsePool(byte[] axml) {
        if (axml.length < 36) return null;
        if (getIntLE(axml, 0) != AXML_MAGIC) return null;

        int pos = 8;
        int type = getIntLE(axml, pos);
        if (type != CHUNK_STRING_POOL) return null;

        PoolInfo info = new PoolInfo();
        info.chunkStart = pos;
        info.chunkSize  = getIntLE(axml, pos + 4);
        info.strCount   = getIntLE(axml, pos + 8);
        int styleCount  = getIntLE(axml, pos + 12);
        info.flags      = getIntLE(axml, pos + 16);
        info.stringsStart = getIntLE(axml, pos + 20);
        info.stylesStart  = getIntLE(axml, pos + 24);

        boolean isUtf8 = (info.flags & 0x100) != 0;
        info.strings = new ArrayList<>(info.strCount);
        info.rawOffsets = new int[info.strCount];
        info.rawByteLengths = new int[info.strCount];

        int offsetsBase = pos + 28;
        int dataBase = pos + info.stringsStart;

        for (int i = 0; i < info.strCount; i++) {
            int offset = getIntLE(axml, offsetsBase + i * 4);
            info.rawOffsets[i] = offset;
            int strPos = dataBase + offset;

            if (isUtf8) {
                // UTF-8: [1-2 byte len] [UTF-8 data] [0x00]
                int b0 = axml[strPos] & 0xFF;
                int skip;
                int byteLen;
                if ((b0 & 0x80) != 0) {
                    int b1 = axml[strPos + 1] & 0xFF;
                    byteLen = ((b0 & 0x7F) << 8) | b1;
                    skip = 2;
                } else {
                    byteLen = b0;
                    skip = 1;
                }
                String s = new String(axml, strPos + skip, byteLen, StandardCharsets.UTF_8);
                info.strings.add(s);
                info.rawByteLengths[i] = skip + byteLen + 1; // len prefix + data + NUL
            } else {
                // UTF-16: [2 byte charCount] [UTF-16LE data] [0x00 0x00]
                int charLen = ((axml[strPos + 1] & 0xFF) << 8) | (axml[strPos] & 0xFF);
                String s = new String(axml, strPos + 2, charLen * 2, StandardCharsets.UTF_16LE);
                info.strings.add(s);
                info.rawByteLengths[i] = 2 + charLen * 2 + 2; // charCount + data + NUL(2)
            }
        }

        return info;
    }

    // ──────────────────────────────────────────────
    //  权限补齐核心
    // ──────────────────────────────────────────────

    private static byte[] addPermissions(byte[] axml, Set<String> permissions) {
        // 1. 解析原始 string pool
        PoolInfo pool = parsePool(axml);
        if (pool == null) throw new RuntimeException("无法解析 AXML string pool");

        // 2. 找出需要加入的新权限字符串
        List<String> newPermStrings = new ArrayList<>();
        for (String p : permissions) {
            if (!pool.strings.contains(p)) {
                newPermStrings.add(p);
            }
        }

        // 3. 确保 uses-permission, name, android namespace 也在池中
        //    （正常情况下它们已经存在，这里做防御）
        ensureInPool(pool, newPermStrings, "http://schemas.android.com/apk/res/android");
        ensureInPool(pool, newPermStrings, "uses-permission");
        ensureInPool(pool, newPermStrings, "name");

        // 4. 编码新字符串（与原始编码一致）
        boolean isUtf8 = (pool.flags & 0x100) != 0;
        List<byte[]> newEncoded = new ArrayList<>();
        int newDataBytes = 0;
        for (String s : newPermStrings) {
            byte[] enc = isUtf8 ? encodeUtf8String(s) : encodeUtf16String(s);
            newEncoded.add(enc);
            newDataBytes += enc.length;
        }

        // 5. 找插入点
        int insertionOffset = findInsertionOffset(axml, pool);

        // 6. 计算新文件大小
        int newStrCount = pool.strCount + newPermStrings.size();
        int oldOffsetsSize = pool.strCount * 4;
        int newOffsetsSize = newStrCount * 4;
        int offsetsDelta = newOffsetsSize - oldOffsetsSize;
        int poolDelta = offsetsDelta + newDataBytes;

        int newPoolSize = pool.chunkSize + poolDelta;

        // 新权限元素大小
        int permsBytes = permissions.size() * (START_ELEM_SIZE + END_ELEM_SIZE);
        int totalSize = axml.length + poolDelta + permsBytes;

        // 7. 构建输出
        ByteBuffer out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);

        // --- 头部 ---
        out.putInt(AXML_MAGIC);
        out.putInt(totalSize);

        // --- 重写 string pool ---
        // chunk header
        out.putInt(CHUNK_STRING_POOL);
        out.putInt(newPoolSize);
        out.putInt(newStrCount);
        out.putInt(0); // styleCount = 0
        out.putInt(pool.flags);

        int newStringsStart = 28 + newStrCount * 4;
        int newStylesStart = newStringsStart; // no styles
        out.putInt(newStringsStart);
        out.putInt(newStylesStart);

        // offsets: 原始字符串保持原偏移，新字符串追加
        for (int i = 0; i < pool.strCount; i++) {
            out.putInt(pool.rawOffsets[i]);
        }
        int newOffsetCursor = 0;
        // 计算原始字符串数据总长度作为新字符串的起始偏移
        for (int i = 0; i < pool.strCount; i++) {
            newOffsetCursor += pool.rawByteLengths[i];
        }
        for (int i = 0; i < newPermStrings.size(); i++) {
            out.putInt(newOffsetCursor);
            newOffsetCursor += newEncoded.get(i).length;
        }

        // string data: 原始数据原封不动复制
        out.put(axml, pool.chunkStart + pool.stringsStart, pool.chunkSize - pool.stringsStart);
        // string data: 追加新字符串
        for (byte[] enc : newEncoded) {
            out.put(enc);
        }

        // --- 复制 pool 后到插入点之间的内容 ---
        int afterPool = pool.chunkStart + pool.chunkSize;
        int copyLen = insertionOffset - afterPool;
        if (copyLen > 0) {
            out.put(axml, afterPool, copyLen);
        }

        // --- 写入新 uses-permission 元素 ---
        // 查找必要字符串的索引
        int androidNsIdx = findPoolIndex(pool, newPermStrings, "http://schemas.android.com/apk/res/android");
        int elemIdx      = findPoolIndex(pool, newPermStrings, "uses-permission");
        int attrNameIdx  = findPoolIndex(pool, newPermStrings, "name");

        for (String perm : permissions) {
            int permIdx = findPoolIndex(pool, newPermStrings, perm);
            writeUsesPermission(out, androidNsIdx, elemIdx, attrNameIdx, permIdx);
        }

        // --- 复制插入点后的剩余内容 ---
        int remaining = axml.length - insertionOffset;
        if (remaining > 0) {
            out.put(axml, insertionOffset, remaining);
        }

        Log.i(TAG, "补齐完成: +" + permissions.size() + " 个权限, AXML " + axml.length + " → " + totalSize + " bytes");
        return out.array();
    }

    // ── 插入点查找 ──

    /**
     * 找到插入新权限元素的位置。
     * 优先放在最后一个 uses-permission 之后；如果没有则放在 &lt;application&gt; 之前；
     * 如果也没有则放在 manifest 结束前。
     *
     * 返回的是原始 axml 数组中的字节偏移量。
     */
    private static int findInsertionOffset(byte[] axml, PoolInfo pool) {
        int pos = 8 + pool.chunkSize;
        int androidNsIdx = pool.strings.indexOf("http://schemas.android.com/apk/res/android");
        int usesPermIdx  = pool.strings.indexOf("uses-permission");
        int applicationIdx = pool.strings.indexOf("application");

        int lastPermEnd = -1;
        int appStart = -1;

        while (pos + 8 <= axml.length) {
            int type = getIntLE(axml, pos);
            int size = getIntLE(axml, pos + 4);
            if (size < 8) break;

            if (type == CHUNK_START_ELEM) {
                int ns   = getIntLE(axml, pos + 16);
                int name = getIntLE(axml, pos + 20);

                if (ns == androidNsIdx && name == usesPermIdx) {
                    // 定位到 START_ELEMENT 之后，检查紧随的 END_ELEMENT
                    int endPos = pos + size;
                    int endType = getIntLE(axml, endPos);
                    int endSize = getIntLE(axml, endPos + 4);
                    if (endType == CHUNK_END_ELEM && endSize >= 8) {
                        lastPermEnd = endPos + endSize; // 跳过 START + END
                    } else {
                        // 防御：至少跳过 START_ELEMENT
                        lastPermEnd = pos + size;
                    }
                } else if (name == applicationIdx) {
                    appStart = pos;
                    break;
                }
            }
            pos += size;
        }

        if (lastPermEnd > 0) return lastPermEnd;
        if (appStart > 0) return appStart;
        // 兜底：放在 manifest 结束之前
        return axml.length - 24;
    }

    // ── 编码辅助 ──

    private static byte[] encodeUtf8String(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        int len = utf8.length;
        byte[] result;
        int skip;
        if (len <= 127) {
            result = new byte[1 + len + 1];
            result[0] = (byte) len;
            skip = 1;
        } else {
            result = new byte[2 + len + 1];
            result[0] = (byte) (((len >> 8) & 0x7F) | 0x80);
            result[1] = (byte) (len & 0xFF);
            skip = 2;
        }
        System.arraycopy(utf8, 0, result, skip, len);
        result[skip + len] = 0; // NULL terminator
        return result;
    }

    private static byte[] encodeUtf16String(String s) {
        byte[] utf16 = s.getBytes(StandardCharsets.UTF_16LE);
        int charLen = utf16.length / 2;
        byte[] result = new byte[2 + utf16.length + 2]; // 2-byte charCount + data + 2-byte NUL
        result[0] = (byte) (charLen & 0xFF);
        result[1] = (byte) ((charLen >> 8) & 0xFF);
        System.arraycopy(utf16, 0, result, 2, utf16.length);
        result[result.length - 2] = 0;
        result[result.length - 1] = 0;
        return result;
    }

    // ── 元素写入 ──

    private static final int START_ELEM_SIZE = 36 + 20;   // 36 header + 20 (1 attr)
    private static final int END_ELEM_SIZE   = 24;

    private static void writeUsesPermission(ByteBuffer out, int nsIdx, int elemIdx,
                                             int attrIdx, int permIdx) {
        // START_ELEMENT
        out.putInt(CHUNK_START_ELEM);
        out.putInt(START_ELEM_SIZE);
        out.putInt(0);                      // lineNumber
        out.putInt(0xFFFFFFFF);             // comment
        out.putInt(nsIdx);                  // namespace URI
        out.putInt(elemIdx);                // element name "uses-permission"
        out.putInt(0x00140014);             // flags (ELEMENT_HAS_NS)
        out.putInt(1);                      // attributeCount
        out.putInt(0);                      // classAttribute

        // attribute: android:name="permission"
        out.putInt(nsIdx);                  // attr namespace
        out.putInt(attrIdx);                // attr name "name"
        out.putInt(permIdx);                // rawValue (string index)
        out.putInt(ATTR_TYPE_STRING);       // type
        out.putInt(permIdx);                // data (string index)

        // END_ELEMENT
        out.putInt(CHUNK_END_ELEM);
        out.putInt(END_ELEM_SIZE);
        out.putInt(0);
        out.putInt(0xFFFFFFFF);
        out.putInt(nsIdx);
        out.putInt(elemIdx);
    }

    // ── 工具方法 ──

    private static int getIntLE(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void ensureInPool(PoolInfo pool, List<String> newStrings, String s) {
        if (!pool.strings.contains(s) && !newStrings.contains(s)) {
            newStrings.add(0, s);
        }
    }

    private static int findPoolIndex(PoolInfo pool, List<String> newStrings, String target) {
        int idx = pool.strings.indexOf(target);
        if (idx >= 0) return idx;
        idx = newStrings.indexOf(target);
        if (idx >= 0) return pool.strCount + idx;
        throw new RuntimeException("字符串未加入 pool: " + target);
    }
}