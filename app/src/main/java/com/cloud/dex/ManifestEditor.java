package com.cloud.dex;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ManifestEditor — 权限检测与自动补齐（最终版）。
 *
 * 算法经 Python 验证：chunk walk 自洽 + 所有字符串 null 终止。
 * 保留原始 String Pool 字节不动，仅追加新字符串；插入 uses-permission 元素。
 */
public class ManifestEditor {

    private static final String TAG = "ManifestEditor";

    public static final String[] REQUIRED_PERMISSIONS = {
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE"
    };

    // AXML 常量
    private static final int AXML_MAGIC = 0x00080003;
    private static final int CHUNK_START_ELEM = 0x00100102;
    private static final int CHUNK_END_ELEM   = 0x00100103;

    // ========== public API ==========

    /** 返回 manifest 中已声明的权限集合 */
    public static Set<String> getExistingPermissions(byte[] axml) {
        Set<String> out = new HashSet<>();
        PoolInfo pool = parsePool(axml);
        int nsIdx = pool.indexOf("http://schemas.android.com/apk/res/android");
        int elemIdx = pool.indexOf("uses-permission");
        if (nsIdx < 0 || elemIdx < 0) return out;

        int pos = 8 + pool.chunkSize;
        while (pos < axml.length) {
            int type = le16(axml, pos);
            int size = le32(axml, pos + 4);
            if (size < 8 || pos + size > axml.length) break;
            if (type == 0x0102) {  // START_ELEMENT
                int ns = le32(axml, pos + 16);
                int name = le32(axml, pos + 20);
                int attrStart = le16(axml, pos + 24);
                int attrCount = le16(axml, pos + 28);
                if (ns == nsIdx && name == elemIdx && attrCount >= 1) {
                    int rawValue = le32(axml, pos + 16 + attrStart + 8);
                    if (rawValue >= 0 && rawValue < pool.strings.size()) {
                        out.add(pool.strings.get(rawValue));
                    }
                }
            }
            pos += size;
        }
        return out;
    }

    /** 缺失权限则返回补齐后的 AXML 字节，无需修改则返回 null */
    public static byte[] ensurePermissions(byte[] axml) {
        Set<String> existing = getExistingPermissions(axml);
        Set<String> missing = new HashSet<>(Arrays.asList(REQUIRED_PERMISSIONS));
        missing.removeAll(existing);
        if (missing.isEmpty()) return null;
        Log.i(TAG, "补齐权限: " + missing);
        return addPermissions(axml, missing);
    }

    // ========== 核心算法 ==========

    private static byte[] addPermissions(byte[] axml, Set<String> permissions) {
        if (permissions.isEmpty()) return null;

        PoolInfo pool = parsePool(axml);

        // 哪些权限字符串不在 pool 中（需要追加）
        List<String> addedStr = new ArrayList<>();
        for (String p : permissions) {
            if (!pool.strings.contains(p)) addedStr.add(p);
        }

        // 编码新字符串
        boolean isUtf8 = (pool.flags & 0x100) != 0;
        List<byte[]> newEncoded = new ArrayList<>();
        int newDataBytes = 0;
        for (String s : addedStr) {
            byte[] enc = isUtf8 ? encodeUtf8(s) : encodeUtf16(s);
            newEncoded.add(enc);
            newDataBytes += enc.length;
        }

        int newStrCount = pool.strCount + addedStr.size();
        int oldDataSize = pool.chunkSize - pool.stringsStart;
        int newOffSize = newStrCount * 4;
        int newPoolSize = 28 + newOffSize + oldDataSize + newDataBytes;
        int poolDelta = newPoolSize - pool.chunkSize;

        // 找插入点
        int insertOffset = findInsertion(axml, pool);

        // 每权限 80 字节
        int permsBytes = permissions.size() * 80;
        int totalSize = axml.length + poolDelta + permsBytes;

        ByteBuffer out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);

        // 1) 文件头
        out.putInt(AXML_MAGIC);
        out.putInt(totalSize);

        // 2) 新 String Pool
        // 2a) header
        out.putInt(0x001C0001);  // type + headerSize
        out.putInt(newPoolSize);
        out.putInt(newStrCount);
        out.putInt(0);             // styleCount
        out.putInt(pool.flags);
        int newStringsStart = 28 + newOffSize;
        out.putInt(newStringsStart);
        out.putInt(newStringsStart); // stylesStart = stringsStart (无 styles)

