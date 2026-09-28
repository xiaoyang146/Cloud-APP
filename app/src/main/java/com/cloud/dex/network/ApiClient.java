package com.cloud.dex.network;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.cloud.dex.LoginActivity;
import com.cloud.dex.SharedPreferencesManager;
import com.cloud.dex.TokenManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ApiClient {
    private static final String TAG = "ApiClient";
    private static Context appContext; // 全局 Application Context

    /**
     * 在 Application 或启动 Activity 中调用一次，传入 ApplicationContext
     */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public interface ApiCallback {
        void onSuccess(JSONObject response);
        void onError(String error);
    }

    public static void post(String url, JSONObject params, ApiCallback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL apiUrl = new URL(url);
                connection = (HttpURLConnection) apiUrl.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Accept", "application/json");
                // 添加 Token 认证头
                TokenManager tokenManager = TokenManager.getInstance(appContext);
                String token = tokenManager.getValidAccessToken();
                if (token != null) {
                    connection.setRequestProperty("Authorization", "Bearer " + token);
                }
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setDoOutput(true);

                String jsonInputString = params.toString();
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == 401) {
                    // Token 过期,尝试刷新
                    String newToken = tokenManager.refreshAccessTokenSync();
                    if (newToken != null) {
                        // 重试请求
                        connection.disconnect();
                        connection = (HttpURLConnection) apiUrl.openConnection();
                        connection.setRequestMethod("POST");
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        connection.setRequestProperty("Accept", "application/json");
                        connection.setRequestProperty("Authorization", "Bearer " + newToken);
                        connection.setConnectTimeout(10000);
                        connection.setReadTimeout(10000);
                        connection.setDoOutput(true);
                        try (OutputStream os = connection.getOutputStream()) {
                            byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                            os.write(input, 0, input.length);
                        }
                        responseCode = connection.getResponseCode();
                    }
                }
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    JSONObject jsonResponse = new JSONObject(response.toString());
                    // 检查是否为冻结错误
                    if (checkFrozenError(jsonResponse)) return;
                    callback.onSuccess(jsonResponse);
                } else {
                    String error = "HTTP error: " + responseCode;
                    // 某些情况下错误也在流中
                    checkFrozenError(connection);
                    callback.onError(error);
                }
            } catch (Exception e) {
                Log.e(TAG, "API request failed", e);
                callback.onError(e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    public static void get(String url, ApiCallback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL apiUrl = new URL(url);
                connection = (HttpURLConnection) apiUrl.openConnection();
                connection.setRequestMethod("GET");
                // 添加 Token 认证头
                TokenManager tokenManagerGet = TokenManager.getInstance(appContext);
                String tokenGet = tokenManagerGet.getValidAccessToken();
                if (tokenGet != null) {
                    connection.setRequestProperty("Authorization", "Bearer " + tokenGet);
                }
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);

                int responseCode = connection.getResponseCode();
                if (responseCode == 401) {
                    // Token 过期,尝试刷新
                    String newToken = tokenManagerGet.refreshAccessTokenSync();
                    if (newToken != null) {
                        // 重试请求
                        connection.disconnect();
                        connection = (HttpURLConnection) apiUrl.openConnection();
                        connection.setRequestMethod("GET");
                        connection.setRequestProperty("Authorization", "Bearer " + newToken);
                        connection.setConnectTimeout(8000);
                        connection.setReadTimeout(8000);
                        responseCode = connection.getResponseCode();
                    }
                }
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    JSONObject jsonResponse = new JSONObject(response.toString());
                    if (checkFrozenError(jsonResponse)) return;
                    callback.onSuccess(jsonResponse);
                } else {
                    checkFrozenError(connection);
                    callback.onError("HTTP error: " + responseCode);
                }
            } catch (Exception e) {
                Log.e(TAG, "API request failed", e);
                callback.onError(e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    /**
     * 检查 JSON 响应中是否包含冻结信息，若是则自动跳转登录页
     */
    private static boolean checkFrozenError(JSONObject jsonResponse) {
        try {
            if (jsonResponse.has("success") && !jsonResponse.getBoolean("success")) {
                String message = jsonResponse.optString("message", "");
                if (message.contains("冻结")) {
                    handleFrozenAccount(message);
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "parse frozen error fail", e);
        }
        return false;
    }

    /**
     * 在连接错误时，尝试从错误流中读取响应，检查是否有冻结信息
     */
    private static void checkFrozenError(HttpURLConnection connection) {
        try {
            if (connection.getErrorStream() != null) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                String body = sb.toString();
                if (body.contains("冻结")) {
                    handleFrozenAccount("账号已被冻结，请联系管理员");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "check frozen error fail", e);
        }
    }

    private static void handleFrozenAccount(String message) {
        if (appContext == null) return;
        // 清除登录信息
        SharedPreferencesManager spManager = new SharedPreferencesManager(appContext);
        spManager.clearLoginInfo();

        // 在主线程显示 Toast 并跳转
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show();
            Intent intent = new Intent(appContext, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        });
    }
}