package com.cloud.dex;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class SharedPreferencesManager {
    private static final String TAG = "SharedPreferencesManager";
    private static final String PREF_NAME = "login_prefs";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_LOGIN_TIME = "login_time";
    private static final String KEY_USER_ID = "user_id";

    private SharedPreferences sharedPreferences;
    private Context context;

    public SharedPreferencesManager(Context context) {
        this.context = context;
        try {
            sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            Log.d(TAG, "SharedPreferences初始化成功");
        } catch (Exception e) {
            Log.e(TAG, "SharedPreferences初始化失败", e);
            // 如果初始化失败，尝试使用默认模式
            try {
                sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            } catch (Exception ex) {
                Log.e(TAG, "SharedPreferences重试初始化也失败", ex);
            }
        }
    }

    // 保存登录状态
    public void setLoginStatus(boolean isLoggedIn) {
        try {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean(KEY_IS_LOGGED_IN, isLoggedIn);
            boolean success = editor.commit(); // 使用commit而不是apply，确保立即写入
            Log.d(TAG, "保存登录状态: " + isLoggedIn + ", 结果: " + success);
        } catch (Exception e) {
            Log.e(TAG, "保存登录状态失败", e);
        }
    }

    // 获取登录状态
    public boolean getLoginStatus() {
        try {
            boolean status = sharedPreferences.getBoolean(KEY_IS_LOGGED_IN, false);
            Log.d(TAG, "读取登录状态: " + status);
            return status;
        } catch (Exception e) {
            Log.e(TAG, "读取登录状态失败，返回默认值false", e);
            return false;
        }
    }

    // 保存用户名
    public void setUsername(String username) {
        try {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(KEY_USERNAME, username);
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "保存用户名失败", e);
        }
    }

    // 获取用户名
    public String getUsername() {
        try {
            String username = sharedPreferences.getString(KEY_USERNAME, "");
            Log.d(TAG, "读取用户名: " + (username.isEmpty() ? "空" : "***"));
            return username;
        } catch (Exception e) {
            Log.e(TAG, "读取用户名失败，返回空字符串", e);
            return "";
        }
    }

    // 保存用户ID
    public void setUserId(int userId) {
        try {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt(KEY_USER_ID, userId);
            boolean success = editor.commit();
            Log.d(TAG, "保存用户ID: " + (userId > 0 ? "***" : "invalid") + ", 结果: " + success);

            // 验证保存是否成功
            int savedId = getUserId();
            if (savedId != userId) {
                Log.e(TAG, "用户ID验证失败! 期望: " + userId + ", 实际: " + savedId);
            }
        } catch (Exception e) {
            Log.e(TAG, "保存用户ID失败", e);
        }
    }

    // 获取用户ID
    public int getUserId() {
        try {
            int userId = sharedPreferences.getInt(KEY_USER_ID, -1);
            Log.d(TAG, "读取用户ID: " + (userId > 0 ? "***" : "invalid"));
            return userId;
        } catch (Exception e) {
            Log.e(TAG, "读取用户ID失败，返回-1", e);
            return -1;
        }
    }
    /**
     * 保存密码(使用 Android Keystore AES-GCM 加密)。
     * 安全注意事项:参数 password 为 String 类型(不可变),
     * 方法返回后密码明文仍可能留在内存中直到 GC 回收。
     * 调用方应尽快将传入的密码变量置为 null。
     */
    public void setPassword(String password) {
        try {
            String encrypted = EncryptionUtil.encrypt(password);
            if (encrypted != null) {
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(KEY_PASSWORD, encrypted);
                editor.apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "保存密码失败", e);
        }
    }

    /**
     * 获取密码(自动解密)。
     * 安全注意事项:返回值为 String 类型(不可变),
     * 调用方在使用完密码后应立即将返回值置为 null,减少明文在内存中的留存时间。
     */
    public String getPassword() {
        try {
            String stored = sharedPreferences.getString(KEY_PASSWORD, "");
            if (stored.isEmpty()) return "";

            // 尝试解密（新格式）
            String decrypted = EncryptionUtil.decrypt(stored);
            if (decrypted != null) return decrypted;

            // 解密失败:密钥可能已变更(如重装App),清除无效密文,避免将密文当密码发送导致登录失败
            Log.w(TAG, "密码解密失败,密钥可能已变更,需要重新登录");
            sharedPreferences.edit().remove(KEY_PASSWORD).apply();
            return "";
        } catch (Exception e) {
            Log.e(TAG, "读取密码失败，返回空字符串", e);
            return "";
        }
    }

    // 保存登录时间
    public void setLoginTime(long time) {
        try {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putLong(KEY_LOGIN_TIME, time);
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "保存登录时间失败", e);
        }
    }

    // 获取登录时间
    public long getLoginTime() {
        try {
            return sharedPreferences.getLong(KEY_LOGIN_TIME, 0);
        } catch (Exception e) {
            Log.e(TAG, "读取登录时间失败，返回0", e);
            return 0;
        }
    }

    private static final String KEY_AVATAR = "avatar";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_NIGHT_MODE = "night_mode";

    // 保存头像URL
    public void setAvatar(String avatar) {
        try {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(KEY_AVATAR, avatar);
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "保存头像失败", e);
        }
    }

    // 获取头像URL
    public String getAvatar() {
        try {
            return sharedPreferences.getString(KEY_AVATAR, "");
        } catch (Exception e) {
            return "";
        }
    }

    // 保存邮箱
    public void setEmail(String email) {
        try {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(KEY_EMAIL, email);
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "保存邮箱失败", e);
        }
    }

    // 获取邮箱
    public String getEmail() {
        try {
            return sharedPreferences.getString(KEY_EMAIL, "");
        } catch (Exception e) {
            return "";
        }
    }

    // 保存夜间模式状态
    public void setNightMode(boolean isNightMode) {
        try {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean(KEY_NIGHT_MODE, isNightMode);
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "保存夜间模式状态失败", e);
        }
    }

    // 获取夜间模式状态
    public boolean isNightMode() {
        try {
            return sharedPreferences.getBoolean(KEY_NIGHT_MODE, false);
        } catch (Exception e) {
            return false;
        }
    }

    // 清除所有登录信息
    public void clearLoginInfo() {
        try {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.remove(KEY_IS_LOGGED_IN);
            editor.remove(KEY_USERNAME);
            editor.remove(KEY_PASSWORD);
            editor.remove(KEY_LOGIN_TIME);
            editor.remove(KEY_USER_ID);
            editor.remove(KEY_AVATAR);
            editor.remove(KEY_EMAIL);
            editor.remove(KEY_ACCESS_TOKEN);
            editor.remove(KEY_REFRESH_TOKEN);
            editor.remove(KEY_ACCESS_EXPIRES);
            editor.remove(KEY_REFRESH_EXPIRES);
            boolean success = editor.commit();
            Log.d(TAG, "清除登录信息, 结果: " + success);
        } catch (Exception e) {
            Log.e(TAG, "清除登录信息失败", e);
        }
        // 同时清除 TokenManager 中的 token,避免两处数据不一致
        try {
            TokenManager.getInstance(context).clearTokens();
        } catch (Exception e) {
            Log.e(TAG, "清除TokenManager失败", e);
        }
        clearMembershipInfo();
    }

    // 验证用户数据是否完整
    public boolean isUserDataValid() {
        boolean isValid = getLoginStatus() &&
                !getUsername().isEmpty() &&
                getUserId() != -1;

        Log.d(TAG, "用户数据验证: " + isValid +
                " (登录状态: " + getLoginStatus() +
                ", 用户名: " + (!getUsername().isEmpty()) +
                ", 用户ID: " + (getUserId() != -1) + ")");

        return isValid;
    }

    // 诊断SharedPreferences状态
    public void diagnose() {
        Log.d(TAG, "=== SharedPreferences诊断 ===");
        Log.d(TAG, "文件路径: " + context.getFilesDir().getParent() + "/shared_prefs/" + PREF_NAME + ".xml");
        Log.d(TAG, "登录状态: " + getLoginStatus());
        Log.d(TAG, "用户名: " + (getUsername().isEmpty() ? "空" : "***"));
        Log.d(TAG, "用户ID: " + (getUserId() > 0 ? "***" : "invalid"));
        Log.d(TAG, "密码: " + (getPassword().isEmpty() ? "空" : "已设置"));
        Log.d(TAG, "登录时间: " + getLoginTime());
        Log.d(TAG, "数据完整性: " + isUserDataValid());
        Log.d(TAG, "=== 诊断结束 ===");
    }






