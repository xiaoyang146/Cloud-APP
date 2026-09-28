package com.cloud.dex;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class RequestSigner {

    private static final String TAG = "RequestSigner";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_HEADER = "X-Signature";
    private static final String TIMESTAMP_HEADER = "X-Timestamp";

    private RequestSigner() {}

    /**
     * Generate an HMAC-SHA256 signature from the request parameters.
     * Sorted lexicographically to ensure deterministic output.
     */
    public static String sign(Map<String, String> params, long timestamp, String secretKey) {
        try {
            TreeMap<String, String> sorted = new TreeMap<>(params);
            StringBuilder data = new StringBuilder();
            data.append(timestamp);
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                data.append(entry.getKey()).append('=').append(entry.getValue()).append('&');
            }
            if (data.length() > 0) {
                data.setLength(data.length() - 1);
            }

            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(hmacBytes, Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            Log.e(TAG, "签名生成失败", e);
            return null;
        }
    }

    public static String sign(Map<String, String> params, String secretKey) {
        return sign(params, System.currentTimeMillis() / 1000, secretKey);
    }

    /**
     * Build a headers map with signature and timestamp.
     */
    public static Map<String, String> signedHeaders(Map<String, String> params, String secretKey) {
        long now = System.currentTimeMillis() / 1000;
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put(TIMESTAMP_HEADER, String.valueOf(now));
        String signature = sign(params, now, secretKey);
        if (signature != null) {
            headers.put(SIGNATURE_HEADER, signature);
        }
        return headers;
    }
}