        // 2b) 偏移表：原始字符串偏移不变，新字符串接在旧数据末尾
        for (int i = 0; i < pool.strCount; i++) {
            out.putInt(pool.rawOffsets[i]);
        }
        int cur = oldDataSize;
        for (byte[] enc : newEncoded) {
            out.putInt(cur);
            cur += enc.length;
        }

        // 2c) 字符串数据：原始数据原样 + 新数据
        //     pool 在 axml 中的字节范围：[pool.chunkStart + pool.stringsStart, pool.chunkStart + pool.chunkSize)
        out.put(axml, 8 + pool.stringsStart, oldDataSize);
        for (byte[] enc : newEncoded) out.put(enc);

        // 3) pool 之后到插入点
        int afterPool = 8 + pool.chunkSize;
        out.put(axml, afterPool, insertOffset - afterPool);

        // 4) 新 uses-permission 元素（每个 80 字节）
        int nsIdx = pool.indexOf("http://schemas.android.com/apk/res/android");
        int elemIdx = pool.indexOf("uses-permission");
        int attrIdx = pool.indexOf("name");

        for (String perm : permissions) {
            int permIdx = pool.indexOf(perm);
            if (permIdx < 0) permIdx = pool.strCount + addedStr.indexOf(perm);
            writePerm(out, nsIdx, elemIdx, attrIdx, permIdx);
        }

        // 5) 插入点之后
        out.put(axml, insertOffset, axml.length - insertOffset);

        byte[] result = out.array();

        // 自检
        if (le32(result, 4) != result.length) {
            Log.e(TAG, "BUG: AXML declared size " + le32(result, 4) + " != actual " + result.length);
        }
        if (le32(result, 0) != AXML_MAGIC) {
            Log.e(TAG, "BUG: AXML bad magic");
        }

