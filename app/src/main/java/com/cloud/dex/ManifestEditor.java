package com.cloud.dex;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 目标 APK 的 AndroidManifest.xml 权限检测与自动补齐工具。
 *
 * 直接操作二进制 AXML，保留原始 String Pool 编码不动，
 * 仅在末尾追加新字符串，并插入 uses-permission 元素。
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

    // ── AXML chunk type ──
    private static final int AXML_MAGIC        = 0x00080003;
    private static final int CHUNK_STRING_POOL = 0x001C0001;
    private static final int CHUNK_START_ELEM  = 0x00100102;
    private static final int CHUNK_END_ELEM    = 0x00100103;

    // ── 公开 API ──

    /** 解析已声明的权限 */
    public static Set<String> getExistingPermissions(byte[] axml) {
        Set<String> perms = new HashSet<>();
        PoolInfo pool = parsePool(axml);
        if (pool == null) return perms;

        int pos = 8 + pool.chunkSize;
        int nsIdx = pool.strings.indexOf("http://schemas.android.com/apk/res/android");
        int elemIdx = pool.strings.indexOf("uses-permission");

        while (pos + 8 <= axml.length) {
            int type = getIntLE(axml, pos);
            int size = getIntLE(axml, pos + 4);
            if (size < 8) break;
            if (type == CHUNK_START_ELEM) {
                int ns = getIntLE(axml, pos + 16);
                int name = getIntLE(axml, pos + 20);
                int attrCount = ((axml[pos + 28] & 0xFF) | ((axml[pos + 29] & 0xFF) << 8)) & 0xFFFF;
                int attrStart = ((axml[pos + 26] & 0xFF) | ((axml[pos + 27] & 0xFF) << 8)) & 0xFFFF;
                if (ns == nsIdx && name == elemIdx && attrCount >= 1) {
                    int val = getIntLE(axml, pos + attrStart + 8); // attribute[0].rawValue
                    if (val >= 0 && val < pool.strings.size()) {
                        perms.add(pool.strings.get(val));
                    }
                }
            }
            pos += size;
        }
        return perms;
    }

    /** 补齐缺失权限。无需修改返回原数组，否则返回新数组。 */
    public static byte[] ensurePermissions(byte[] axml) {
        Set<String> existing = getExistingPermissions(axml);
        Set<String> missing = new HashSet<>(Arrays.asList(REQUIRED_PERMISSIONS));
        missing.removeAll(existing);
        if (missing.isEmpty()) return axml;
        Log.i(TAG, "缺失权限: " + missing + "，自动补齐");
        return addPermissions(axml, missing);
    }

    // ── String Pool ──

    static class PoolInfo {
        int chunkStart;
        int chunkSize;
        int strCount;
        int flags;
        int stringsStart;
        int stylesStart;
        List<String> strings;
        int[] rawOffsets;
        int[] rawByteLengths;
    }

    private static PoolInfo parsePool(byte[] axml) {
        if (axml.length < 36) return null;
        if (getIntLE(axml, 0) != AXML_MAGIC) return null;
        int pos = 8;
        if (getIntLE(axml, pos) != CHUNK_STRING_POOL) return null;

        PoolInfo p = new PoolInfo();
        p.chunkStart   = pos;
        p.chunkSize    = getIntLE(axml, pos + 4);
        p.strCount     = getIntLE(axml, pos + 8);
        int styleCount = getIntLE(axml, pos + 12);
        p.flags        = getIntLE(axml, pos + 16);
        p.stringsStart = getIntLE(axml, pos + 20);
        p.stylesStart  = getIntLE(axml, pos + 24);

        boolean isUtf8 = (p.flags & 0x100) != 0;
        p.strings = new ArrayList<>(p.strCount);
        p.rawOffsets = new int[p.strCount];
        p.rawByteLengths = new int[p.strCount];

        int offBase = pos + 28;
        int dataBase = pos + p.stringsStart;

        for (int i = 0; i < p.strCount; i++) {
            int off = getIntLE(axml, offBase + i * 4);
            p.rawOffsets[i] = off;
            int dp = dataBase + off;

            if (isUtf8) {
                int b0 = axml[dp] & 0xFF;
                int skip, byteLen;
                if ((b0 & 0x80) != 0) {
                    byteLen = ((b0 & 0x7F) << 8) | (axml[dp + 1] & 0xFF);
                    skip = 2;
                } else {
                    byteLen = b0;
                    skip = 1;
                }
                p.strings.add(new String(axml, dp + skip, byteLen, StandardCharsets.UTF_8));
                p.rawByteLengths[i] = skip + byteLen + 1;
            } else {
                int cl = ((axml[dp + 1] & 0xFF) << 8) | (axml[dp] & 0xFF);
                p.strings.add(new String(axml, dp + 2, cl * 2, StandardCharsets.UTF_16LE));
                p.rawByteLengths[i] = 2 + cl * 2 + 2;
            }
        }
        return p;
    }

    // ── 核心：追加权限 ──

    // START_ELEMENT(36) + 1 attr(20) = 56, END_ELEMENT = 24
    private static final int START_ELEM_SIZE = 56;
    private static final int END_ELEM_SIZE   = 24;
    private static final int ATTR_STRING     = 0x03000008;

    private static byte[] addPermissions(byte[] axml, Set<String> permissions) {
        PoolInfo pool = parsePool(axml);
        if (pool == null) throw new RuntimeException("无法解析 String Pool");

        // 1. 收集需要加入 pool 的新字符串
        List<String> newStrs = new ArrayList<>();
        for (String p : permissions) {
            if (!pool.strings.contains(p)) newStrs.add(p);
        }
        ensureInPool(pool, newStrs, "http://schemas.android.com/apk/res/android");
        ensureInPool(pool, newStrs, "uses-permission");
        ensureInPool(pool, newStrs, "name");

        // 2. 编码新字符串
        boolean isUtf8 = (pool.flags & 0x100) != 0;
        List<byte[]> newEnc = new ArrayList<>();
        int newBytes = 0;
        for (String s : newStrs) {
            byte[] b = isUtf8 ? encUtf8(s) : encUtf16(s);
            newEnc.add(b);
            newBytes += b.length;
        }

        int newStrCount = pool.strCount + newStrs.size();

        // ★ 关键修复：从实际写入字节计算 pool 大小，而非增量计算
        int oldDataSize = pool.chunkSize - pool.stringsStart;  // 原始 string data 区
        int newPoolSize = 28 + newStrCount * 4 + oldDataSize + newBytes;
        int poolDelta   = newPoolSize - pool.chunkSize;

        // 3. 找插入点
        int ins = findInsertionOffset(axml, pool);
        int permsBytes = permissions.size() * (START_ELEM_SIZE + END_ELEM_SIZE);
        int totalSize  = axml.length + poolDelta + permsBytes;

        ByteBuffer out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);

        // ── 文件头 ──
        out.putInt(AXML_MAGIC);
        out.putInt(totalSize);

        // ── 新 String Pool ──
        out.putInt(CHUNK_STRING_POOL);
        out.putInt(newPoolSize);
        out.putInt(newStrCount);
        out.putInt(0);                     // styleCount = 0
        out.putInt(pool.flags);

        int stringsStart = 28 + newStrCount * 4;
        out.putInt(stringsStart);          // 新 stringsStart
        out.putInt(stringsStart);          // stylesStart = stringsStart

        // 偏移表：原始字符串保持不动，新字符串追加
        for (int i = 0; i < pool.strCount; i++) {
            out.putInt(pool.rawOffsets[i]);
        }
        int cursor = 0;
        for (int i = 0; i < pool.strCount; i++) cursor += pool.rawByteLengths[i];
        for (byte[] enc : newEnc) {
            out.putInt(cursor);
            cursor += enc.length;
        }

        // 字符串数据：原始→追加
        out.put(axml, pool.chunkStart + pool.stringsStart, oldDataSize);
        for (byte[] enc : newEnc) out.put(enc);

        // ── pool 后到插入点之间的内容 ──
        int afterPool = pool.chunkStart + pool.chunkSize;
        int midLen = ins - afterPool;
        if (midLen > 0) out.put(axml, afterPool, midLen);

        // ── 新 uses-permission 元素 ──
        int nsIdx   = findIndex(pool, newStrs, "http://schemas.android.com/apk/res/android");
        int elemIdx = findIndex(pool, newStrs, "uses-permission");
        int attrIdx = findIndex(pool, newStrs, "name");
        for (String perm : permissions) {
            int pIdx = findIndex(pool, newStrs, perm);
            writePerm(out, nsIdx, elemIdx, attrIdx, pIdx);
        }

        // ── 插入点后剩余内容 ──
        int tail = axml.length - ins;
        if (tail > 0) out.put(axml, ins, tail);

        Log.i(TAG, "补齐: +" + permissions.size() + "权限, " + axml.length + "→" + totalSize);
        return out.array();
    }

    // ── 插入点 ──

    private static int findInsertionOffset(byte[] axml, PoolInfo pool) {
        int pos = 8 + pool.chunkSize;
        int nsI = pool.strings.indexOf("http://schemas.android.com/apk/res/android");
        int permI = pool.strings.indexOf("uses-permission");
        int appI = pool.strings.indexOf("application");

        int lastEnd = -1;
        int appStart = -1;
        while (pos + 8 <= axml.length) {
            int type = getIntLE(axml, pos);
            int size = getIntLE(axml, pos + 4);
            if (size < 8) break;
            if (type == CHUNK_START_ELEM) {
                int ns = getIntLE(axml, pos + 16);
                int nm = getIntLE(axml, pos + 20);
                if (ns == nsI && nm == permI) {
                    // 跳过 START + END
                    int endPos = pos + size;
                    int endType = getIntLE(axml, endPos);
                    int endSize = getIntLE(axml, endPos + 4);
                    lastEnd = (endType == CHUNK_END_ELEM) ? endPos + endSize : pos + size;
                } else if (nm == appI) {
                    appStart = pos;
                    break;
                }
            }
            pos += size;
        }
        if (lastEnd > 0) return lastEnd;
        if (appStart > 0) return appStart;
        return axml.length - 24;
    }

    // ── 元素写入 ──

    private static void writePerm(ByteBuffer out, int ns, int el, int at, int val) {
        // START_ELEMENT (header = 36, attr = 20, total = 56)
        out.putInt(CHUNK_START_ELEM);
        out.putInt(START_ELEM_SIZE);
        out.putInt(0);            // line
        out.putInt(0xFFFFFFFF);   // comment
        out.putInt(ns); out.putInt(el);
        out.putShort((short) 20);     // attributeSize
        out.putShort((short) 36);     // attributeStart (offset from chunk start to attr data)
        out.putShort((short) 1);      // attributeCount
        out.putShort((short) 0xFFFF); // idIndex
        out.putShort((short) 0xFFFF); // classIndex
        out.putShort((short) 0xFFFF); // styleIndex
        // attribute[0]
        out.putInt(ns); out.putInt(at);
        out.putInt(val);          // rawValue
        out.putInt(ATTR_STRING);
        out.putInt(val);          // data
        // END_ELEMENT (24 bytes)
        out.putInt(CHUNK_END_ELEM);
        out.putInt(END_ELEM_SIZE);
        out.putInt(0); out.putInt(0xFFFFFFFF);
        out.putInt(ns); out.putInt(el);
    }

    // ── 字符串编码 ──

    private static byte[] encUtf8(String s) {
        byte[] d = s.getBytes(StandardCharsets.UTF_8);
        int len = d.length;
        if (len <= 127) {
            byte[] r = new byte[len + 2];
            r[0] = (byte) len;
            System.arraycopy(d, 0, r, 1, len);
            r[len + 1] = 0;
            return r;
        }
        byte[] r = new byte[len + 3];
        r[0] = (byte) (((len >> 8) & 0x7F) | 0x80);
        r[1] = (byte) (len & 0xFF);
        System.arraycopy(d, 0, r, 2, len);
        r[len + 2] = 0;
        return r;
    }

    private static byte[] encUtf16(String s) {
        byte[] d = s.getBytes(StandardCharsets.UTF_16LE);
        int cl = d.length / 2;
        byte[] r = new byte[d.length + 4];
        r[0] = (byte) (cl & 0xFF);
        r[1] = (byte) ((cl >> 8) & 0xFF);
        System.arraycopy(d, 0, r, 2, d.length);
        r[r.length - 2] = 0;
        r[r.length - 1] = 0;
        return r;
    }

    // ── 小工具 ──

    private static int getIntLE(byte[] d, int off) {
        return ByteBuffer.wrap(d, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void ensureInPool(PoolInfo pool, List<String> ns, String s) {
        if (!pool.strings.contains(s) && !ns.contains(s)) ns.add(0, s);
    }

    private static int findIndex(PoolInfo pool, List<String> ns, String t) {
        int i = pool.strings.indexOf(t);
        if (i >= 0) return i;
        i = ns.indexOf(t);
        if (i >= 0) return pool.strCount + i;
        throw new RuntimeException("missing: " + t);
    }
}