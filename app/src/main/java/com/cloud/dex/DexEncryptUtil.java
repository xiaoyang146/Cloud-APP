package com.cloud.dex;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-CBC 加密/解密工具，用于保护 assets 中的 xiao.dex。
 * 密钥由固定口令通过 SHA-256 派生，IV 随机生成并拼在密文头部。
 */
public class DexEncryptUtil {
    private static final String TAG = "DexEncrypt";
    private static final String PASSPHRASE = "DexInjector@2025!Secure#Payload";
    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int IV_LENGTH = 16;

    private static SecretKeySpec deriveKey() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(PASSPHRASE.getBytes("UTF-8"));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("密钥派生失败", e);
        }
    }

    /** 加密：返回 IV + 密文 */
    public static byte[] encrypt(byte[] plaintext) {
        try {
            SecretKeySpec key = deriveKey();
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            byte[] iv = new byte[IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] result = new byte[IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, result, 0, IV_LENGTH);
            System.arraycopy(encrypted, 0, result, IV_LENGTH, encrypted.length);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "加密失败", e);
            return null;
        }
    }

    /** 解密：前 16 字节为 IV，后续为密文 */
    public static byte[] decrypt(byte[] ciphertext) {
        try {
            if (ciphertext.length < IV_LENGTH) return null;
            SecretKeySpec key = deriveKey();
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[ciphertext.length - IV_LENGTH];
            System.arraycopy(ciphertext, 0, iv, 0, IV_LENGTH);
            System.arraycopy(ciphertext, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            Log.e(TAG, "解密失败", e);
            return null;
        }
    }

    // ========== 命令行加密工具 ==========
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法: java DexEncryptUtil <输入文件> <输出文件>");
            return;
        }
        File inFile = new File(args[0]);
        File outFile = new File(args[1]);
        byte[] plaintext = new byte[(int) inFile.length()];
        try (FileInputStream fis = new FileInputStream(inFile)) {
            fis.read(plaintext);
        }
        byte[] encrypted = encrypt(plaintext);
        if (encrypted != null) {
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(encrypted);
            }
            System.out.println("加密完成: " + outFile.getAbsolutePath());
            System.out.println("原始大小: " + plaintext.length + " bytes");
            System.out.println("加密大小: " + encrypted.length + " bytes");
        } else {
            System.err.println("加密失败");
        }
    }
}