// 在 SharedPreferencesManager 类中添加以下方法

    /**
     * 设置会员类型
     */
    public void setMembershipType(String membershipType) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("membership_type", membershipType);
        editor.apply();
    }

    /**
     * 获取会员类型
     */
    public String getMembershipType() {
        return sharedPreferences.getString("membership_type", "");
    }

    /**
     * 清除会员类型信息
     */
    public void clearMembershipInfo() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove("membership_type");
        editor.apply();
    }

    // ========== 弹窗公告 每天展示一次 ==========
    private static final String KEY_NOTICE_LAST_SHOW = "notice_last_show_date";

    /**
     * 获取上次弹窗公告展示日期（yyyy-MM-dd）
     */
    public String getNoticeLastShowDate() {
        return sharedPreferences.getString(KEY_NOTICE_LAST_SHOW, "");
    }

    /**
     * 记录本次弹窗公告展示日期
     */
    public void setNoticeLastShowDate(String date) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_NOTICE_LAST_SHOW, date);
        editor.apply();
    }












    // ========== Token 管理 ==========
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_ACCESS_EXPIRES = "access_expires_at";
    private static final String KEY_REFRESH_EXPIRES = "refresh_expires_at";

    /**
     * @deprecated 请使用 TokenManager.getInstance(context) 统一管理 Token。
     *             保留此方法仅为向后兼容。
     */
    @Deprecated
    public void saveTokens(String accessToken, String refreshToken, String accessExpires, String refreshExpires) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_ACCESS_TOKEN, accessToken);
        editor.putString(KEY_REFRESH_TOKEN, refreshToken);
        editor.putString(KEY_ACCESS_EXPIRES, accessExpires);
        editor.putString(KEY_REFRESH_EXPIRES, refreshExpires);
        editor.apply();
    }

    /**
     * @deprecated 请使用 TokenManager.getInstance(context) 统一管理 Token。
     *             保留此方法仅为向后兼容。
     */
    @Deprecated
    public String getAccessToken() {
        return sharedPreferences.getString(KEY_ACCESS_TOKEN, "");
    }

    /**
     * @deprecated 请使用 TokenManager.getInstance(context) 统一管理 Token。
     *             保留此方法仅为向后兼容。
     */
    @Deprecated
    public String getRefreshToken() {
        return sharedPreferences.getString(KEY_REFRESH_TOKEN, "");
    }

    /**
     * @deprecated 请使用 TokenManager.getInstance(context) 统一管理 Token。
     *             保留此方法仅为向后兼容。
     */
    @Deprecated
    public String getAccessTokenExpires() {
        return sharedPreferences.getString(KEY_ACCESS_EXPIRES, "");
    }

    /**
     * @deprecated 请使用 TokenManager.getInstance(context) 统一管理 Token。
     *             保留此方法仅为向后兼容。
     */
    @Deprecated
    public String getRefreshTokenExpires() {
        return sharedPreferences.getString(KEY_REFRESH_EXPIRES, "");
    }

    /**
     * @deprecated 请使用 TokenManager.getInstance(context) 统一管理 Token。
     *             保留此方法仅为向后兼容。
     */
    @Deprecated
    public boolean hasValidTokens() {
        String accessExpires = getAccessTokenExpires();
        if (accessExpires.isEmpty()) return false;
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
            long expireTime = sdf.parse(accessExpires).getTime();
            return System.currentTimeMillis() < expireTime && !getAccessToken().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @deprecated 请使用 TokenManager.getInstance(context) 统一管理 Token。
     *             保留此方法仅为向后兼容。
     */
    @Deprecated
    public void clearTokens() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(KEY_ACCESS_TOKEN);
        editor.remove(KEY_REFRESH_TOKEN);
        editor.remove(KEY_ACCESS_EXPIRES);
        editor.remove(KEY_REFRESH_EXPIRES);
        editor.apply();
    }
}