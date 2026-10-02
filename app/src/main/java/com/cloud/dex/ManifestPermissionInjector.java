package com.cloud.dex;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/**
 * 向二进制 AndroidManifest.xml (AXML) 中注入 uses-permission 声明。
 *
 * 参考实现：/workspace/xiao_ui/inject_axml_v2.py（已通过真实 APK 验证）
 * 支持 UTF-8 和 UTF-16 两种 StringPool 编码。
 * 零外部依赖，兼容 Android 5.0 ~ 15。
 */
public class ManifestPermissionInjector {

    private static final int AXML_MAGIC    = 0x00080003;
    private static final int STR_POOL      = 0x001C0001;
    private static final int START_TAG     = 0x00100102;
    private static final int END_TAG       = 0x00100103;
    private static final int TYPE_STRING   = 0x03;
    private static final int UTF8_FLAG     = 0x100;

    /**
     * 返回修改后的 AndroidManifest.xml 字节。
     * 若无需修改（权限已存在）则返回 null。
     */
    public static byte[] inject(byte[] raw, String... permissions) throws IOException {
        if (raw == null || raw.length < 8) return null;
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getInt(0) != AXML_MAGIC) throw new IOException("Not an AXML file");

        int spSize     = buf.getInt(12);
        int strCount   = buf.getInt(16);
        int styleCount = buf.getInt(20);
        int flags      = buf.getInt(24);
        int strsStart  = buf.getInt(28);    // 字符串数据在 chunk 内的偏移
        int stysStart  = buf.getInt(32);

        boolean utf8 = (flags & UTF8_FLAG) != 0;

        // --- 读取原始字符串偏移 ---
        int[] oldOffsets = new int[strCount];
        for (int i = 0; i < strCount; i++) oldOffsets[i] = buf.getInt(36 + i * 4);

        // --- 读取原始字符串 ---
        String[] strings = new String[strCount];
        for (int i = 0; i < strCount; i++) {
            strings[i] = readString(buf, 8 + strsStart + oldOffsets[i], utf8);
        }

        // --- 收集待添加的字符串（只加不存在的）---
        List<String> allStrings = new ArrayList<>(Arrays.asList(strings));
        List<String> newStrs = new ArrayList<>();
        for (String p : permissions) {
            if (!allStrings.contains(p)) { allStrings.add(p); newStrs.add(p); }
        }
        String NS = "http://schemas.android.com/apk/res/android";
        String TAG = "uses-permission";
        String ATTR = "name";
        if (!allStrings.contains(NS))    { allStrings.add(NS);    newStrs.add(NS); }
        if (!allStrings.contains(TAG))   { allStrings.add(TAG);   newStrs.add(TAG); }
        if (!allStrings.contains(ATTR))  { allStrings.add(ATTR);  newStrs.add(ATTR); }
        if (newStrs.isEmpty()) return null;   // 无需修改

        // --- 重建 String Pool ---
        int newStrCount = allStrings.size();
        int oldDataSize = spSize - strsStart;  // 原字符串数据区大小
        byte[] newData = encodeNewStrings(newStrs, utf8, oldDataSize);
        int newSpSize = spSize + newData.length;
        // 样式偏移更新
        int newStysStart = stysStart;
        if (styleCount > 0) newStysStart += newData.length;

        ByteArrayOutputStream sp = new ByteArrayOutputStream();
        writeInt(sp, STR_POOL);
        writeInt(sp, newSpSize);
        writeInt(sp, newStrCount);
        writeInt(sp, styleCount);
        writeInt(sp, flags);
        writeInt(sp, strsStart);
        writeInt(sp, newStysStart);
        // string offsets (旧 + 新)
        for (int o : oldOffsets) writeInt(sp, o);
        for (byte[] nb : encodeOffsets(newStrs, oldDataSize, utf8)) sp.write(nb);
        // style offsets（原样复制）
        for (int i = 0; i < styleCount; i++)
            writeInt(sp, buf.getInt(36 + strCount * 4 + i * 4));
        // 原始字符串数据
        sp.write(raw, 8 + strsStart, oldDataSize);
        // 新字符串数据
        sp.write(newData);
        byte[] newSP = sp.toByteArray();

        // --- 找插入点：<application> 之前 ---
        int insertAt = raw.length;
        int cursor = 8 + spSize;
        while (cursor + 8 <= raw.length) {
            int t = buf.getInt(cursor); int s = buf.getInt(cursor + 4);
            if (s <= 0 || cursor + s > raw.length) break;
            if (t == START_TAG) {
                int nIdx = buf.getInt(cursor + 20);
                if (nIdx < strings.length && "application".equals(strings[nIdx])) {
                    insertAt = cursor; break;
                }
            }
            cursor += s;
        }

        // --- 构造 uses-permission 标签 ---
        int nsIdx   = allStrings.indexOf(NS);
        int tagIdx  = allStrings.indexOf(TAG);
        int attrIdx = allStrings.indexOf(ATTR);

