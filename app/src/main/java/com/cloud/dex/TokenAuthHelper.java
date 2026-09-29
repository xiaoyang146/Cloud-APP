package com.cloud.dex;

import android.content.Context;
import android.util.Log;

import java.util.Map;

/**
 * Token 认证辅助类
 * 为所有受保护的 API 请求自动注入 access_token
 * 注意: token 统一由 TokenManager 管理
 */
public class TokenAuthHelper {
    private static final String TAG = "TokenAuthHelper";

    private TokenAuthHelper() {}

    /**
     * 从 TokenManager 读取 access_token(统一入口)
     */
    private static String getToken(Context context) {
        return TokenManager.getInstance(context).getAccessToken();
    }

    public static String appendTokenToUrl(String url, Context context) {
        String token = getToken(context);
        if (token == null || token.isEmpty()) {
            Log.w(TAG, "Token 为空,跳过 token 注入");
            return url;
        }
        try {
            String separator = url.contains("?") ? "&" : "?";
            return url + separator + "access_token=" + java.net.URLEncoder.encode(token, "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "编码 token 失败", e);
            return url;
        }
    }

    public static void addTokenToParams(Map<String, String> params, Context context) {
        String token = getToken(context);
        if (token != null && !token.isEmpty()) {
            params.put("access_token", token);
        } else {
            Log.w(TAG, "Token 为空,跳过 token 注入");
        }
    }
}
