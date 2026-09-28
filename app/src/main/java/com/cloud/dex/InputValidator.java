package com.cloud.dex;

import android.text.TextUtils;

import java.util.regex.Pattern;

public final class InputValidator {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9]{5,15}$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^.{5,10}$");
    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");
    private static final Pattern SAFE_TEXT = Pattern.compile("^[\\w\\u4e00-\\u9fa5\\s\\-,.()（）]{1,100}$");
    private static final Pattern URL_SAFE = Pattern.compile("^[a-zA-Z0-9_/:?.=&%-]+$");

    private InputValidator() {}

    public static boolean isValidUsername(String username) {
        return username != null && USERNAME_PATTERN.matcher(username).matches();
    }

    public static boolean isValidPassword(String password) {
        return password != null && PASSWORD_PATTERN.matcher(password).matches();
    }

    public static boolean isValidQQ(String qq) {
        return qq != null && DIGITS_ONLY.matcher(qq).matches() && qq.length() >= 5 && qq.length() <= 12;
    }

    public static boolean isSafeText(String text) {
        return text != null && SAFE_TEXT.matcher(text).matches();
    }

    public static boolean isUrlSafe(String url) {
        return url != null && URL_SAFE.matcher(url).matches();
    }

    public static boolean isValidAppName(String name) {
        return name != null && name.length() >= 1 && name.length() <= 50
                && SAFE_TEXT.matcher(name).matches();
    }

    public static boolean isValidAppDesc(String desc) {
        return desc == null || desc.length() <= 200;
    }

    /**
     * Sanitize a string for safe log output — redact if it looks sensitive.
     */
    public static String sanitizeForLog(String input) {
        if (input == null) return "null";
        if (input.length() > 100) return input.substring(0, 100) + "... (" + input.length() + " chars)";
        return input;
    }

    /**
     * Strip control characters and truncate to prevent log injection.
     */
    public static String sanitizeLogMessage(String message) {
        if (message == null) return "";
        return message.replace('\n', ' ').replace('\r', ' ')
                .substring(0, Math.min(message.length(), 200));
    }
}
