package com.cloud.dex;

import android.app.Application;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

public class MyApplication extends Application {

    private static final String TAG = "MyApplication";

    @Override
    public void onCreate() {
        super.onCreate();

        // 应用保存的夜间模式设置
        SharedPreferencesManager spManager = new SharedPreferencesManager(this);
        if (spManager.isNightMode()) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }

        // 设置全局异常处理器(温和模式:记录日志,交给系统默认处理器显示友好对话框)
        final Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                Log.e(TAG, "未捕获的异常: " + ex.getMessage(), ex);
                logCrashInfo(ex);

                // 记录崩溃时间戳,供下次启动友好提示
                try {
                    getSharedPreferences("crash_log", MODE_PRIVATE).edit()
                        .putString("last_crash_time", java.text.DateFormat.getDateTimeInstance().format(new java.util.Date()))
                        .putString("last_crash_msg", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())
                        .apply();
                } catch (Exception ignored) {}

                try { Thread.sleep(300); } catch (InterruptedException ignored) {}

                // 交给系统默认处理器(显示系统崩溃对话框,用户可手动重启)
                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, ex);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(1);
                }
            }
        });
    }

    private void logCrashInfo(Throwable ex) {
        Log.e(TAG, "=== 应用崩溃信息 ===");
        Log.e(TAG, "异常类型: " + ex.getClass().getName());
        Log.e(TAG, "异常信息: " + ex.getMessage());

        // 打印堆栈跟踪
        for (StackTraceElement element : ex.getStackTrace()) {
            Log.e(TAG, "at " + element.toString());
        }

        // 如果有原因异常，也打印出来
        if (ex.getCause() != null) {
            Log.e(TAG, "原因异常: " + ex.getCause().getMessage());
        }
    }
}