package com.cloud.dex;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileFilter;
import java.util.HashMap;
import java.util.Map;

public class AppUpdateManager {
    private static final String TAG = "AppUpdateManager";

    // URL unified: now using AppConfig.CHECK_VERSION_URL

    private Context context;
    private RequestQueue requestQueue;
    private SharedPreferencesManager spManager;
    private long downloadId;
    private DownloadManager downloadManager;
    private AlertDialog downloadDialog;
    private ProgressBar downloadProgress;
    private TextView downloadPercent;
    private String pendingDownloadUrl;

    // 安装权限请求码
    private static final int REQUEST_INSTALL_PERMISSION = 1001;

    public AppUpdateManager(Context context) {
        this.context = context;
        this.requestQueue = Volley.newRequestQueue(context);
        this.spManager = new SharedPreferencesManager(context);
        this.downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Log.d(TAG, "AppUpdateManager initialized");
    }

    public void checkUpdate(final boolean silent) {
        checkUpdate(silent, false);
    }

    /**
     * @param silent        是否静默（不弹「已是最新」「检查失败」提示）
     * @param forceShowDialog 即使本次已检查过也强制再查一次（用于登录成功后/进入主页）
     */
    public void checkUpdate(final boolean silent, final boolean forceShowDialog) {
        Log.d(TAG, "checkUpdate called, silent: " + silent + ", force=" + forceShowDialog);

        try {
            // 获取应用信息
            String appName = getAppName();
            String packageName = context.getPackageName();
            String currentVersion = BuildConfig.VERSION_NAME;
            int currentVersionCode = BuildConfig.VERSION_CODE;

            Log.d(TAG, "App Info - Name: " + appName + ", Package: " + packageName +
                    ", Version: " + currentVersion + ", VersionCode: " + currentVersionCode);

            StringRequest stringRequest = new StringRequest(Request.Method.POST, AppConfig.CHECK_VERSION_URL,
                    new Response.Listener<String>() {
                        @Override
                        public void onResponse(String response) {
                            Log.d(TAG, "Update check response: " + InputValidator.sanitizeForLog(response));
                            handleUpdateResponse(response, silent);
                        }
                    },
                    new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            Log.e(TAG, "Update check network error: " + error.toString());
                            if (error.networkResponse != null) {
                                Log.e(TAG, "Error status code: " + error.networkResponse.statusCode);
                                Log.e(TAG, "Error data: " + new String(error.networkResponse.data));
                            }
                            if (!silent) {
                                Toast.makeText(context, "检查更新失败，请检查网络连接", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }) {
                @Override
                protected Map<String, String> getParams() {
                    Map<String, String> params = new HashMap<>();
                    params.put("app_name", appName); // 使用自动获取的应用名称
                    params.put("package_name", packageName); // 同时发送包名，服务器可以根据需要选择使用哪个
                    params.put("current_version", currentVersion);
                    params.put("current_version_code", String.valueOf(currentVersionCode));
                    Log.d(TAG, "Request params: " + InputValidator.sanitizeForLog(params.toString()));
                    return params;
                }

                @Override
                public Map<String, String> getHeaders() {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/x-www-form-urlencoded");
                    return headers;
                }
            };

            // 设置请求标签以便取消
            stringRequest.setTag(TAG);
            requestQueue.add(stringRequest);
            Log.d(TAG, "Update check request added to queue");

        } catch (Exception e) {
            Log.e(TAG, "Exception in checkUpdate", e);
            if (!silent) {
                Toast.makeText(context, "检查更新时发生异常", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 自动获取应用名称
     */
    private String getAppName() {
        try {
            PackageManager packageManager = context.getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(context.getPackageName(), 0);
            String appName = (String) packageManager.getApplicationLabel(applicationInfo);
            Log.d(TAG, "Auto detected app name: " + appName);
            return appName;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to get app name", e);
            // 如果获取失败，使用包名作为备用
            String packageName = context.getPackageName();
            Log.d(TAG, "Using package name as fallback: " + packageName);
            return packageName;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error getting app name", e);
            // 最终备用方案
            return "MyApplication";
        }
    }

    private void handleUpdateResponse(String response, boolean silent) {
        Log.d(TAG, "Handling update response");

        try {
            JSONObject jsonResponse = new JSONObject(response);
            boolean success = jsonResponse.getBoolean("success");
            Log.d(TAG, "Response success: " + success);

            if (success) {
                // 兼容服务端两种字段命名：needs_update（check_version.php 实际返回）/ has_update
                boolean hasUpdate = jsonResponse.optBoolean("needs_update",
                        jsonResponse.optBoolean("has_update", false));
                Log.d(TAG, "Has update: " + hasUpdate);

                if (hasUpdate) {
                    JSONObject latestVersion = jsonResponse.getJSONObject("latest_version");
                    Log.d(TAG, "Latest version info: " + InputValidator.sanitizeForLog(latestVersion.toString()));

                    // 在UI线程显示更新对话框
                    ((Activity) context).runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showUpdateDialog(latestVersion);
                        }
                    });
                } else {
                    Log.d(TAG, "No update available");
                    if (!silent) {
                        ((Activity) context).runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(context, "已经是最新版本", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            } else {
                String message = jsonResponse.optString("message", "检查更新失败");
                Log.e(TAG, "Server returned error: " + message);
                if (!silent) {
                    ((Activity) context).runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }

        } catch (JSONException e) {
            Log.e(TAG, "JSON parsing error in update response", e);
            if (!silent) {
                ((Activity) context).runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(context, "解析更新信息失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling update response", e);
        }
    }

    private void showUpdateDialog(JSONObject versionInfo) {
        Log.d(TAG, "Showing update dialog");

        try {
            String versionName = versionInfo.getString("version_name");
            String updateLog = versionInfo.optString("update_log", "暂无更新日志");
            boolean forceUpdate = versionInfo.optBoolean("force_update", false);
            // 兼容服务端两种字段命名：download_url（check_version.php 实际返回）/ update_url
            final String downloadUrl = versionInfo.optString("download_url",
                    versionInfo.optString("update_url", ""));
            if (downloadUrl == null || downloadUrl.isEmpty()) {
                throw new JSONException("缺少下载地址字段 download_url/update_url");
            }
            long fileSize = versionInfo.optLong("file_size", 0);

            String fileSizeStr = formatFileSize(fileSize);
            String message = "新版本: v" + versionName + "\n\n更新内容:\n" + updateLog + "\n\n文件大小: " + fileSizeStr;

            Log.d(TAG, "Update dialog info - Version: " + InputValidator.sanitizeForLog(versionName) + ", Force: " + forceUpdate + ", URL: " + InputValidator.sanitizeForLog(downloadUrl));

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("发现新版本");
            builder.setMessage(message);

            if (forceUpdate) {
                builder.setCancelable(false);
                builder.setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Log.d(TAG, "User clicked update (force)");
                        startDownload(downloadUrl);
                    }
                });
            } else {
                builder.setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Log.d(TAG, "User clicked update");
                        startDownload(downloadUrl);
                    }
                });
                builder.setNegativeButton("稍后再说", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Log.d(TAG, "User clicked later");
                    }
                });
            }

            try {
                builder.show();
                Log.d(TAG, "Update dialog shown successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to show update dialog", e);
            }

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing version info for dialog", e);
            Toast.makeText(context, "更新信息错误", Toast.LENGTH_SHORT).show();
        }
    }

    private void startDownload(String downloadUrl) {
        Log.d(TAG, "Starting download from: " + InputValidator.sanitizeForLog(downloadUrl));

        // 检查安装权限（Android 8.0+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                Log.d(TAG, "Requesting install permission");
                this.pendingDownloadUrl = downloadUrl;
                requestInstallPermission();
                return;
            }
        }

        // 继续下载逻辑
        proceedWithDownload(downloadUrl);
    }

    /**
     * 请求安装未知应用权限
     */
    private void requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("安装权限请求")
                    .setMessage("应用更新需要安装未知应用权限，请允许此权限")
                    .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                            intent.setData(Uri.parse("package:" + context.getPackageName()));
                            try {
                                ((Activity) context).startActivityForResult(intent, REQUEST_INSTALL_PERMISSION);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to start settings activity", e);
                                Toast.makeText(context, "无法打开设置页面，请手动开启安装权限", Toast.LENGTH_LONG).show();
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    private void proceedWithDownload(String downloadUrl) {
        try {
            this.pendingDownloadUrl = downloadUrl;

            // 创建下载目录
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            String fileName = "MyApp_Update_" + System.currentTimeMillis() + ".apk";

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            request.setTitle("应用更新");
            request.setDescription("正在下载新版本...");
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setMimeType("application/vnd.android.package-archive");


            downloadId = downloadManager.enqueue(request);
            Log.d(TAG, "Download started with ID: " + downloadId);

            // 注册下载完成广播接收器
            context.registerReceiver(downloadCompleteReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        } catch (Exception e) {
            Log.e(TAG, "Failed to start download", e);
            Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 重试下载（在获得权限后调用）
     */
    public void retryDownload() {
        if (pendingDownloadUrl != null) {
            Log.d(TAG, "Retrying download with URL: " + InputValidator.sanitizeForLog(pendingDownloadUrl));
            startDownload(pendingDownloadUrl);
        } else {
            Log.e(TAG, "No pending download URL available for retry");
            Toast.makeText(context, "无法重新下载，请手动检查更新", Toast.LENGTH_SHORT).show();
        }
    }

    private void startProgressUpdate() {
        Log.d(TAG, "Starting progress update thread");

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (downloadDialog != null && downloadDialog.isShowing()) {
                    try {
                        Thread.sleep(1000);
                        updateDownloadProgress();
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Error in progress update", e);
                    }
                }
                Log.d(TAG, "Progress update thread stopped");
            }
        }).start();
    }

    private void updateDownloadProgress() {
        try {
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);

            android.database.Cursor cursor = downloadManager.query(query);
            if (cursor.moveToFirst()) {
                int bytesDownloaded = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                int bytesTotal = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

                if (bytesTotal > 0) {
                    final int progress = (int) ((bytesDownloaded * 100L) / bytesTotal);
                    final String percentText = progress + "%";
                    final String sizeText = formatFileSize(bytesDownloaded) + "/" + formatFileSize(bytesTotal);

                    Log.d(TAG, "Download progress: " + progress + "% (" + sizeText + ")");

                    ((Activity) context).runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (downloadProgress != null) {
                                downloadProgress.setProgress(progress);
                            }
                            if (downloadPercent != null) {
                                downloadPercent.setText(percentText + " (" + sizeText + ")");
                            }
                        }
                    });
                }
            }
            cursor.close();
        } catch (Exception e) {
            Log.e(TAG, "Error updating download progress", e);
        }
    }

    private BroadcastReceiver downloadCompleteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long completeDownloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            Log.d(TAG, "Download complete received for ID: " + completeDownloadId);

            if (completeDownloadId == downloadId) {
                Log.d(TAG, "Our download completed");

                if (downloadDialog != null && downloadDialog.isShowing()) {
                    downloadDialog.dismiss();
                    Log.d(TAG, "Download dialog dismissed");
                }

                // 显示安装对话框而不是直接安装
                ((Activity) context).runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showInstallDialog();
                    }
                });

                // 取消注册广播接收器
                try {
                    context.unregisterReceiver(this);
                    Log.d(TAG, "Download receiver unregistered");
                } catch (Exception e) {
                    Log.e(TAG, "Error unregistering download receiver", e);
                }
            }
        }
    };

    private void showInstallDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("下载完成")
                .setMessage("应用已下载完成，是否立即安装？")
                .setPositiveButton("安装", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        installApk();
                    }
                })
                .setNegativeButton("手动安装", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showManualInstallInstructions();
                    }
                })
                .setNeutralButton("稍后", null)
                .show();
    }

    private void showManualInstallInstructions() {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File apkFile = findLatestApkFile(downloadDir);

        String message = "请按照以下步骤手动安装：\n\n" +
                "1. 打开文件管理器\n" +
                "2. 进入下载目录 (Download)\n" +
                "3. 找到文件: " + (apkFile != null ? apkFile.getName() : "MyApp_Update_*.apk") + "\n" +
                "4. 点击文件进行安装";

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("手动安装指导")
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show();
    }

    private void installApk() {
        Log.d(TAG, "Installing APK");

        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

            // 更精确地查找APK文件
            File apkFile = findLatestApkFile(downloadDir);

            if (apkFile != null && apkFile.exists()) {
                Log.d(TAG, "Found APK file: " + InputValidator.sanitizeForLog(apkFile.getAbsolutePath()) + ", size: " + apkFile.length());

                // 检查文件大小，确保文件完整
                if (apkFile.length() < 1024) {
                    Log.e(TAG, "APK file too small, likely corrupted");
                    Toast.makeText(context, "下载文件损坏，请重新下载", Toast.LENGTH_LONG).show();
                    return;
                }

                // 设置文件可读权限
                apkFile.setReadable(true, false);

                Intent installIntent = new Intent(Intent.ACTION_VIEW);

                // 根据不同Android版本使用不同的安装方式
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Android 7.0+ 使用FileProvider
                    Uri apkUri = FileProvider.getUriForFile(context,
                            context.getPackageName() + ".fileprovider", apkFile);
                    installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Log.d(TAG, "Using FileProvider for installation");
                } else {
                    // Android 7.0以下使用传统方式
                    installIntent.setDataAndType(Uri.fromFile(apkFile),
                            "application/vnd.android.package-archive");
                    Log.d(TAG, "Using traditional installation");
                }

                installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                // 添加额外的标志位以提高兼容性
                installIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

                try {
                    context.startActivity(installIntent);
                    Log.d(TAG, "Install intent started successfully");
                } catch (ActivityNotFoundException e) {
                    Log.e(TAG, "No activity found to handle APK installation", e);
                    Toast.makeText(context, "无法找到安装程序，请检查系统设置", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start installation", e);
                    Toast.makeText(context, "安装启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else {
                Log.e(TAG, "APK file not found");
                Toast.makeText(context, "安装文件不存在，请重新下载", Toast.LENGTH_LONG).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to install APK", e);
            Toast.makeText(context, "安装失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 查找最新的APK文件
     */
    private File findLatestApkFile(File downloadDir) {
        if (downloadDir == null || !downloadDir.exists()) {
            Log.e(TAG, "Download directory does not exist");
            return null;
        }

        File[] apkFiles = downloadDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isFile() &&
                        file.getName().toLowerCase().endsWith(".apk") &&
                        file.getName().contains("MyApp_Update");
            }
        });

        if (apkFiles == null || apkFiles.length == 0) {
            Log.e(TAG, "No APK files found in download directory");
            return null;
        }

        // 返回最新的文件（按修改时间排序）
        File latestFile = apkFiles[0];
        for (File file : apkFiles) {
            if (file.lastModified() > latestFile.lastModified()) {
                latestFile = file;
            }
        }

        Log.d(TAG, "Found " + apkFiles.length + " APK files, using: " + latestFile.getName());
        return latestFile;
    }

    private void cancelDownload() {
        Log.d(TAG, "Cancelling download");

        if (downloadId != 0) {
            downloadManager.remove(downloadId);
            Log.d(TAG, "Download cancelled with ID: " + downloadId);
        }
        if (downloadDialog != null && downloadDialog.isShowing()) {
            downloadDialog.dismiss();
            Log.d(TAG, "Download dialog dismissed");
        }
        try {
            context.unregisterReceiver(downloadCompleteReceiver);
            Log.d(TAG, "Download receiver unregistered");
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering download receiver on cancel", e);
        }
    }

    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";

        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));

        return String.format("%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    public void destroy() {
        Log.d(TAG, "Destroying AppUpdateManager");
        if (requestQueue != null) {
            requestQueue.cancelAll(TAG);
            Log.d(TAG, "Requests cancelled");
        }
        try {
            context.unregisterReceiver(downloadCompleteReceiver);
            Log.d(TAG, "Download receiver unregistered in destroy");
        } catch (Exception e) {
            // 忽略未注册的异常
        }
    }

    /**
     * 获取安装权限请求码
     */
    public static int getRequestInstallPermissionCode() {
        return REQUEST_INSTALL_PERMISSION;
    }
}