        ByteArrayOutputStream tags = new ByteArrayOutputStream();
        for (String perm : permissions) {
            int permIdx = allStrings.indexOf(perm);
            tags.write(makeStartTag(nsIdx, tagIdx, attrIdx, permIdx));
            tags.write(makeEndTag(nsIdx, tagIdx));
        }
        byte[] tagBytes = tags.toByteArray();

        // --- 组装最终结果 ---
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(raw, 0, 8);                              // file header
        out.write(newSP);                                   // 新 string pool
        out.write(raw, 8 + spSize, insertAt - (8 + spSize));// pool 到插入点
        out.write(tagBytes);                                // uses-permission
        out.write(raw, insertAt, raw.length - insertAt);    // 剩余
        byte[] result = out.toByteArray();
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.length);
        return result;
    }

    // ---- helpers ----

    private static String readString(ByteBuffer buf, int absOff, boolean utf8) {
        if (utf8) {
            int pos = absOff;
            int len = buf.get(pos++) & 0xff;
            if ((len & 0x80) != 0) len = ((len & 0x7f) << 8) | (buf.get(pos++) & 0xff);
            byte[] b = new byte[len];
            for (int i = 0; i < len; i++) b[i] = buf.get(pos + i);
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } else {
            int len = buf.getShort(absOff) & 0xffff;
            int pos = absOff + 2;
            char[] c = new char[len];
            for (int i = 0; i < len; i++) c[i] = (char)(buf.getShort(pos + i * 2) & 0xffff);
            return new String(c);
        }
    }

    private static byte[] encodeNewStrings(List<String> strs, boolean utf8, int baseOff) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (String s : strs) {
            if (utf8) {
                byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (b.length < 128) bos.write(b.length);
                else { bos.write((b.length >> 8) | 0x80); bos.write(b.length & 0xff); }
                try { bos.write(b); bos.write(0); } catch (IOException e) {}
            } else {
                try {
                    writeShort(bos, s.length());
                    byte[] c = s.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
                    bos.write(c); writeShort(bos, 0);
                } catch (IOException e) {}
            }
        }
        while (bos.size() % 4 != 0) bos.write(0);
        return bos.toByteArray();
    }

    private static byte[][] encodeOffsets(List<String> strs, int baseOff, boolean utf8) {
        byte[][] out = new byte[strs.size()][];
        int cum = baseOff;
        for (int i = 0; i < strs.size(); i++) {
            out[i] = new byte[4];
            ByteBuffer.wrap(out[i]).order(ByteOrder.LITTLE_ENDIAN).putInt(cum);
            if (utf8) {
                byte[] b = strs.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                cum += (b.length < 128 ? 1 : 2) + b.length + 1;  // len + data + null
            } else {
                cum += 2 + strs.get(i).length() * 2 + 2;  // len + chars + null
            }
        }
        // pad to 4
        while (cum % 4 != 0) cum++;
        return out;
    }

    private static byte[] makeStartTag(int nsIdx, int tagIdx, int attrIdx, int valueIdx) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try {
            writeInt(b, START_TAG);     // type
            writeInt(b, 0);             // size (patch later)
            writeInt(b, 0);             // line
            writeInt(b, 0xFFFFFFFF);    // comment
            writeInt(b, nsIdx);         // ns
            writeInt(b, tagIdx);        // name
            writeShort(b, 20);          // attributeStart (from attrExt base)
            writeShort(b, 20);          // attributeSize
            writeShort(b, 1);           // attributeCount
            writeShort(b, 0);           // idIndex
            writeShort(b, 0);           // classIndex
            writeShort(b, 0);           // styleIndex
            // attribute body: android:name="PERM"
            writeInt(b, nsIdx);         // attr ns
            writeInt(b, attrIdx);       // attr name
            writeInt(b, valueIdx);      // rawValue
            writeShort(b, 8);           // typedValue.size
            b.write(0);                 // typedValue.res0
            b.write(TYPE_STRING);       // typedValue.dataType
            writeInt(b, valueIdx);      // typedValue.data
        } catch (IOException e) { throw new RuntimeException(e); }
        byte[] arr = b.toByteArray();
        ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN).putInt(4, arr.length);
        return arr;
    }

    private static byte[] makeEndTag(int nsIdx, int tagIdx) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try {
            writeInt(b, END_TAG);
            writeInt(b, 24);
            writeInt(b, 0);
            writeInt(b, 0xFFFFFFFF);
            writeInt(b, nsIdx);
            writeInt(b, tagIdx);
        } catch (IOException e) { throw new RuntimeException(e); }
        return b.toByteArray();
    }

    private static void writeInt(OutputStream os, int v) throws IOException {
        os.write(v & 0xff); os.write((v >> 8) & 0xff);
        os.write((v >> 16) & 0xff); os.write((v >> 24) & 0xff);
    }
    private static void writeShort(OutputStream os, int v) throws IOException {
        os.write(v & 0xff); os.write((v >> 8) & 0xff);
    }
}