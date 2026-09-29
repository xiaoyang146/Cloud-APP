package com.cloud.dex;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TokenManager {
    private static final String TAG = "TokenManager";
    private static final String PREF_NAME = "token_prefs";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_ACCESS_EXPIRES = "access_expires_at";
    private static final String KEY_REFRESH_EXPIRES = "refresh_expires_at";

    private static TokenManager instance;
    private final Context context;
    private final android.content.SharedPreferences prefs;
    private boolean isRefreshing = false;
    private final Object refreshLock = new Object();

    private TokenManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized TokenManager getInstance(Context context) {
        if (instance == null) {
            instance = new TokenManager(context);
        }
        return instance;
    }

    /**
     * 保存登录返回的 token 对
     */
    public void saveTokens(String accessToken, String refreshToken, String accessExpires, String refreshExpires) {
        prefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putString(KEY_ACCESS_EXPIRES, accessExpires)
                .putString(KEY_REFRESH_EXPIRES, refreshExpires)
                .apply();
        Log.d(TAG, "Token 已保存");
    }

    public String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, null);
    }

    public String getRefreshToken() {
        return prefs.getString(KEY_REFRESH_TOKEN, null);
    }

    public boolean hasTokens() {
        return getAccessToken() != null && getRefreshToken() != null;
    }

    /**
     * 检查 access_token 是否已过期
     */
    public boolean isAccessTokenExpired() {
        String expires = prefs.getString(KEY_ACCESS_EXPIRES, null);
        if (expires == null) return true;
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
            long expireTime = sdf.parse(expires).getTime();
            return System.currentTimeMillis() > expireTime;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 检查 refresh_token 是否已过期
     */
    public boolean isRefreshTokenExpired() {
        String expires = prefs.getString(KEY_REFRESH_EXPIRES, null);
        if (expires == null) return true;
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
            long expireTime = sdf.parse(expires).getTime();
            return System.currentTimeMillis() > expireTime;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 同步刷新 token(阻塞调用,在后台线程执行)
     * @return 新的 access_token,失败返回 null
     */
    public String refreshAccessTokenSync() {
        synchronized (refreshLock) {
            if (isRefreshing) {
                try {
                    refreshLock.wait(10000);
                } catch (InterruptedException e) {
                    return null;
                }
                return getAccessToken();
            }

            String refreshToken = getRefreshToken();
            if (refreshToken == null) return null;

            isRefreshing = true;
            try {
                String url = AppConfig.TOKEN_REFRESH_URL;
                String params = "refresh_token=" + java.net.URLEncoder.encode(refreshToken, "UTF-8");

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(params.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    java.io.InputStream is = conn.getInputStream();
                    java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                    String body = s.hasNext() ? s.next() : "";
                    is.close();

                    JSONObject json = new JSONObject(body);
                    if (json.getBoolean("success")) {
                        String newAccess = json.getString("access_token");
                        String newRefresh = json.getString("refresh_token");
                        String newAccessExp = json.getString("access_expires_at");
                        String newRefreshExp = json.getString("refresh_expires_at");
                        saveTokens(newAccess, newRefresh, newAccessExp, newRefreshExp);
                        Log.d(TAG, "Token 刷新成功");
                        return newAccess;
                    }
                }
                Log.e(TAG, "Token 刷新失败,HTTP " + code);
                return null;
            } catch (Exception e) {
                Log.e(TAG, "Token 刷新异常", e);
                return null;
            } finally {
                isRefreshing = false;
                refreshLock.notifyAll();
            }
        }
    }

    /**
     * 获取有效的 access_token(自动刷新)
     * 在后台线程调用
     */
    public String getValidAccessToken() {
        if (!hasTokens()) return null;
        if (isAccessTokenExpired()) {
            if (isRefreshTokenExpired()) {
                clearTokens();
                return null;
            }
            return refreshAccessTokenSync();
        }
        return getAccessToken();
    }

    public void clearTokens() {
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_ACCESS_EXPIRES)
                .remove(KEY_REFRESH_EXPIRES)
                .apply();
        Log.d(TAG, "Token 已清除");
    }
}