        Log.i(TAG, "补齐完成: " + axml.length + " → " + result.length + " bytes");
        return result;
    }

    // ========== 写入一个 uses-permission 元素 ==========
    private static void writePerm(ByteBuffer out, int nsI, int elI, int atI, int valI) {
        // START_ELEMENT (56B)
        out.putInt(0x00100102); // type=0x0102, headerSize=16
        out.putInt(56);          // size
        out.putInt(0xFFFFFFFF);  // lineNumber
        out.putInt(0xFFFFFFFF);  // comment
        out.putInt(nsI);
        out.putInt(elI);
        out.putShort((short) 20);     // attributeStart
        out.putShort((short) 20);     // attributeSize
        out.putShort((short) 1);      // attributeCount
        out.putShort((short) 0xFFFF); // idIndex
        out.putShort((short) 0xFFFF); // classIndex
        out.putShort((short) 0xFFFF); // styleIndex
        // attribute[0] (20B)
        out.putInt(nsI); out.putInt(atI);
        out.putInt(valI);              // rawValue
        out.putInt(0x03000008);        // typedValue: size=8,res0=0,type=STRING
        out.putInt(valI);              // typedValue data
        // END_ELEMENT (24B)
        out.putInt(0x00180103); // type=0x0103, headerSize=24
        out.putInt(24);
        out.putInt(0xFFFFFFFF);
        out.putInt(0xFFFFFFFF);
        out.putInt(nsI);
        out.putInt(elI);
    }

    // ========== 找插入点 ==========
    private static int findInsertion(byte[] axml, PoolInfo pool) {
        int nsIdx = pool.indexOf("http://schemas.android.com/apk/res/android");
        int elemIdx = pool.indexOf("uses-permission");
        int appIdx = pool.indexOf("application");

        int pos = 8 + pool.chunkSize;
        int lastPermEnd = -1, appStart = -1;

        while (pos < axml.length) {
            int type = le16(axml, pos);
            int size = le32(axml, pos + 4);
            if (size < 8 || pos + size > axml.length) break;

            if (type == 0x0102) { // START_ELEMENT
                int ns = le32(axml, pos + 16);
                int name = le32(axml, pos + 20);
                if (ns == nsIdx && name == elemIdx) {
                    lastPermEnd = pos + size;
                } else if (name == appIdx) {
                    appStart = pos;
                    break;
                }
            }
            pos += size;
        }
        if (lastPermEnd > 0) return lastPermEnd;
        if (appStart > 0) return appStart;
        return axml.length - 24;
    }

    // ========== String Pool 解析 ==========
    private static class PoolInfo {
        int chunkStart, chunkSize, strCount, styleCount, flags, stringsStart, stylesStart;
        List<String> strings = new ArrayList<>();
        int[] rawOffsets, rawByteLengths;

        int indexOf(String s) { return strings.indexOf(s); }
    }

    private static PoolInfo parsePool(byte[] axml) {
        PoolInfo p = new PoolInfo();
        int pos = getPoolChunkStart(axml);
        p.chunkStart = pos;
        p.chunkSize   = le32(axml, pos + 4);
        p.strCount    = le32(axml, pos + 8);
        p.styleCount  = le32(axml, pos + 12);
        p.flags       = le32(axml, pos + 16);
        p.stringsStart = le32(axml, pos + 20);
        p.stylesStart  = le32(axml, pos + 24);
        boolean isUtf8 = (p.flags & 0x100) != 0;

        int[] offsets = new int[p.strCount];
        int[] byteLengths = new int[p.strCount];
        p.rawOffsets = offsets;
        p.rawByteLengths = byteLengths;

        for (int i = 0; i < p.strCount; i++) {
            offsets[i] = le32(axml, pos + 28 + i * 4);
        }
        for (int i = 0; i < p.strCount; i++) {
            int strAbs = pos + p.stringsStart + offsets[i];
            byteLengths[i] = byteLen(axml, strAbs, isUtf8);
            p.strings.add(decodeStr(axml, strAbs, isUtf8));
        }
        return p;
    }

    private static int getPoolChunkStart(byte[] axml) {
        for (int i = 8; i < axml.length - 8; i++) {
            if (le16(axml, i) == 0x0001) return i;
        }
        throw new RuntimeException("string pool not found");
    }

    private static String decodeStr(byte[] axml, int strAbs, boolean isUtf8) {
        if (isUtf8) {
            int n = axml[strAbs] & 0xFF;
            int start;
            if ((n & 0x80) != 0) {
                n = ((n & 0x7F) << 8) | (axml[strAbs + 1] & 0xFF);
                start = strAbs + 2;
            } else {
                start = strAbs + 1;
            }
            return new String(axml, start, n, StandardCharsets.UTF_8);
        } else {
            int n = ((axml[strAbs + 1] & 0xFF) << 8) | (axml[strAbs] & 0xFF);
            return new String(axml, strAbs + 2, n * 2, StandardCharsets.UTF_16LE);
        }
    }

    private static int byteLen(byte[] axml, int strAbs, boolean isUtf8) {
        if (isUtf8) {
            int n = axml[strAbs] & 0xFF;
            int prefix = ((n & 0x80) != 0) ? 2 : 1;
            if ((n & 0x80) != 0) n = ((n & 0x7F) << 8) | (axml[strAbs + 1] & 0xFF);
            return prefix + n + 1;
        } else {
            int n = ((axml[strAbs + 1] & 0xFF) << 8) | (axml[strAbs] & 0xFF);
            return 2 + n * 2 + 2;
        }
    }

    private static byte[] encodeUtf8(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        int n = b.length;
        byte[] out = new byte[n + (n < 0x80 ? 2 : 3)];
        if (n < 0x80) {
            out[0] = (byte) n;
            System.arraycopy(b, 0, out, 1, n);
            out[n + 1] = 0;
        } else {
            out[0] = (byte) (((n >> 8) & 0x7F) | 0x80);
            out[1] = (byte) (n & 0xFF);
            System.arraycopy(b, 0, out, 2, n);
            out[n + 2] = 0;
        }
        return out;
    }

    private static byte[] encodeUtf16(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_16LE);
        int n = b.length / 2;
        byte[] out = new byte[2 + b.length + 2];
        out[0] = (byte) (n & 0xFF);
        out[1] = (byte) ((n >> 8) & 0xFF);
        System.arraycopy(b, 0, out, 2, b.length);
        return out;
    }

    // ========== little-endian helpers ==========
    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /** 仅用于 self-check (derived from le32) */
    private static void setLe32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }
}