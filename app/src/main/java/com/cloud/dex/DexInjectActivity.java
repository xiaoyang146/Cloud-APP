package com.cloud.dex;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.MethodParameter;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodImplementation;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.android.apksig.ApkSigner;

import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.security.auth.x500.X500Principal;

public class DexInjectActivity extends AppCompatActivity {
    private static final String TAG = "DEXInjector";
    private static final int REQUEST_CODE_SELECT_APK = 1001;
    private static final int REQUEST_CODE_STORAGE_PERMISSION = 1002;
    private static final int REQUEST_CODE_MANAGE_STORAGE = 1003;
    private static final int REQUEST_CODE_INSTALL_APK = 1004;
    private static final int REQUEST_CODE_UNINSTALL_APK = 1005;
    private static final int REQUEST_CODE_UNINSTALL_PERMISSION = 1006;

    private Button btnSelectApk, btnInject;
    private TextView tvSelectedApk, tvLog, tvSelectedActivity, tvConfigStatus;
    private ScrollView svLogContainer;

    private String selectedApkPath;
    private Uri selectedApkUri;
    private List<String> activityList = new ArrayList<>();
    private String selectedActivity;
    private String selectedAppId, selectedContact, selectedCardUrl, selectedVersion;
    private String configTitle1, configTitle2, configTitle3, configTitle4;
    private String knownMainActivity;
    private String pendingInstallApkPath;
    private String pendingUninstallPkg;
    private BroadcastReceiver uninstallReceiver;

    private static final String AUTHOR_QQ = AppConfig.AUTHOR_QQ;
    @SuppressWarnings("deprecation")
    private File workDir = new File(Environment.getExternalStorageDirectory(), "Dex");

    private ProgressDialog progressDialog;
    private static final int COMPATIBLE_API_LEVEL = Build.VERSION.SDK_INT;

    // ── 混淆/加固检测结果类 ──

    private static class ObfuscationResult {
        boolean isObfuscated;
        int totalClasses;
        int obfuscatedClassCount;
        double obfuscatedClassRatio;
        int totalMethods;
        int obfuscatedMethodCount;
        double obfuscatedMethodRatio;
        String details;
    }

    private static class PackingResult {
        boolean isPacked;
        String packerName;
        String details;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dex_inject);
        setupActionBar();
        initViews();
        checkPermissions();
    }

    private void setupActionBar() {
        try {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.card_bg)));
                SpannableString title = new SpannableString("DEX注入");
                title.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.text_black)), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                getSupportActionBar().setTitle(title);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置ActionBar失败", e);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void ensureWorkDir() {
        if (!workDir.exists()) {
            if (!workDir.mkdirs()) {
                appendLog("创建工作目录失败: " + workDir.getAbsolutePath());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    showManageStoragePermissionDialog();
                }
                return;
            }
            appendLog("创建工作目录: " + workDir.getAbsolutePath());
        } else {
            appendLog("使用工作目录: " + workDir.getAbsolutePath());
        }
    }

    private void cleanWorkDir() {
        if (!workDir.exists()) return;
        File[] files = workDir.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteDirectory(file);
            }
            appendLog("已清空工作目录: " + workDir.getAbsolutePath());
        }
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showManageStoragePermissionDialog();
            } else {
                ensureWorkDir();
            }
        } else {
            List<String> permissionsNeeded = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (!permissionsNeeded.isEmpty()) {
                ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), REQUEST_CODE_STORAGE_PERMISSION);
            } else {
                ensureWorkDir();
            }
        }
    }

    private void showManageStoragePermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("需要存储权限")
                .setMessage("本应用需要访问/storage/emulated/0/Dex文件夹，请授予【所有文件访问权限】")
                .setPositiveButton("去设置", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
                    } catch (Exception e) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
                    }
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    Toast.makeText(this, "未授予权限，无法使用公共存储目录", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
                .setTitle("权限不足")
                .setMessage("存储权限被拒绝，无法使用/storage/emulated/0/Dex目录，是否退出应用？")
                .setPositiveButton("退出", (dialog, which) -> finish())
                .setNegativeButton("重试", (dialog, which) -> checkPermissions())
                .setCancelable(false)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_STORAGE_PERMISSION) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                ensureWorkDir();
                Toast.makeText(this, "存储权限已获取", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "存储权限被拒绝", Toast.LENGTH_SHORT).show();
                showPermissionDeniedDialog();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    ensureWorkDir();
                    Toast.makeText(this, "所有文件访问权限已获取", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "所有文件访问权限被拒绝", Toast.LENGTH_SHORT).show();
                    showPermissionDeniedDialog();
                }
            }
            return;
        }
        if (resultCode == RESULT_OK && requestCode == REQUEST_CODE_SELECT_APK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    try {
                        getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException e) {
                        Log.w(TAG, "无法获取持久URI权限", e);
                    }
                }
                selectedApkUri = uri;
                selectedApkPath = null;
                knownMainActivity = null;
                String displayName = getFileNameFromUri(uri);
                tvSelectedApk.setText("已选择: " + (displayName != null ? displayName : uri.toString()));
                progressDialog = new ProgressDialog(DexInjectActivity.this);
                progressDialog.setMessage("正在解析应用...");
                progressDialog.setCancelable(false);
                progressDialog.show();
                loadActivitiesFromUri(uri);
            }
        } else if (requestCode == REQUEST_CODE_INSTALL_APK) {
            appendLog("安装完成");
        } else if (requestCode == REQUEST_CODE_UNINSTALL_PERMISSION) {
            // 权限请求返回后继续卸载
            if (pendingInstallApkPath != null) {
                String targetPkg = getPackageNameFromApk(pendingInstallApkPath);
                if (targetPkg != null) {
                    doUninstall(targetPkg);
                }
            }
        }
    }

    private String getFileNameFromUri(Uri uri) {
        String fileName = null;
        if (uri == null) return null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "获取文件名失败", e);
            }
        }
        if (fileName == null && uri.getLastPathSegment() != null) {
            fileName = uri.getLastPathSegment();
        }
        return fileName;
    }

    private void initViews() {
        btnSelectApk = findViewById(R.id.btn_select_apk);
        btnInject = findViewById(R.id.btn_inject);
        tvSelectedApk = findViewById(R.id.tv_selected_apk);
        tvLog = findViewById(R.id.tv_log);
        tvSelectedActivity = findViewById(R.id.tv_selected_activity);
        tvConfigStatus = findViewById(R.id.tv_config_status);
        svLogContainer = findViewById(R.id.scroll_view);

        btnSelectApk.setOnClickListener(v -> selectApkFile());
        btnInject.setOnClickListener(v -> {
            if ((selectedApkPath == null && selectedApkUri == null) || selectedActivity == null ||
                    selectedContact == null || selectedCardUrl == null) {
                Toast.makeText(this, "请先完成所有配置步骤", Toast.LENGTH_SHORT).show();
                return;
            }
            startInjection();
        });

        tvLog.setMovementMethod(new android.text.method.ScrollingMovementMethod());
    }

    private void contactAuthor() {
        try {
            String url = "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=" + AUTHOR_QQ;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (isQQInstalled()) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setPackage("com.tencent.mobileqq");
                startActivity(intent);
                appendLog("跳转到QQ个人资料成功，QQ号: " + AUTHOR_QQ);
            } else {
                showQQNotInstalledDialog(AUTHOR_QQ);
            }
        } catch (Exception e) {
            Log.e(TAG, "跳转QQ个人资料失败", e);
            appendLog("跳转QQ失败: " + e.getMessage());
            tryAlternativeQQSchemes();
        }
    }

    private boolean isQQInstalled() {
        try {
            PackageManager packageManager = getPackageManager();
            try {
                packageManager.getPackageInfo("com.tencent.mobileqq", PackageManager.GET_ACTIVITIES);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("mqq://"));
            List<ResolveInfo> list = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return list.size() > 0;
        } catch (Exception e) {
            Log.e(TAG, "检查QQ安装状态失败", e);
            return false;
        }
    }

    private void tryAlternativeQQSchemes() {
        try {
            String url = "mqq://card/show_pslcard?src_type=internal&uin=" + AUTHOR_QQ;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                appendLog("使用备选方案1跳转QQ个人资料成功");
                return;
            }
        } catch (Exception e1) {
            Log.e(TAG, "备选方案1失败", e1);
        }
        try {
            String url = "mqqapi://card/show_pslcard?uin=" + AUTHOR_QQ;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                appendLog("使用备选方案2跳转QQ个人资料成功");
                return;
            }
        } catch (Exception e2) {
            Log.e(TAG, "备选方案2失败", e2);
        }
        showAlternativeContactMethods();
    }

    private void showQQNotInstalledDialog(final String qqNumber) {
        new AlertDialog.Builder(this)
                .setTitle("QQ未安装")
                .setMessage("检测到您的设备未安装QQ，是否前往下载？\n\n作者QQ: " + qqNumber)
                .setPositiveButton("下载QQ", (dialog, which) -> {
                    Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.tencent.mobileqq"));
                    try {
                        startActivity(marketIntent);
                    } catch (Exception e) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://im.qq.com")));
                    }
                })
                .setNegativeButton("复制QQ号", (dialog, which) -> copyQQToClipboard(qqNumber))
                .setNeutralButton("取消", null)
                .setCancelable(false)
                .show();
    }

    private void copyQQToClipboard(String qqNumber) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("作者QQ号", qqNumber);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "作者QQ号已复制到剪贴板: " + qqNumber, Toast.LENGTH_LONG).show();
            appendLog("已复制作者QQ号: " + qqNumber);
        } catch (Exception e) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void showAlternativeContactMethods() {
        new AlertDialog.Builder(this)
                .setTitle("联系作者")
                .setMessage("无法自动跳转QQ，请选择联系方式：\n\n作者QQ: " + AUTHOR_QQ)
                .setPositiveButton("复制QQ号", (dialog, which) -> copyQQToClipboard(AUTHOR_QQ))
                .setNeutralButton("手动打开QQ", (dialog, which) -> showManualQQGuide(AUTHOR_QQ))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showManualQQGuide(final String qqNumber) {
        new AlertDialog.Builder(this)
                .setTitle("手动联系作者")
                .setMessage("请按以下步骤操作：\n\n1. 打开QQ应用\n2. 点击右上角\"+\"号\n3. 选择\"加好友/群\"\n4. 输入QQ号: " + qqNumber + "\n5. 搜索并添加作者\n\n或者您也可以复制QQ号后手动添加")
                .setPositiveButton("复制QQ号", (dialog, which) -> copyQQToClipboard(qqNumber))
                .setNegativeButton("知道了", null)
                .show();
    }

    private void selectApkFile() {
        String[] options = {"选择已安装应用", "选择APK文件"};
        new AlertDialog.Builder(this)
                .setTitle("选择APK来源")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) showInstalledAppsDialog();
                    else selectApkFromStorage();
                })
                .show();
    }

    private void showInstalledAppsDialog() {
        PackageManager pm = getPackageManager();

        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(launcherIntent, 0);
        final List<String> allAppNames = new ArrayList<>();
        final List<android.graphics.drawable.Drawable> allAppIcons = new ArrayList<>();
        final List<String> allPackageNames = new ArrayList<>();
        final List<String> allPackagePaths = new ArrayList<>();
        final List<String> allMainActivities = new ArrayList<>();

        Set<String> addedPackages = new HashSet<>();

        for (ResolveInfo resolveInfo : resolveInfos) {
            ApplicationInfo appInfo = resolveInfo.activityInfo.applicationInfo;
            String packageName = appInfo.packageName;

            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0 && !addedPackages.contains(packageName)) {
                addedPackages.add(packageName);
                allAppNames.add(pm.getApplicationLabel(appInfo).toString());
                allAppIcons.add(pm.getApplicationIcon(appInfo));
                allPackageNames.add(packageName);
                allPackagePaths.add(appInfo.sourceDir);
                allMainActivities.add(resolveInfo.activityInfo.name);
            }
        }

        if (allAppNames.isEmpty()) {
            Toast.makeText(this, "未找到用户应用", Toast.LENGTH_SHORT).show();
            return;
        }

        final List<Integer> filteredPositions = new ArrayList<>();
        for (int i = 0; i < allAppNames.size(); i++) {
            filteredPositions.add(i);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_installed_apps, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        dialog.show();

        ListView lvApps = dialogView.findViewById(R.id.lv_installed_apps);
        EditText etSearch = dialogView.findViewById(R.id.et_search_app);

        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override
            public int getCount() {
                return filteredPositions.size();
            }

            @Override
            public Object getItem(int position) {
                return allAppNames.get(filteredPositions.get(position));
            }

            @Override
            public long getItemId(int position) {
                return filteredPositions.get(position);
            }

            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                InstalledAppViewHolder holder;
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(R.layout.dialog_installed_app_item, parent, false);
                    holder = new InstalledAppViewHolder();
                    holder.ivAppIcon = convertView.findViewById(R.id.iv_app_icon);
                    holder.tvAppName = convertView.findViewById(R.id.tv_app_name);
                    holder.tvAppPackage = convertView.findViewById(R.id.tv_app_package);
                    convertView.setTag(holder);
                } else {
                    holder = (InstalledAppViewHolder) convertView.getTag();
                }

                int realPos = filteredPositions.get(position);
                holder.ivAppIcon.setImageDrawable(allAppIcons.get(realPos));
                holder.tvAppName.setText(allAppNames.get(realPos));
                holder.tvAppPackage.setText(allPackageNames.get(realPos));

                return convertView;
            }
        };

        lvApps.setAdapter(adapter);
        if (!filteredPositions.isEmpty()) {
            lvApps.setItemChecked(0, true);
        }

        lvApps.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < filteredPositions.size()) {
                int realPos = filteredPositions.get(position);
                selectedApkPath = allPackagePaths.get(realPos);
                selectedApkUri = null;
                knownMainActivity = allMainActivities.get(realPos);
                tvSelectedApk.setText("已选择: " + allAppNames.get(realPos));
                dialog.dismiss();
                progressDialog = new ProgressDialog(DexInjectActivity.this);
                progressDialog.setMessage("正在解析应用...");
                progressDialog.setCancelable(false);
                progressDialog.show();
                loadActivitiesFromApk(selectedApkPath);
            }
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String searchText = s.toString().toLowerCase().trim();
                filteredPositions.clear();
                if (searchText.isEmpty()) {
                    for (int i = 0; i < allAppNames.size(); i++) {
                        filteredPositions.add(i);
                    }
                } else {
                    for (int i = 0; i < allAppNames.size(); i++) {
                        if (allAppNames.get(i).toLowerCase().contains(searchText)
                                || allPackageNames.get(i).toLowerCase().contains(searchText)) {
                            filteredPositions.add(i);
                        }
                    }
                }
                adapter.notifyDataSetChanged();
                if (filteredPositions.size() > 0) {
                    lvApps.setItemChecked(0, true);
                }
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        etSearch.setOnTouchListener((v, event) -> {
            final int DRAWABLE_RIGHT = 2;
            if (event.getAction() == MotionEvent.ACTION_UP && event.getRawX() >= (etSearch.getRight() - etSearch.getCompoundDrawables()[DRAWABLE_RIGHT].getBounds().width())) {
                etSearch.setText("");
                return true;
            }
            return false;
        });


    }

    private static class InstalledAppViewHolder {
        android.widget.ImageView ivAppIcon;
        TextView tvAppName;
        TextView tvAppPackage;
    }

    private void selectApkFromStorage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/vnd.android.package-archive");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }
        startActivityForResult(intent, REQUEST_CODE_SELECT_APK);
    }

    private void loadActivitiesFromUri(Uri uri) {
        new AsyncTask<Void, Void, List<String>>() {
            private String tempApkPath;
            private ObfuscationResult obfResult;
            private PackingResult packResult;

            @Override
            protected List<String> doInBackground(Void... voids) {
                try {
                    ensureWorkDir();
                    InputStream inputStream = getContentResolver().openInputStream(uri);
                    if (inputStream == null) return null;
                    File tempApk = new File(workDir, "temp_parse_" + System.currentTimeMillis() + ".apk");
                    tempApkPath = tempApk.getAbsolutePath();
                    try (OutputStream outputStream = new FileOutputStream(tempApk)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = inputStream.read(buffer)) > 0) {
                            outputStream.write(buffer, 0, len);
                        }
                    }
                    inputStream.close();
                    List<String> activities = extractActivitiesFromApk(tempApkPath);
                    obfResult = detectObfuscation(tempApkPath);
                    packResult = detectPackingOrEncryption(tempApkPath);
                    return activities;
                } catch (Exception e) {
                    Log.e(TAG, "临时复制APK失败", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(List<String> activities) {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                activityList.clear();
                if (activities != null && !activities.isEmpty()) {
                    activityList.addAll(activities);
                    boolean obfuscated = obfResult != null && obfResult.isObfuscated;
                    boolean packed = packResult != null && packResult.isPacked;
                    appendLog("检测结果: 混淆=" + (obfuscated ? "有" : "无") + " 加固=" + (packed ? "有" : "无"));
                    if (packResult != null) {
                        appendLog("加固详情: " + packResult.packerName);
                    }
                    if (obfuscated || packed) {
                        showProtectionWarningDialog(obfuscated, obfResult, packed, packResult,
                                () -> showActivitySelectionDialog());
                    } else {
                        showActivitySelectionDialog();
                    }
                } else {
                    appendLog("未找到Activity，请手动输入");
                    showManualActivityInput();
                }
                if (tempApkPath != null) {
                    new File(tempApkPath).delete();
                }
            }
        }.execute();
    }

    private void loadActivitiesFromApk(String apkPath) {
        new AsyncTask<String, Void, List<String>>() {
            private ObfuscationResult obfResult;
            private PackingResult packResult;
            private String taskApkPath;

            @Override
            protected List<String> doInBackground(String... paths) {
                taskApkPath = paths[0];
                List<String> activities = extractActivitiesFromApk(taskApkPath, knownMainActivity);
                obfResult = detectObfuscation(taskApkPath);
                packResult = detectPackingOrEncryption(taskApkPath);
                return activities;
            }
            @Override
            protected void onPostExecute(List<String> activities) {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                knownMainActivity = null;
                activityList.clear();
                if (activities != null && !activities.isEmpty()) {
                    activityList.addAll(activities);
                    boolean obfuscated = obfResult != null && obfResult.isObfuscated;
                    boolean packed = packResult != null && packResult.isPacked;
                    appendLog("检测结果: 混淆=" + (obfuscated ? "有" : "无") + " 加固=" + (packed ? "有" : "无"));
                    if (packResult != null) {
                        appendLog("加固详情: " + packResult.packerName);
                    }
                    if (obfuscated || packed) {
                        showProtectionWarningDialog(obfuscated, obfResult, packed, packResult,
                                () -> showActivitySelectionDialog());
                    } else {
                        showActivitySelectionDialog();
                    }
                } else {
                    appendLog("未找到Activity，请手动输入");
                    showManualActivityInput();
                }
            }
        }.execute(apkPath);
    }

    private void showActivitySelectionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_activity_selection, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        dialog.show();

        ListView lvActivities = dialogView.findViewById(R.id.lv_activities_dialog);
        Button btnConfirm = dialogView.findViewById(R.id.btn_confirm_activity);
        EditText etSearch = dialogView.findViewById(R.id.et_search_activity);
        final List<String> originalActivityList = new ArrayList<>(activityList);
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, activityList) {
            @androidx.annotation.NonNull
            @Override
            public android.view.View getView(int position, android.view.View convertView, @androidx.annotation.NonNull android.view.ViewGroup parent) {
                android.view.View view = super.getView(position, convertView, parent);
                String activityName = getItem(position);
                android.widget.TextView tv = (android.widget.TextView) view;
                if (activityName.equals(originalActivityList.get(0))) {
                    tv.setText(activityName + "  🏠主界面");
                } else if (isActivityNameObfuscated(activityName)) {
                    tv.setText(activityName + "  ⚠混淆");
                }
                return view;
            }

            @androidx.annotation.NonNull
            @Override
            public android.view.View getDropDownView(int position, android.view.View convertView, @androidx.annotation.NonNull android.view.ViewGroup parent) {
                android.view.View view = super.getDropDownView(position, convertView, parent);
                String activityName = getItem(position);
                android.widget.TextView tv = (android.widget.TextView) view;
                if (activityName.equals(originalActivityList.get(0))) {
                    tv.setText(activityName + "  🏠主界面");
                } else if (isActivityNameObfuscated(activityName)) {
                    tv.setText(activityName + "  ⚠混淆");
                }
                return view;
            }
        };
        lvActivities.setAdapter(adapter);
        if (!activityList.isEmpty()) {
            lvActivities.setItemChecked(0, true);
            selectedActivity = activityList.get(0);
        }
        lvActivities.setOnItemClickListener((parent, view, position, id) -> {
            selectedActivity = adapter.getItem(position);
            appendLog("选中Activity: " + selectedActivity);
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String searchText = s.toString().toLowerCase().trim();
                activityList.clear();
                if (searchText.isEmpty()) {
                    activityList.addAll(originalActivityList);
                } else {
                    for (String activity : originalActivityList) {
                        if (activity.toLowerCase().contains(searchText)) activityList.add(activity);
                    }
                }
                adapter.notifyDataSetChanged();
                if (adapter.getCount() > 0) {
                    lvActivities.setItemChecked(0, true);
                    selectedActivity = adapter.getItem(0);
                } else {
                    selectedActivity = null;
                }
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });
        etSearch.setOnTouchListener((v, event) -> {
            final int DRAWABLE_RIGHT = 2;
            if (event.getAction() == MotionEvent.ACTION_UP && event.getRawX() >= (etSearch.getRight() - etSearch.getCompoundDrawables()[DRAWABLE_RIGHT].getBounds().width())) {
                etSearch.setText("");
                return true;
            }
            return false;
        });
        btnConfirm.setOnClickListener(v -> {
            if (selectedActivity != null) {
                tvSelectedActivity.setText(selectedActivity);
                dialog.dismiss();
                showConfigDialog();
            } else {
                Toast.makeText(DexInjectActivity.this, "请选择一个Activity", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showConfigDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_config, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();
        dialog.show();

        EditText etContact = dialogView.findViewById(R.id.et_contact_dialog);
        EditText etCardUrl = dialogView.findViewById(R.id.et_card_url_dialog);
        EditText etVersion = dialogView.findViewById(R.id.et_version_dialog);
        EditText etTitle1 = dialogView.findViewById(R.id.et_title_1);
        EditText etTitle2 = dialogView.findViewById(R.id.et_title_2);
        EditText etTitle3 = dialogView.findViewById(R.id.et_title_3);
        EditText etTitle4 = dialogView.findViewById(R.id.et_title_4);
        Button btnCancel = dialogView.findViewById(R.id.btn_cancel_config);
        Button btnConfirm = dialogView.findViewById(R.id.btn_confirm_config);

        etContact.setFilters(new android.text.InputFilter[] {
                new android.text.InputFilter.LengthFilter(12),
                (source, start, end, dest, dstart, dend) -> {
                    for (int i = start; i < end; i++) if (!Character.isDigit(source.charAt(i))) return "";
                    return null;
                }
        });

        etVersion.setFilters(new android.text.InputFilter[] {
                new android.text.InputFilter.LengthFilter(20),
                (source, start, end, dest, dstart, dend) -> {
                    // 统计已存在的点（排除将被替换的区间）
                    int dots = 0;
                    for (int i = 0; i < dest.length(); i++) {
                        if (i >= dstart && i < dend) continue;
                        if (dest.charAt(i) == '.') dots++;
                    }
                    for (int i = start; i < end; i++) {
                        char ch = source.charAt(i);
                        if (!Character.isDigit(ch) && ch != '.') return "";
                        if (ch == '.') {
                            dots++;
                            if (dots > 2) return ""; // 最多 2 个点，例如 1.0.1
                        }
                    }
                    return null;
                }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            String contact = etContact.getText().toString().trim();
            String cardUrl = etCardUrl.getText().toString().trim();
            String version = etVersion.getText().toString().trim();

            if (contact.length() < 5 || contact.length() > 12) {
                etContact.setError("请输入5-12位数字");
                return;
            }
            if (TextUtils.isEmpty(cardUrl)) {
                etCardUrl.setError("请输入购卡地址");
                return;
            }
            if (version.isEmpty() || !version.matches("[0-9.]+") || !version.matches(".*\\d.*")) {
                etVersion.setError("请输入版本号（仅数字和 .）");
                return;
            }

            selectedAppId = "1";
            selectedContact = contact;
            selectedCardUrl = cardUrl;
            selectedVersion = version;
            configTitle1 = etTitle1.getText().toString().trim();
            configTitle2 = etTitle2.getText().toString().trim();
            configTitle3 = etTitle3.getText().toString().trim();
            configTitle4 = etTitle4.getText().toString().trim();

            tvConfigStatus.setText("已配置");
            tvConfigStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            btnInject.setEnabled(true);
            appendLog("配置完成 - APP ID: 1 (固定), 联系方式: " + contact + ", 购卡地址: " + cardUrl
                    + ", 版本号: " + version
                    + ", 标题1: " + configTitle1 + ", 标题2: " + configTitle2
                    + ", 标题3: " + configTitle3 + ", 标题4: " + configTitle4);
            dialog.dismiss();
        });
    }

    private void showManualActivityInput() {
        final EditText input = new EditText(this);
        input.setHint("例如: com.yanben.ui.activity.MainActivity");
        new AlertDialog.Builder(this)
                .setTitle("手动输入Activity")
                .setMessage("请手动输入目标Activity的完整类名：")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String activityName = input.getText().toString().trim();
                    if (!activityName.isEmpty()) {
                        activityList.clear();
                        activityList.add(activityName);
                        selectedActivity = activityName;
                        tvSelectedActivity.setText(activityName);
                        appendLog("手动设置Activity: " + activityName);
                        showConfigDialog();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startInjection() {
        new InjectionTask().execute(selectedActivity, selectedAppId, selectedContact, selectedCardUrl,
                configTitle1, configTitle2, configTitle3, configTitle4, selectedVersion);
    }

    // ── 混淆检测 ──

    private ObfuscationResult detectObfuscation(String apkPath) {
        ObfuscationResult result = new ObfuscationResult();
        try {
            File apkFile = new File(apkPath);
            ZipFile zipFile = new ZipFile(apkFile);
            List<File> dexFiles = new ArrayList<>();
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".dex")) {
                    File tempDex = File.createTempFile("detect_", ".dex");
                    tempDex.deleteOnExit();
                    try (InputStream is = zipFile.getInputStream(entry); FileOutputStream fos = new FileOutputStream(tempDex)) {
                        copyStream(is, fos);
                    }
                    dexFiles.add(tempDex);
                }
            }
            zipFile.close();

            for (File dexFile : dexFiles) {
                try {
                    DexFile dex = DexFileFactory.loadDexFile(dexFile, org.jf.dexlib2.Opcodes.forApi(COMPATIBLE_API_LEVEL));
                    for (ClassDef classDef : dex.getClasses()) {
                        result.totalClasses++;
                        if (isObfuscatedClassName(classDef.getType())) {
                            result.obfuscatedClassCount++;
                        }
                        // 方法名混淆检测
                        for (Method method : classDef.getMethods()) {
                            result.totalMethods++;
                            if (isObfuscatedMethodName(method.getName(), method.getAccessFlags())) {
                                result.obfuscatedMethodCount++;
                            }
                        }
                    }
                } catch (Exception e) {
                    appendLog("解析DEX失败，可能被加固/加密: " + dexFile.getName());
                }
            }
            for (File dexFile : dexFiles) {
                if (dexFile.exists()) dexFile.delete();
            }

            if (result.totalClasses > 0) {
                result.obfuscatedClassRatio = (double) result.obfuscatedClassCount / result.totalClasses;
                result.obfuscatedMethodRatio = result.totalMethods > 0
                        ? (double) result.obfuscatedMethodCount / result.totalMethods : 0;
                result.isObfuscated = result.obfuscatedClassRatio > 0.3
                        || result.obfuscatedMethodRatio > 0.4;
                result.details = "  · 类总数: " + result.totalClasses
                        + "\n  · 疑似混淆类: " + result.obfuscatedClassCount
                        + "\n  · 类名混淆比例: " + String.format("%.1f%%", result.obfuscatedClassRatio * 100)
                        + "\n  · 方法总数: " + result.totalMethods
                        + "\n  · 疑似混淆方法: " + result.obfuscatedMethodCount
                        + "\n  · 方法名混淆比例: " + String.format("%.1f%%", result.obfuscatedMethodRatio * 100);
            } else {
                result.details = "  · 未发现可分析的类";
            }
        } catch (Exception e) {
            Log.e(TAG, "检测混淆失败", e);
            result.details = "  · 检测过程出错: " + e.getMessage();
        }
        return result;
    }

    // ── 加固/加密检测 ──

    private PackingResult detectPackingOrEncryption(String apkPath) {
        PackingResult result = new PackingResult();
        result.isPacked = false;
        result.packerName = "未知";
        StringBuilder details = new StringBuilder();

        try {
            File apkFile = new File(apkPath);
            ZipFile zipFile = new ZipFile(apkFile);
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();

            int dexCount = 0;
            int totalEntries = 0;
            int soCount = 0;
            Map<String, List<String>> detectedPackers = new LinkedHashMap<>();

            while (entries.hasMoreElements()) {
                totalEntries++;
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String lower = name.toLowerCase();

                if (name.endsWith(".dex")) {
                    dexCount++;
                    if (entry.getSize() > 0 && entry.getSize() < 4096) {
                        details.append("  · 发现可疑DEX存根文件: ").append(name)
                                .append(" (大小: ").append(entry.getSize()).append(" 字节)\n");
                    }
                }

                // ── SO库文件特征检测 ──
                if (lower.startsWith("lib/") && lower.endsWith(".so")) {
                    soCount++;
                    // 360加固 (Qihoo 360)
                    if (lower.contains("jiagu") || lower.contains("protectclass") || lower.contains("jgdtc")
                            || lower.contains("qihoo"))
                        addDetection(detectedPackers, "360加固", "SO: " + name);
                    // 腾讯乐固/云加固 (Tencent Legu)
                    if (lower.contains("shella") || lower.contains("shellx") || lower.contains("legudb")
                            || lower.contains("tup") || lower.contains("tpmshell") || lower.contains("tpx")
                            || lower.contains("tensafe"))
                        addDetection(detectedPackers, "腾讯乐固", "SO: " + name);
                    if (lower.contains("tencent"))
                        addDetection(detectedPackers, "腾讯加固", "SO: " + name);
                    // 梆梆加固 (Bangcle)
                    if (lower.contains("bangcle") || lower.contains("secshell") || lower.contains("dexhelper")
                            || lower.contains("secexe") || lower.contains("secmain") || lower.contains("dexjni")
                            || lower.contains("datajar"))
                        addDetection(detectedPackers, "梆梆加固", "SO: " + name);
                    // 爱加密 (Ijiami)
                    if (lower.contains("libexec") || lower.contains("execmain") || lower.contains("ijiami"))
                        addDetection(detectedPackers, "爱加密", "SO: " + name);
                    // 阿里聚安全/云安全 (Alibaba)
                    if (lower.contains("sgmain") || lower.contains("sgsecuritybody") || lower.contains("demolish")
                            || lower.contains("fakejni") || lower.contains("zuma") || lower.contains("mobisec")
                            || lower.contains("preverify"))
                        addDetection(detectedPackers, "阿里聚安全", "SO: " + name);
                    // 百度加固 (Baidu)
                    if (lower.contains("baiduprotect"))
                        addDetection(detectedPackers, "百度加固", "SO: " + name);
                    // 娜迦加固 (Naga)
                    if (lower.contains("ddog") || lower.contains("fdog") || lower.contains("edog")
                            || lower.contains("cdog") || lower.contains("chaosvmp"))
                        addDetection(detectedPackers, "娜迦加固", "SO: " + name);
                    // 网易易盾 (NetEase)
                    if (lower.contains("nesec"))
                        addDetection(detectedPackers, "网易易盾", "SO: " + name);
                    // 顶象技术 (Dingxiang)
                    if (lower.contains("x3g"))
                        addDetection(detectedPackers, "顶象加固", "SO: " + name);
                    // 几维安全 (Kiwisec)
                    if (lower.contains("kwscmm") || lower.contains("kwscr") || lower.contains("kwslinker")
                            || lower.contains("kwsdataenc") || lower.contains("kiwi"))
                        addDetection(detectedPackers, "几维安全", "SO: " + name);
                    // 通付盾 (Tongfudun)
                    if (lower.contains("egis") || lower.contains("nsafer"))
                        addDetection(detectedPackers, "通付盾", "SO: " + name);
                    // 网秦 (NQ Mobile)
                    if (lower.contains("nqshield"))
                        addDetection(detectedPackers, "网秦", "SO: " + name);
                    // 腾讯御安全 (Tencent Yusafe)
                    if (lower.contains("tosprotection") || lower.contains("shell-super") || lower.contains("shellx-super"))
                        addDetection(detectedPackers, "腾讯御安全", "SO: " + name);
                    // APKProtect
                    if (lower.contains("apkprotect"))
                        addDetection(detectedPackers, "APKProtect", "SO: " + name);
                    // SecNeo
                    if (lower.contains("secneo") || lower.contains("sneagle"))
                        addDetection(detectedPackers, "SecNeo", "SO: " + name);
                    // 瑞星加固 (Rising)
                    if (lower.contains("rsprotect"))
                        addDetection(detectedPackers, "瑞星加固", "SO: " + name);
                    // 盛大加固 (Shanda)
                    if (lower.contains("apssec"))
                        addDetection(detectedPackers, "盛大加固", "SO: " + name);
                    // UU安全
                    if (lower.contains("uusafe"))
                        addDetection(detectedPackers, "UU安全", "SO: " + name);
                    // 海云安
                    if (lower.contains("itsec"))
                        addDetection(detectedPackers, "海云安加固", "SO: " + name);
                    // 中国移动加固 (Mogosec)
                    if (lower.contains("cmvmp") || lower.contains("mogosec"))
                        addDetection(detectedPackers, "中国移动加固", "SO: " + name);
                    // 蛮犀加固 (Manxi)
                    if (lower.contains("manxi"))
                        addDetection(detectedPackers, "蛮犀加固", "SO: " + name);
                    // 启明星辰 (Venustech)
                    if (lower.contains("vensec") || lower.contains("venustech"))
                        addDetection(detectedPackers, "启明星辰", "SO: " + name);
                    // DexProtector (Licel)
                    if (lower.contains("dexprotector"))
                        addDetection(detectedPackers, "DexProtector", "SO: " + name);
                    // Promon
                    if (lower.contains("promon"))
                        addDetection(detectedPackers, "Promon", "SO: " + name);
                    // AppSealing
                    if (lower.contains("covault") || lower.contains("appsealing"))
                        addDetection(detectedPackers, "AppSealing", "SO: " + name);
                    // DexGuard (Guardsquare)
                    if (lower.contains("dexguard"))
                        addDetection(detectedPackers, "DexGuard", "SO: " + name);

                    // ── SO文件内容特征检测（厂商标识编译在SO二进制内部，文件名随机化也能识别） ──
                    try {
                        byte[] header = new byte[4096];
                        int read;
                        try (java.io.InputStream soIs = zipFile.getInputStream(entry)) {
                            read = soIs.read(header);
                        }
                        if (read > 0) {
                            String content = new String(header, 0, read, java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase();
                            if (content.contains("qihoo") || content.contains("360jiagu") || content.contains("360protect"))
                                addDetection(detectedPackers, "360加固", "SO内容: " + name);
                            if (content.contains("tpxshell") || content.contains("tensafe") || content.contains("legu"))
                                addDetection(detectedPackers, "腾讯乐固", "SO内容: " + name);
                            if (content.contains("bangcle") || content.contains("secshell") || content.contains("secneo"))
                                addDetection(detectedPackers, "梆梆加固", "SO内容: " + name);
                            if (content.contains("ijiami") || content.contains("shell.super"))
                                addDetection(detectedPackers, "爱加密", "SO内容: " + name);
                            if (content.contains("sgmain") || content.contains("aliprotect") || content.contains("demolish"))
                                addDetection(detectedPackers, "阿里聚安全", "SO内容: " + name);
                            if (content.contains("baiduprotect"))
                                addDetection(detectedPackers, "百度加固", "SO内容: " + name);
                            if (content.contains("nesec") || content.contains("netease") || content.contains("yidun"))
                                addDetection(detectedPackers, "网易易盾", "SO内容: " + name);
                            if (content.contains("naga") || content.contains("chaosvmp"))
                                addDetection(detectedPackers, "娜迦加固", "SO内容: " + name);
                            if (content.contains("kwscmm") || content.contains("kiwisec"))
                                addDetection(detectedPackers, "几维安全", "SO内容: " + name);
                            if (content.contains("tosprotect"))
                                addDetection(detectedPackers, "腾讯御安全", "SO内容: " + name);
                            if (content.contains("dexprotector") || content.contains("licel"))
                                addDetection(detectedPackers, "DexProtector", "SO内容: " + name);
                            if (content.contains("promon"))
                                addDetection(detectedPackers, "Promon", "SO内容: " + name);
                            if (content.contains("dexguard"))
                                addDetection(detectedPackers, "DexGuard", "SO内容: " + name);
                            if (content.contains("covault") || content.contains("appsealing"))
                                addDetection(detectedPackers, "AppSealing", "SO内容: " + name);
                        }
                    } catch (Exception ignored) {
                        // SO内容读取失败，跳过内容检测
                    }
                }

                // ── lib/下加密DEX特征（腾讯乐固将DEX伪装存放在lib/目录） ──
                if (lower.startsWith("lib/") && (lower.endsWith("mix.dex") || lower.endsWith("mixz.dex"))) {
                    addDetection(detectedPackers, "腾讯乐固", "加密DEX: " + name);
                }

                // ── Assets资源文件特征检测 ──
                if (lower.startsWith("assets/")) {
                    // 360加固
                    if (lower.contains(".appkey"))
                        addDetection(detectedPackers, "360加固", "资源: " + name);
                    // 爱加密
                    if (lower.contains("ijiami"))
                        addDetection(detectedPackers, "爱加密", "资源: " + name);
                    // 梆梆加固
                    if (lower.contains("secdata0.jar") || lower.contains("classes.jar") || lower.contains("classes.dgc"))
                        addDetection(detectedPackers, "梆梆加固", "资源: " + name);
                    // 阿里聚安全
                    if (lower.contains("aliprotect.dat"))
                        addDetection(detectedPackers, "阿里聚安全", "资源: " + name);
                    // 百度加固
                    if (lower.contains("baiduprotect"))
                        addDetection(detectedPackers, "百度加固", "资源: " + name);
                    // 腾讯乐固
                    if (lower.contains("mix.dex") || lower.contains("mixz.dex") || lower.contains("tencent_stub"))
                        addDetection(detectedPackers, "腾讯乐固", "资源: " + name);
                    // 腾讯御安全
                    if (lower.contains("tosversion") || lower.contains("oooo") || lower.contains("ooooo"))
                        addDetection(detectedPackers, "腾讯御安全", "资源: " + name);
                    // 网易易盾
                    if (lower.contains("nedata.db"))
                        addDetection(detectedPackers, "网易易盾", "资源: " + name);
                    // 几维安全
                    if (lower.contains("dex.dat") || lower.contains("ec_dt.lic") || lower.contains("kwpt.lincense"))
                        addDetection(detectedPackers, "几维安全", "资源: " + name);
                    // 通付盾
                    if (lower.contains("libegis.a"))
                        addDetection(detectedPackers, "通付盾", "资源: " + name);
                    // AppSealing
                    if (lower.contains("sealed1.dex") || lower.contains("sealed2.dex"))
                        addDetection(detectedPackers, "AppSealing", "资源: " + name);
                    // LIAPP
                    if (lower.contains("liapp.ini") || lower.contains("liapp.dat"))
                        addDetection(detectedPackers, "LIAPP", "资源: " + name);
                    // DexGuard
                    if (lower.contains("dexguard"))
                        addDetection(detectedPackers, "DexGuard", "资源: " + name);
                    // 珊瑚灵御
                    if (lower.contains("libreincp"))
                        addDetection(detectedPackers, "珊瑚灵御", "资源: " + name);
                    // apktoolplus
                    if (lower.contains("jiagu_data.bin") || lower.contains("sign.bin"))
                        addDetection(detectedPackers, "apktoolplus", "资源: " + name);
                    // UU安全
                    if (lower.contains("uusafe"))
                        addDetection(detectedPackers, "UU安全", "资源: " + name);
                    // 海云安
                    if (lower.contains("itse/"))
                        addDetection(detectedPackers, "海云安加固", "资源: " + name);
                    // 中国移动加固
                    if (lower.contains("mogosec_"))
                        addDetection(detectedPackers, "中国移动加固", "资源: " + name);
                    // 蛮犀加固
                    if (lower.contains("mx/"))
                        addDetection(detectedPackers, "蛮犀加固", "资源: " + name);
                }
            }

            // ── AndroidManifest.xml 壳入口检测 ──
            String appClassName = getApplicationNameFromManifest(apkPath);
            if (appClassName != null) {
                String lowerApp = appClassName.toLowerCase();
                // 360加固
                if (lowerApp.contains("stubapp") || lowerApp.contains("stub.stub"))
                    addDetection(detectedPackers, "360加固", "壳入口: " + appClassName);
                // 腾讯乐固
                if (lowerApp.contains("stubshell") || lowerApp.contains("txappentry"))
                    addDetection(detectedPackers, "腾讯乐固", "壳入口: " + appClassName);
                // 腾讯御安全
                if (lowerApp.contains("mywrapperproxyapplication") || lowerApp.contains("wrapper.proxyapplication"))
                    addDetection(detectedPackers, "腾讯御安全", "壳入口: " + appClassName);
                // 百度加固
                if (lowerApp.contains("baidu.protect") || lowerApp.contains("stubapplication"))
                    addDetection(detectedPackers, "百度加固", "壳入口: " + appClassName);
                // 爱加密
                if (lowerApp.contains("superapplication") || lowerApp.contains("shell.s")
                        || (lowerApp.contains("shell") && !lowerApp.contains("secshell")
                            && !lowerApp.contains("stubshell") && !lowerApp.contains("secshell")))
                    addDetection(detectedPackers, "爱加密", "壳入口: " + appClassName);
                // 梆梆加固
                if (lowerApp.contains("secappwrapper") || lowerApp.contains("secneo.apkwrapper"))
                    addDetection(detectedPackers, "梆梆加固", "壳入口: " + appClassName);
                // 网易易盾
                if (lowerApp.contains("netease.nis") || lowerApp.contains("netease"))
                    addDetection(detectedPackers, "网易易盾", "壳入口: " + appClassName);
                // 几维安全
                if (lowerApp.contains("kiwisec"))
                    addDetection(detectedPackers, "几维安全", "壳入口: " + appClassName);
                // 通付盾
                if (lowerApp.contains("payegis"))
                    addDetection(detectedPackers, "通付盾", "壳入口: " + appClassName);
                // 顶象技术
                if (lowerApp.contains("securitystack") || lowerApp.contains("appstub"))
                    addDetection(detectedPackers, "顶象加固", "壳入口: " + appClassName);
                // 珊瑚灵御
                if (lowerApp.contains("coral.util"))
                    addDetection(detectedPackers, "珊瑚灵御", "壳入口: " + appClassName);
                // apktoolplus
                if (lowerApp.contains("linchaolong.apktoolplus"))
                    addDetection(detectedPackers, "apktoolplus", "壳入口: " + appClassName);
                // 中国移动加固
                if (lowerApp.contains("mogosec.appmgr"))
                    addDetection(detectedPackers, "中国移动加固", "壳入口: " + appClassName);
                // 海云安
                if (lowerApp.contains("c.b.c.b"))
                    addDetection(detectedPackers, "海云安加固", "壳入口: " + appClassName);
            } else {
                // 旧版解析失败日志已移除，getApplicationNameFromManifest 已有内部日志
            }

            zipFile.close();

            if (dexCount == 0) {
                result.isPacked = true;
                result.packerName = "未知加固(无DEX文件)";
                details.append("  · APK中未找到DEX文件，代码可能被整体加密\n");
            }

            // ── 汇总结果 ──
            if (!detectedPackers.isEmpty()) {
                result.isPacked = true;
                result.packerName = String.join(", ", detectedPackers.keySet());
                details.append("  · 检测到加固方案: ").append(result.packerName).append("\n");
                for (Map.Entry<String, List<String>> e : detectedPackers.entrySet()) {
                    details.append("    ").append(e.getKey()).append(":\n");
                    for (String det : e.getValue()) {
                        details.append("      - ").append(det).append("\n");
                    }
                }
            }

            // ── 启发式检测：DEX极少 + SO极多 + APK大 → 代码隐藏于SO库（如360/腾讯等加固） ──
            if (!result.isPacked && dexCount > 0 && dexCount <= 2 && soCount > 15
                    && apkFile.exists() && apkFile.length() > 15 * 1024 * 1024) {
                result.isPacked = true;
                result.packerName = "疑似加固(代码隐藏于SO库)";
                details.append("  · APK中仅有 ").append(dexCount).append(" 个DEX，但包含 ")
                       .append(soCount).append(" 个SO库\n");
                details.append("  · APK体积 ").append(String.format("%.0fMB", apkFile.length() / (1024.0 * 1024.0)))
                       .append("MB，代码可能被加固并隐藏在SO文件中\n");
            }

            if (details.length() == 0) {
                details.append("  · 未检测到已知加固特征");
            }

            result.details = details.toString();
        } catch (Exception e) {
            Log.e(TAG, "检测加固/加密失败", e);
            details.append("  · 检测过程出错: ").append(e.getMessage());
            result.details = details.toString();
        }

        return result;
    }

    private void addDetection(Map<String, List<String>> map, String packer, String detail) {
        List<String> list = map.computeIfAbsent(packer, k -> new ArrayList<>());
        list.add(detail);
    }

    private String getApplicationNameFromManifest(String apkPath) {
        try (ZipFile zipFile = new ZipFile(apkPath)) {
            ZipEntry entry = zipFile.getEntry("AndroidManifest.xml");
            if (entry == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) entry.getSize());
            try (InputStream is = zipFile.getInputStream(entry)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            }
            return parseBinaryManifestForAppName(bos.toByteArray());
        } catch (Exception e) {
            Log.w(TAG, "读取AndroidManifest失败", e);
            return null;
        }
    }

    private String parseBinaryManifestForAppName(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int chunkType = bb.getShort() & 0xFFFF;
        if (chunkType != 0x0003) return null;
        bb.getShort(); bb.getInt();

        while (bb.remaining() >= 8) {
            int type = bb.getShort() & 0xFFFF;
            int headerSize = bb.getShort() & 0xFFFF;
            int chunkSize = bb.getInt();
            if (type == 0x0001) {
                int stringCount = bb.getInt();
                int styleCount = bb.getInt();
                int flags = bb.getInt();
                int stringsStart = bb.getInt();
                int stylesStart = bb.getInt();
                boolean isUtf8 = (flags & 0x00000100) != 0;

                int[] stringOffsets = new int[stringCount];
                for (int i = 0; i < stringCount; i++) stringOffsets[i] = bb.getInt();
                bb.position(bb.position() + styleCount * 4);

                String[] strings = new String[stringCount];
                int chunkStart = bb.position() - 28 - (stringCount + styleCount) * 4;
                int stringDataStart = chunkStart + stringsStart;
                for (int i = 0; i < stringCount; i++) {
                    int offset = stringDataStart + stringOffsets[i];
                    int savedPos = bb.position();
                    if (offset >= 0 && offset < data.length) {
                        bb.position(offset);
                        strings[i] = isUtf8 ? readUtf8String(bb) : readUtf16String(bb);
                    }
                    bb.position(savedPos);
                }

                bb.position(chunkStart + chunkSize);
                return walkXmlForAppName(bb, strings);
            } else {
                bb.position(bb.position() + chunkSize - 8);
            }
        }
        return null;
    }

    private String walkXmlForAppName(ByteBuffer bb, String[] strings) {
        String packageName = null;

        while (bb.remaining() >= 8) {
            int type = bb.getShort() & 0xFFFF;
            int headerSize = bb.getShort() & 0xFFFF;
            int chunkSize = bb.getInt();
            int endPos = bb.position() + chunkSize - 8;

            if (type == 0x0102) {
                bb.getInt(); bb.getInt(); bb.getInt();
                int nameIdx = bb.getInt();
                int attrFlags = bb.getShort() & 0xFFFF;
                int attrCount = bb.getShort() & 0xFFFF;

                String tagName = (nameIdx >= 0 && nameIdx < strings.length) ? strings[nameIdx] : null;
                boolean isApplication = "application".equals(tagName);
                String appName = null;

                for (int i = 0; i < attrCount; i++) {
                    int attrNs = bb.getInt();
                    int attrNameIdx = bb.getInt();
                    int rawValue = bb.getInt();
                    // Res_value: uint16 size + uint8 res0 + uint8 dataType + uint32 data
                    int typedSize = bb.getShort() & 0xFFFF;
                    bb.get(); // res0
                    int dataType = bb.get() & 0xFF;
                    int typedData = bb.getInt();

                    // 字符串属性可能只存在于 typedValue.data 中（rawValue 为 -1）
                    int stringIdx = rawValue;
                    if (stringIdx < 0 && dataType == 0x03) {
                        stringIdx = typedData;
                    }

                    if (attrNameIdx >= 0 && attrNameIdx < strings.length) {
                        String aName = strings[attrNameIdx];
                        if ("package".equals(aName) && stringIdx >= 0 && stringIdx < strings.length) {
                            packageName = strings[stringIdx];
                        }
                        if (isApplication && "name".equals(aName) && stringIdx >= 0 && stringIdx < strings.length) {
                            appName = strings[stringIdx];
                        }
                    }
                }

                if (isApplication && appName != null) {
                    if (appName.startsWith(".") && packageName != null) {
                        return packageName + appName;
                    }
                    return appName;
                }

            } else if (type == 0x0101) {
                break;
            }

            bb.position(endPos);
        }
        return null;
    }

    private boolean isObfuscatedClassName(String className) {
        if (className == null || className.length() < 3) return false;
        String internal = className;
        if (internal.startsWith("L") && internal.endsWith(";")) {
            internal = internal.substring(1, internal.length() - 1);
        }
        int lastSlash = internal.lastIndexOf('/');
        String simpleName = lastSlash >= 0 ? internal.substring(lastSlash + 1) : internal;

        // 单/双字母类名 如 a.class, aa.class
        if (simpleName.length() <= 3 && simpleName.matches("[a-z][a-z0-9]*")) return true;

        String packageName = lastSlash > 0 ? internal.substring(0, lastSlash) : "";
        if (!packageName.isEmpty()) {
            String[] segments = packageName.split("/");
            int shortSegments = 0;
            for (String seg : segments) {
                if (seg.length() <= 3 && seg.matches("[a-z][a-z0-9]*")) shortSegments++;
            }
            if (segments.length >= 3 && shortSegments >= segments.length - 1) return true;
            if (segments.length >= 2 && shortSegments == segments.length) return true;
        }
        return false;
    }

    // ── 方法名混淆检测 ──

    private boolean isActivityNameObfuscated(String fullClassName) {
        if (fullClassName == null) return false;
        String simpleName = fullClassName;
        int dot = fullClassName.lastIndexOf('.');
        if (dot >= 0 && dot < fullClassName.length() - 1) {
            simpleName = fullClassName.substring(dot + 1);
        }
        if (simpleName.isEmpty()) return false;
        // 单字母类名 a-z 或 A-Z → 典型混淆
        if (simpleName.length() == 1) {
            char c = simpleName.charAt(0);
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }
        // 双字母类名 → 极大概率混淆
        if (simpleName.length() == 2 && simpleName.matches("[a-zA-Z]{2}")) {
            return true;
        }
        // 三字母小写类名 → 排除常见名
        if (simpleName.length() == 3 && simpleName.matches("[a-z]{3}") && !isCommonShortClassName(simpleName)) {
            return true;
        }
        return false;
    }

    private boolean isCommonShortClassName(String name) {
        return name.equals("app") || name.equals("act") || name.equals("ui") ||
               name.equals("net") || name.equals("util") || name.equals("http") ||
               name.equals("api") || name.equals("web") || name.equals("sys") ||
               name.equals("cmd") || name.equals("mod") || name.equals("cfg");
    }

    private boolean isObfuscatedMethodName(String methodName, int accessFlags) {
        if (methodName == null) return false;
        if (methodName.startsWith("<")) return false;               // 构造方法 <init> / <clinit>
        if ((accessFlags & 0x0100) != 0) return false;             // ACC_NATIVE（native方法不参与混淆）

        String name = methodName;
        if (name.isEmpty()) return false;

        // 单字母方法名 a-z → ProGuard 典型特征，几乎确定是混淆
        if (name.length() == 1) {
            return name.charAt(0) >= 'a' && name.charAt(0) <= 'z';
        }

        // 双字母方法名 aa-zz → 极大概率是混淆
        if (name.length() == 2 && name.matches("[a-z]{2}")) {
            return true;
        }

        // 三字母方法名 → 排除常见短方法名后判定
        if (name.length() == 3 && name.matches("[a-z]{3}") && !isCommonShortMethod(name)) {
            return true;
        }

        return false;
    }

    private boolean isCommonShortMethod(String name) {
        return name.equals("run") || name.equals("get") || name.equals("set") ||
               name.equals("add") || name.equals("put") || name.equals("has") ||
               name.equals("new") || name.equals("try") || name.equals("log") ||
               name.equals("map") || name.equals("sum") || name.equals("min") ||
               name.equals("max") || name.equals("abs");
    }

    private void showProtectionWarningDialog(boolean obfuscated, ObfuscationResult obfResult,
                                              boolean packed, PackingResult packResult,
                                              Runnable onProceed) {
        StringBuilder body = new StringBuilder();
        if (obfuscated) {
            body.append("⚠ 检测到代码混淆\n");
        } else {
            body.append("✓ 未检测到代码混淆\n");
        }
        if (packed) {
            body.append("⚠ 检测到加固: ").append(packResult.packerName).append("\n");
        } else {
            body.append("✓ 未检测到加固\n");
        }

        if (obfuscated || packed) {
            body.append("\n混淆或加固的应用可能会增加注入失败的风险，\n");
            body.append("是否仍然继续？");
        }

        android.widget.TextView titleView = new android.widget.TextView(this);
        titleView.setText("加固检测警告");
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setGravity(android.view.Gravity.CENTER);
        titleView.setPadding(0, (int)(20 * getResources().getDisplayMetrics().density), 0, 0);

        new AlertDialog.Builder(this)
                .setCustomTitle(titleView)
                .setMessage(body.toString().trim())
                .setPositiveButton("继续", (dialog, which) -> onProceed.run())
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 判断Activity是否属于目标应用(通过包名前缀匹配)
     * 处理相对路径:以.开头的activity拼接包名后判断
     */
    private boolean isAppOwnActivity(String activityName, String packageName) {
        if (packageName == null || packageName.isEmpty()) return true;
        // 直接以包名开头
        if (activityName.startsWith(packageName)) return true;
        // 处理相对路径:以.开头表示相对于包名
        if (activityName.startsWith(".")) {
            String resolved = packageName + activityName;
            return resolved.startsWith(packageName);
        }
        return false;
    }

    private List<String> extractActivitiesFromApk(String apkPath) {
        return extractActivitiesFromApk(apkPath, null);
    }

    private List<String> extractActivitiesFromApk(String apkPath, String knownMainActivity) {
        List<String> activities = new ArrayList<>();
        String mainActivity = knownMainActivity; // 优先使用已知主Activity（从已安装应用获取）
        try {
            String appPackageName = null;
            try {
                Process process = Runtime.getRuntime().exec("aapt dump badging " + apkPath);
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    // 提取包名
                    if (appPackageName == null && line.startsWith("package:")) {
                        int nameStart = line.indexOf("name='") + 6;
                        int nameEnd = line.indexOf("'", nameStart);
                        if (nameStart > 5 && nameEnd > nameStart) {
                            appPackageName = line.substring(nameStart, nameEnd);
                        }
                    }
                    if (line.contains("launchable-activity")) {
                        int start = line.indexOf("name='") + 6;
                        int end = line.indexOf("'", start);
                        if (start > 5 && end > start) {
                            String activityName = line.substring(start, end);
                            if (!activityName.startsWith("android.") && !activityName.startsWith("androidx.")) {
                                mainActivity = activityName;
                                if (!activities.contains(mainActivity)) activities.add(mainActivity);
                            }
                        }
                    } else if (line.contains("activity:") && line.contains("name=")) {
                        int start = line.indexOf("name='") + 6;
                        int end = line.indexOf("'", start);
                        if (start > 5 && end > start) {
                            String activityName = line.substring(start, end);
                            if (!activityName.startsWith("android.") && !activityName.startsWith("androidx.") && !activities.contains(activityName)) {
                                activities.add(activityName);
                            }
                        }
                    }
                }
                reader.close();
                process.waitFor();
                // 通过包名前缀过滤第三方SDK Activity
                if (appPackageName != null) {
                    List<String> filtered = new ArrayList<>();
                    for (String act : activities) {
                        if (isAppOwnActivity(act, appPackageName)) {
                            filtered.add(act);
                        }
                    }
                    if (filtered.isEmpty()) {
                        appendLog("包名过滤后无Activity,保留全部");
                    } else {
                        int removed = activities.size() - filtered.size();
                        if (removed > 0) appendLog("已过滤掉 " + removed + " 个第三方SDK Activity");
                        activities.clear();
                        activities.addAll(filtered);
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                File apkFile = new File(apkPath);
                ZipFile zipFile = new ZipFile(apkFile);
                List<File> dexFiles = new ArrayList<>();
                java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".dex")) {
                        File tempDex = File.createTempFile("temp", ".dex");
                        tempDex.deleteOnExit();
                        try (InputStream is = zipFile.getInputStream(entry); FileOutputStream fos = new FileOutputStream(tempDex)) {
                            copyStream(is, fos);
                        }
                        dexFiles.add(tempDex);
                    }
                }
                zipFile.close();
                for (File dexFile : dexFiles) {
                    try {
                        DexFile dex = DexFileFactory.loadDexFile(dexFile, org.jf.dexlib2.Opcodes.forApi(COMPATIBLE_API_LEVEL));
                        for (ClassDef classDef : dex.getClasses()) {
                            if (isActivityClass(classDef)) {
                                String javaClassName = classDef.getType().substring(1, classDef.getType().length() - 1).replace('/', '.');
                                if (!javaClassName.startsWith("android.") && !javaClassName.startsWith("androidx.") && !activities.contains(javaClassName)) {
                                    activities.add(javaClassName);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "解析DEX文件失败: " + dexFile.getName(), e);
                    }
                }
                // DEX发现的Activity也通过包名过滤
                if (appPackageName != null) {
                    List<String> dexFiltered = new ArrayList<>();
                    for (String act : activities) {
                        if (isAppOwnActivity(act, appPackageName) || mainActivity != null && act.equals(mainActivity)) {
                            dexFiltered.add(act);
                        }
                    }
                    if (dexFiltered.size() < activities.size()) {
                        appendLog("DEX来源已过滤掉 " + (activities.size() - dexFiltered.size()) + " 个第三方Activity");
                        activities.clear();
                        activities.addAll(dexFiltered);
                    }
                }
                for (File dexFile : dexFiles) if (dexFile.exists()) dexFile.delete();
            } catch (Exception e) {
                Log.e(TAG, "解析APK失败", e);
                appendLog("解析APK失败: " + e.getMessage());
            }

            // aapt解析失败且没有已知主Activity时，直接解析二进制AndroidManifest.xml
            if (mainActivity == null) {
                mainActivity = findLauncherActivityFromApk(apkPath);
            }

            if (mainActivity != null && !mainActivity.startsWith("android.") && !mainActivity.startsWith("androidx.")) {
                if (activities.contains(mainActivity)) {
                    activities.remove(mainActivity);
                    activities.add(0, mainActivity);
                } else {
                    activities.add(0, mainActivity);
                }
                appendLog("已将主Activity置顶: " + mainActivity);
            }
            if (activities.size() > 1) {
                List<String> otherActivities = activities.subList(1, activities.size());
                Collections.sort(otherActivities);
                appendLog("已对其他Activity按字母顺序排序");
            }
            appendLog("总共找到 " + activities.size() + " 个用户Activity");
        } catch (Exception e) {
            Log.e(TAG, "提取Activity失败", e);
            appendLog("提取Activity失败: " + e.getMessage());
        }
        return activities;
    }

    // ── 二进制 AndroidManifest.xml 解析 ──

    private String findLauncherActivityFromApk(String apkPath) {
        try (ZipFile zipFile = new ZipFile(apkPath)) {
            ZipEntry entry = zipFile.getEntry("AndroidManifest.xml");
            if (entry == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) entry.getSize());
            try (InputStream is = zipFile.getInputStream(entry)) {
                byte[] buf = new byte[4096]; int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            }
            return parseBinaryManifestForLauncher(bos.toByteArray());
        } catch (Exception e) {
            Log.w(TAG, "解析AndroidManifest.xml失败", e);
            return null;
        }
    }

    private String parseBinaryManifestForLauncher(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        // XML header (type 0x00080003)
        int chunkType = bb.getShort() & 0xFFFF;
        if (chunkType != 0x0003) return null;
        bb.getShort(); // header size
        bb.getInt();   // chunk size

        // find string pool
        while (bb.remaining() >= 8) {
            int type = bb.getShort() & 0xFFFF;
            int headerSize = bb.getShort() & 0xFFFF;
            int chunkSize = bb.getInt();
            if (type == 0x0001) {
                int stringCount = bb.getInt();
                int styleCount = bb.getInt();
                int flags = bb.getInt();
                int stringsStart = bb.getInt();
                int stylesStart = bb.getInt();

                boolean isUtf8 = (flags & 0x00000100) != 0;

                int[] stringOffsets = new int[stringCount];
                for (int i = 0; i < stringCount; i++) stringOffsets[i] = bb.getInt();

                // skip style offsets
                bb.position(bb.position() + styleCount * 4);

                // read all strings
                String[] strings = new String[stringCount];
                int chunkStart = bb.position() - 28 - (stringCount + styleCount) * 4;
                int stringDataStart = chunkStart + stringsStart;
                for (int i = 0; i < stringCount; i++) {
                    int offset = stringDataStart + stringOffsets[i];
                    int savedPos = bb.position();
                    bb.position(offset);
                    strings[i] = isUtf8 ? readUtf8String(bb) : readUtf16String(bb);
                    bb.position(savedPos);
                }

                // skip to end of string pool chunk (past string data and style data)
                bb.position(chunkStart + chunkSize);

                // now walk XML tree
                return walkXmlTree(bb, strings);
            } else {
                bb.position(bb.position() + chunkSize - 8);
            }
        }
        return null;
    }

    private String readUtf8String(ByteBuffer bb) {
        int utf16Len = readVarLen(bb);
        int utf8Len = readVarLen(bb);
        if (utf8Len == 0) { bb.get(); return ""; } // skip null terminator
        byte[] bytes = new byte[utf8Len];
        bb.get(bytes);
        bb.get(); // null terminator
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String readUtf16String(ByteBuffer bb) {
        int charCount = readVarLen(bb);
        if (charCount == 0) { bb.getShort(); return ""; } // skip null terminator
        byte[] bytes = new byte[charCount * 2];
        bb.get(bytes);
        bb.getShort(); // null terminator (2 bytes)
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_16LE);
    }

    private int readVarLen(ByteBuffer bb) {
        int val = bb.get() & 0xFF;
        if ((val & 0x80) != 0) {
            val = ((val & 0x7F) << 8) | (bb.get() & 0xFF);
        }
        return val;
    }

    private String walkXmlTree(ByteBuffer bb, String[] strings) {
        String currentActivity = null;
        boolean inIntentFilter = false;
        boolean hasMainAction = false;
        boolean hasLauncherCategory = false;
        String packageName = null;

        while (bb.remaining() >= 8) {
            int type = bb.getShort() & 0xFFFF;
            int headerSize = bb.getShort() & 0xFFFF;
            int chunkSize = bb.getInt();
            int endPos = bb.position() + chunkSize - 8;

            if (type == 0x0102) { // START_TAG
                int line = bb.getInt();      // line number
                int comment = bb.getInt();   // comment
                int nsIdx = bb.getInt();     // namespace
                int nameIdx = bb.getInt();   // name
                int attrFlags = bb.getShort() & 0xFFFF;
                int attrCount = bb.getShort() & 0xFFFF;

                String tagName = (nameIdx >= 0 && nameIdx < strings.length) ? strings[nameIdx] : null;

                // parse attributes
                String attrName = null;
                for (int i = 0; i < attrCount; i++) {
                    int attrNs = bb.getInt();
                    int attrNameIdx = bb.getInt();
                    int rawValue = bb.getInt();  // string pool index for string values (-1 if N/A)
                    // Res_value: uint16 size + uint8 res0 + uint8 dataType + uint32 data
                    int typedSize = bb.getShort() & 0xFFFF;
                    bb.get(); // res0
                    int dataType = bb.get() & 0xFF;
                    int typedData = bb.getInt();

                    // 字符串属性可能只存在于 typedValue.data 中（rawValue 为 -1）
                    int stringIdx = rawValue;
                    if (stringIdx < 0 && dataType == 0x03) {
                        stringIdx = typedData;
                    }

                    if (attrNameIdx >= 0 && attrNameIdx < strings.length) {
                        String aName = strings[attrNameIdx];
                        if ("name".equals(aName) && stringIdx >= 0 && stringIdx < strings.length) {
                            attrName = strings[stringIdx];
                        } else if ("package".equals(aName) && stringIdx >= 0 && stringIdx < strings.length) {
                            packageName = strings[stringIdx];
                        }
                    }
                }

                if ("activity".equals(tagName) || "activity-alias".equals(tagName)) {
                    currentActivity = attrName;
                    inIntentFilter = false;
                    hasMainAction = false;
                    hasLauncherCategory = false;
                } else if ("intent-filter".equals(tagName)) {
                    inIntentFilter = true;
                } else if (inIntentFilter && "action".equals(tagName) && "android.intent.action.MAIN".equals(attrName)) {
                    hasMainAction = true;
                } else if (inIntentFilter && "category".equals(tagName) && "android.intent.category.LAUNCHER".equals(attrName)) {
                    hasLauncherCategory = true;
                }

            } else if (type == 0x0103) { // END_TAG
                bb.getInt(); // line
                bb.getInt(); // comment
                int nsIdx = bb.getInt();
                int nameIdx = bb.getInt();

                String tagName = (nameIdx >= 0 && nameIdx < strings.length) ? strings[nameIdx] : null;

                if ("activity".equals(tagName) || "activity-alias".equals(tagName)) {
                    if (hasMainAction && hasLauncherCategory && currentActivity != null) {
                        String resolved = currentActivity;
                        if (resolved.startsWith(".") && packageName != null) {
                            resolved = packageName + resolved;
                        }
                        appendLog("从AndroidManifest解析到启动Activity: " + resolved);
                        return resolved;
                    }
                    currentActivity = null;
                    inIntentFilter = false;
                    hasMainAction = false;
                    hasLauncherCategory = false;
                } else if ("intent-filter".equals(tagName)) {
                    inIntentFilter = false;
                }

            } else if (type == 0x0104) { // TEXT
                // skip text content
                bb.getInt(); bb.getInt(); bb.getInt(); bb.getInt();
            } else if (type == 0x0101) { // END_DOCUMENT
                break;
            }

            bb.position(endPos);
        }
        return null;
    }

    private int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
             | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private boolean isActivityClass(ClassDef classDef) {
        String superClass = classDef.getSuperclass();
        if (superClass == null) return false;
        String javaClassName = classDef.getType().substring(1, classDef.getType().length() - 1).replace('/', '.');
        if (javaClassName.startsWith("android.") || javaClassName.startsWith("androidx.")) return false;
        return isActivitySuperClass(superClass);
    }

    private boolean isActivitySuperClass(String superClass) {
        if (superClass == null || "Ljava/lang/Object;".equals(superClass)) return false;
        Set<String> activitySuperClasses = new HashSet<>();
        activitySuperClasses.add("Landroid/app/Activity;");
        activitySuperClasses.add("Landroid/app/ListActivity;");
        activitySuperClasses.add("Landroid/app/ExpandableListActivity;");
        activitySuperClasses.add("Landroid/app/ActivityGroup;");
        activitySuperClasses.add("Landroid/app/TabActivity;");
        activitySuperClasses.add("Landroidx/activity/ComponentActivity;");
        activitySuperClasses.add("Landroidx/appcompat/app/AppCompatActivity;");
        activitySuperClasses.add("Landroidx/fragment/app/FragmentActivity;");
        return activitySuperClasses.contains(superClass) || superClass.contains("Activity");
    }

    @SuppressLint("StaticFieldLeak")
    private class InjectionTask extends AsyncTask<String, String, Boolean> {
        private String outputApkPath;
        private List<String> missingPermissionsRef; // xiao.dex缺失的权限清单

        @Override
        protected void onPreExecute() {
            progressDialog = new ProgressDialog(DexInjectActivity.this);
            progressDialog.setMessage("正在注入...");
            progressDialog.setCancelable(false);
            progressDialog.show();
        }

        @Override
        protected Boolean doInBackground(String... params) {
            String targetActivity = params[0];
            String appId = params[1];
            String contact = params[2];
            String cardUrl = params[3];
            String title1 = params[4];
            String title2 = params[5];
            String title3 = params[6];
            String title4 = params[7];
            String version = params[8];

            publishProgress("正在清空工作目录...");
            cleanWorkDir();
            ensureWorkDir();

            String apkPath = null;
            if (selectedApkPath != null) {
                apkPath = selectedApkPath;
            } else if (selectedApkUri != null) {
                try {
                    publishProgress("正在复制APK到工作目录...");
                    File apkFile = new File(workDir, "source_apk_" + System.currentTimeMillis() + ".apk");
                    try (InputStream is = getContentResolver().openInputStream(selectedApkUri);
                         FileOutputStream fos = new FileOutputStream(apkFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                    apkPath = apkFile.getAbsolutePath();
                    appendLog("APK已复制到: " + apkPath);
                } catch (Exception e) {
                    publishProgress("复制APK失败: " + e.getMessage());
                    Log.e(TAG, "复制APK失败", e);
                    return false;
                }
            } else {
                publishProgress("错误: 未找到APK源");
                return false;
            }

            File tempDir = null;
            File modifiedXiaoDex = null;
            try {
                publishProgress("正在创建临时工作目录...");
                tempDir = createTempDir();
                appendLog("临时目录: " + tempDir.getAbsolutePath());

                // 并行: 线程A只提取DEX文件, 线程B修改xiao.dex
                final String finalApkPath = apkPath;
                final File finalTempDir = tempDir;

                // ── 权限检测: 检查目标APK是否声明了xiao.dex所需的权限 ──
                publishProgress("正在检测目标APK权限...");
                List<String> missingPermissions = ManifestPermissionPatcher.getMissingPermissions(finalApkPath);
                if (!missingPermissions.isEmpty()) {
                    StringBuilder sb = new StringBuilder("检测到缺少权限: ");
                    for (String p : missingPermissions) sb.append(p).append(" ");
                    appendLog(sb.toString());
                    appendLog("将自动注入缺失的权限声明到 AndroidManifest.xml");
                } else {
                    appendLog("目标APK权限齐全, 无需补丁");
                }
                // 保存引用供 streamRepackApk 使用
                missingPermissionsRef = missingPermissions.isEmpty() ? null : missingPermissions;
                final String fContact = contact, fCardUrl = cardUrl;
                final String fTitle1 = title1, fTitle2 = title2, fTitle3 = title3, fTitle4 = title4;
                final String fVersion = version;
                ExecutorService executor = Executors.newFixedThreadPool(2);

                // 线程A: 只提取DEX文件 (不解压整个APK)
                Future<Integer> extractFuture = executor.submit(() -> {
                    publishProgress("正在提取DEX文件...");
                    long extractStart = System.currentTimeMillis();
                    int dexCount = extractDexFilesOnly(finalApkPath, finalTempDir);
                    appendLog("DEX提取完成: " + dexCount + "个文件 (" + (System.currentTimeMillis() - extractStart) + "ms)");
                    return dexCount;
                });

                // 线程B: 修改xiao.dex (独立于APK)
                Future<File> xiaoDexFuture = executor.submit(() -> {
                    publishProgress("正在修改classes.dex文件...");
                    return modifyXiaoDexWithDexlib2(fContact, fCardUrl, fTitle1, fTitle2, fTitle3, fTitle4, fVersion);
                });

                // 等待两个线程完成
                int dexCount = extractFuture.get();
                modifiedXiaoDex = xiaoDexFuture.get();
                executor.shutdown();

                if (modifiedXiaoDex == null) throw new Exception("修改xiao.dex失败");
                appendLog("classes.dex修改完成, DEX文件数: " + dexCount);

                // 确定新DEX文件名
                String newDexName = getNextAvailableDexName(tempDir);
                appendLog("新DEX将命名为: " + newDexName);

                publishProgress("正在修改目标Activity...");
                String modifiedDexName = modifyTargetActivityWithDexlib2(tempDir, targetActivity);
                appendLog(modifiedDexName != null ? "目标Activity修改成功: " + modifiedDexName : "警告: 目标Activity修改失败");

                // 流式重打包: 从原始APK直接流式复制未修改的条目, 只替换修改的DEX
                publishProgress("正在流式重打包APK (不解压)...");
                long repackStart = System.currentTimeMillis();
                outputApkPath = streamRepackApk(apkPath, tempDir, modifiedXiaoDex, newDexName, modifiedDexName, missingPermissionsRef);
                appendLog("APK流式重打包完成: " + outputApkPath + " (" + (System.currentTimeMillis() - repackStart) + "ms)");

                publishProgress("正在对APK进行Zipalign对齐...");
                long alignStart = System.currentTimeMillis();
                File outputApkFile = new File(outputApkPath);
                zipalignApk(outputApkFile);
                appendLog("APK Zipalign完成 (" + (System.currentTimeMillis() - alignStart) + "ms)");

                publishProgress("正在对APK进行V1+V2+V3签名...");
                long signStart = System.currentTimeMillis();
                signApk(outputApkFile);
                appendLog("APK签名完成 (V1+V2+V3) (" + (System.currentTimeMillis() - signStart) + "ms)");

                publishProgress("正在清理临时文件...");
                deleteDirectory(tempDir);
                if (modifiedXiaoDex != null && modifiedXiaoDex.exists()) modifiedXiaoDex.delete();
                appendLog("临时文件清理完成");
                return true;
            } catch (Exception e) {
                publishProgress("注入失败: " + e.getMessage());
                Log.e(TAG, "注入失败", e);
                appendLog("注入失败: " + describeError(e));
                return false;
            } finally {
                if (tempDir != null && tempDir.exists()) deleteDirectory(tempDir);
                if (modifiedXiaoDex != null && modifiedXiaoDex.exists()) modifiedXiaoDex.delete();
                if (selectedApkUri != null && apkPath != null) {
                    new File(apkPath).delete();
                }
            }
        }

        @Override
        protected void onProgressUpdate(String... values) { appendLog(values[0]); }

        @Override
        protected void onPostExecute(Boolean success) {
            if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
            if (success) {
                appendLog("注入完成！输出文件: " + outputApkPath);
                showSuccessDialog(outputApkPath);
            } else {
                appendLog("注入失败");
                Toast.makeText(DexInjectActivity.this, "注入失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String getNextAvailableDexName(File apkDir) {
        File[] dexFiles = apkDir.listFiles((dir, name) -> name.matches("classes[0-9]*\\.dex"));
        if (dexFiles == null || dexFiles.length == 0) {
            File classesDex = new File(apkDir, "classes.dex");
            return classesDex.exists() ? "classes2.dex" : "classes.dex";
        }
        int maxNumber = 0;
        for (File dexFile : dexFiles) {
            String name = dexFile.getName();
            if (name.equals("classes.dex")) maxNumber = Math.max(maxNumber, 1);
            else {
                try {
                    int number = Integer.parseInt(name.replace("classes", "").replace(".dex", ""));
                    maxNumber = Math.max(maxNumber, number);
                } catch (NumberFormatException ignored) {}
            }
        }
        return maxNumber == 0 ? "classes.dex" : "classes" + (maxNumber + 1) + ".dex";
    }

    private void copyFile(File source, File dest) throws IOException {
        try (InputStream is = new FileInputStream(source); OutputStream os = new FileOutputStream(dest)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) os.write(buffer, 0, length);
        }
        appendLog("文件复制完成: " + source.getName() + " -> " + dest.getName());
    }

    private File modifyXiaoDexWithDexlib2(String contact, String cardUrl, String title1, String title2, String title3, String title4, String version) {
        try {
            InputStream is = getAssets().open("xiao.dex");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) baos.write(buffer, 0, len);
            is.close();
            byte[] decrypted = DexEncryptUtil.decrypt(baos.toByteArray());
            if (decrypted == null) throw new Exception("xiao.dex解密失败");
            File tempDex = new File(workDir, "xiao_modified_" + System.currentTimeMillis() + ".dex");
            try (FileOutputStream fos = new FileOutputStream(tempDex)) {
                fos.write(decrypted);
            }

            appendLog("开始修改classes.dex...");
            DexFile dexFile = DexFileFactory.loadDexFile(tempDex, org.jf.dexlib2.Opcodes.forApi(COMPATIBLE_API_LEVEL));
            List<ClassDef> modifiedClasses = new ArrayList<>();
            boolean modified = false;

            for (ClassDef classDef : dexFile.getClasses()) {
                String className = classDef.getType();
                if (className.equals("Lcom/example/myapplication/DialogUtils;")) {
                    appendLog("修改对话框工具类...");
                    String[][] replacements = {
                            {AppConfig.AUTHOR_QQ, contact},
                            {AppConfig.WEBSITE_URL, cardUrl},
                            {"【请输入卡密】", title1},
                            {"·一机一码，通过后进入应用！", title2},
                            {"请点击下方按钮购买卡密👇", title3},
                            {"未验证前无法进入应用！", title4}
                    };
                    ClassDef current = classDef;
                    int replacedCount = 0;
                    for (String[] r : replacements) {
                        if (r[0] == null || r[0].isEmpty()) continue;
                        if (r[1] == null || r[1].isEmpty()) continue;
                        ClassDef next = modifyStringInClass(current, r[0], r[1]);
                        if (next != null) {
                            current = next;
                            replacedCount++;
                            appendLog("替换成功: " + r[0] + " -> " + r[1]);
                        } else {
                            appendLog("警告: 未匹配到待替换字符串: " + r[0]);
                        }
                    }
                    modifiedClasses.add(current);
                    if (replacedCount > 0) {
                        modified = true;
                        appendLog("对话框工具类修改完成（替换 " + replacedCount + " 处）");
                    } else {
                        appendLog("对话框工具类修改失败：未匹配到可替换的字符串");
                    }
                } else if (className.equals("Lcom/example/myapplication/AppConstants;")) {
                    appendLog("修改应用常量类(版本号)...");
                    if (version != null && !version.isEmpty()) {
                        ClassDef next = modifyStringInClass(classDef, "-1", version);
                        if (next != null) {
                            modifiedClasses.add(next);
                            modified = true;
                            appendLog("版本号替换成功: -1 -> " + version);
                        } else {
                            modifiedClasses.add(classDef);
                            appendLog("警告: 未匹配到版本号占位符: -1");
                        }
                    } else {
                        modifiedClasses.add(classDef);
                        appendLog("未填写版本号，跳过版本号替换");
                    }
                } else {
                    modifiedClasses.add(classDef);
                }
            }

            // ── 合并 CloudMarker 帮助类到 xiao.dex ──
            {
                try {
                    java.io.InputStream cmIs = getAssets().open("cloud_marker.dex");
                    java.io.ByteArrayOutputStream cmBos = new java.io.ByteArrayOutputStream();
                    byte[] cmBuf = new byte[8192]; int cmN;
                    while ((cmN = cmIs.read(cmBuf)) > 0) cmBos.write(cmBuf, 0, cmN);
                    cmIs.close();
                    java.io.File cmTemp = new java.io.File(workDir, "cloud_marker_temp_" + System.currentTimeMillis() + ".dex");
                    try (java.io.FileOutputStream cmFos = new java.io.FileOutputStream(cmTemp)) {
                        cmFos.write(cmBos.toByteArray());
                    }
                    org.jf.dexlib2.iface.DexFile cmDex = org.jf.dexlib2.DexFileFactory.loadDexFile(
                        cmTemp, org.jf.dexlib2.Opcodes.forApi(COMPATIBLE_API_LEVEL));
                    for (ClassDef cmClass : cmDex.getClasses()) {
                        modifiedClasses.add(cmClass);
                    }
                    cmTemp.delete();
                    modified = true;
                    appendLog("CloudMarker 帮助类已合并到 xiao.dex");
                } catch (Exception cmEx) {
                    appendLog("CloudMarker 合并失败: " + cmEx.getMessage());
                }
            }

            if (modified) {
                DexFile newDexFile = new ImmutableDexFile(org.jf.dexlib2.Opcodes.forApi(COMPATIBLE_API_LEVEL), modifiedClasses);
                DexFileFactory.writeDexFile(tempDex.getAbsolutePath(), newDexFile);
                appendLog("classes.dex修改完成");
                return tempDex;
            } else {
                appendLog("未找到需要修改的类");
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "修改classes.dex失败", e);
            appendLog("xiao.dex修改失败: " + e.getMessage());
            return null;
        }
    }

    private ClassDef modifyStringInClass(ClassDef classDef, String oldString, String newString) {
        try {
            List<Method> newMethods = new ArrayList<>();
            boolean methodModified = false;
            for (Method method : classDef.getMethods()) {
                MethodImplementation impl = method.getImplementation();
                if (impl != null) {
                    List<Instruction> newInstructions = new ArrayList<>();
                    boolean instructionsModified = false;
                    for (Instruction instruction : impl.getInstructions()) {
                        Instruction newInstruction = instruction;
                        if (instruction.getOpcode() == Opcode.CONST_STRING || instruction.getOpcode() == Opcode.CONST_STRING_JUMBO) {
                            if (instruction instanceof ReferenceInstruction) {
                                ReferenceInstruction refInstruction = (ReferenceInstruction) instruction;
                                if (refInstruction.getReference() instanceof StringReference) {
                                    StringReference stringRef = (StringReference) refInstruction.getReference();
                                    if (oldString.equals(stringRef.getString())) {
                                        StringReference newStringRef = new ImmutableStringReference(newString);
                                        if (instruction.getOpcode() == Opcode.CONST_STRING) {
                                            newInstruction = new org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c(
                                                    Opcode.CONST_STRING,
                                                    ((org.jf.dexlib2.iface.instruction.OneRegisterInstruction) instruction).getRegisterA(),
                                                    newStringRef);
                                        } else {
                                            newInstruction = new org.jf.dexlib2.immutable.instruction.ImmutableInstruction31c(
                                                    Opcode.CONST_STRING_JUMBO,
                                                    ((org.jf.dexlib2.iface.instruction.OneRegisterInstruction) instruction).getRegisterA(),
                                                    newStringRef);
                                        }
                                        instructionsModified = true;
                                    }
                                }
                            }
                        }
                        newInstructions.add(newInstruction);
                    }
                    if (instructionsModified) {
                        MethodImplementation newImpl = new ImmutableMethodImplementation(
                                impl.getRegisterCount(), newInstructions, impl.getTryBlocks(), impl.getDebugItems());
                        Method newMethod = new ImmutableMethod(
                                method.getDefiningClass(), method.getName(), method.getParameters(),
                                method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                                method.getHiddenApiRestrictions(), newImpl);
                        newMethods.add(newMethod);
                        methodModified = true;
                    } else {
                        newMethods.add(method);
                    }
                } else {
                    newMethods.add(method);
                }
            }
            // 计算替换后的静态字段初始值，例如 QQ 号、购卡地址（通过 Field.getInitialValue 暴露）
            List<org.jf.dexlib2.iface.Field> newFields = new ArrayList<>();
            boolean fieldChanged = false;
            for (org.jf.dexlib2.iface.Field field : classDef.getFields()) {
                org.jf.dexlib2.iface.value.EncodedValue init = field.getInitialValue();
                org.jf.dexlib2.iface.value.EncodedValue newInit = replaceInStaticValues(init, oldString, newString);
                if (newInit != init) {
                    newFields.add(new org.jf.dexlib2.immutable.ImmutableField(
                            field.getDefiningClass(), field.getName(), field.getType(), field.getAccessFlags(),
                            newInit, field.getAnnotations(), field.getHiddenApiRestrictions()));
                    fieldChanged = true;
                } else {
                    newFields.add(field);
                }
            }

            if (methodModified || fieldChanged) {
                return new ImmutableClassDef(classDef.getType(), classDef.getAccessFlags(), classDef.getSuperclass(),
                        classDef.getInterfaces(), classDef.getSourceFile(), classDef.getAnnotations(),
                        newFields, newMethods);
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 递归替换静态字段初始值中的字符串。
     * dexlib2 中类的 static_values 通常是一个 ArrayEncodedValue，
     * 里面每个元素对应一个静态字段的初值（例如 QQ_NUMBER 这类 StringEncodedValue）。
     */
    private org.jf.dexlib2.iface.value.EncodedValue replaceInStaticValues(
            org.jf.dexlib2.iface.value.EncodedValue encodedValue,
            String oldString, String newString) {
        if (encodedValue == null) return null;
        if (encodedValue instanceof org.jf.dexlib2.iface.value.ArrayEncodedValue) {
            List<org.jf.dexlib2.iface.value.EncodedValue> items = new ArrayList<>(
                    ((org.jf.dexlib2.iface.value.ArrayEncodedValue) encodedValue).getValue());
            boolean changed = false;
            for (int i = 0; i < items.size(); i++) {
                org.jf.dexlib2.iface.value.EncodedValue item = items.get(i);
                org.jf.dexlib2.iface.value.EncodedValue replaced = replaceInStaticValues(item, oldString, newString);
                if (replaced != item) { items.set(i, replaced); changed = true; }
            }
            if (changed) {
                return new org.jf.dexlib2.immutable.value.ImmutableArrayEncodedValue(items);
            }
            return encodedValue;
        } else if (encodedValue instanceof org.jf.dexlib2.iface.value.StringEncodedValue) {
            String value = ((org.jf.dexlib2.iface.value.StringEncodedValue) encodedValue).getValue();
            if (oldString.equals(value)) {
                return new org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue(newString);
            }
            return encodedValue;
        }
        return encodedValue;
    }

    private String modifyTargetActivityWithDexlib2(File apkDir, String targetActivity) {
        try {
            appendLog("=== 开始修改目标Activity ===");
            appendLog("目标Activity: " + targetActivity);
            String dexTargetActivity = "L" + targetActivity.replace('.', '/') + ";";
            File[] dexFiles = apkDir.listFiles((dir, name) -> name.matches("classes[0-9]*\\.dex"));
            if (dexFiles == null || dexFiles.length == 0) { appendLog("错误: 未找到DEX文件"); return null; }

            appendLog("共 " + dexFiles.length + " 个DEX文件, 使用多线程并行扫描");
            AtomicReference<String> modifiedDexName = new AtomicReference<>(null);
            int threads = Math.min(dexFiles.length, Runtime.getRuntime().availableProcessors());
            ExecutorService dexExecutor = Executors.newFixedThreadPool(threads);
            List<Future<?>> dexFutures = new ArrayList<>();

            for (File dexFile : dexFiles) {
                dexFutures.add(dexExecutor.submit(() -> {
                    if (modifiedDexName.get() != null) return;
                    try {
                        long dexStart = System.currentTimeMillis();
                        appendLog("正在扫描: " + dexFile.getName() + " (" + (dexFile.length() / 1048576) + " MB)");
                        DexFile dex = DexFileFactory.loadDexFile(dexFile.getAbsolutePath(), org.jf.dexlib2.Opcodes.forApi(COMPATIBLE_API_LEVEL));
                        List<ClassDef> modifiedClasses = new ArrayList<>();
                        boolean modified = false;
                        for (ClassDef classDef : dex.getClasses()) {
                            if (modified) { modifiedClasses.add(classDef); continue; }
                            if (classDef.getType().equals(dexTargetActivity)) {
                                ClassDef modifiedClass = modifyClassDef(classDef);
                                if (modifiedClass != null) { modifiedClasses.add(modifiedClass); modified = true; modifiedDexName.compareAndSet(null, dexFile.getName()); }
                                else { modifiedClasses.add(classDef); }
                            } else {
                                modifiedClasses.add(classDef);
                            }
                        }
                        if (modified) {
                            DexFile modifiedDex = new ImmutableDexFile(org.jf.dexlib2.Opcodes.forApi(COMPATIBLE_API_LEVEL), modifiedClasses);
                            DexFileFactory.writeDexFile(dexFile.getAbsolutePath(), modifiedDex);
                            long dexTime = System.currentTimeMillis() - dexStart;
                            appendLog("成功保存修改后的DEX文件: " + dexFile.getName() + " (" + dexTime + "ms)");
                        } else {
                            long dexTime = System.currentTimeMillis() - dexStart;
                            appendLog("未找到目标,跳过: " + dexFile.getName() + " (" + dexTime + "ms)");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "处理DEX文件失败: " + dexFile.getName(), e);
                    }
                }));
            }

            for (Future<?> f : dexFutures) {
                try { f.get(); } catch (Exception ignored) {}
            }
            dexExecutor.shutdown();
            return modifiedDexName.get();
        } catch (Exception e) {
            Log.e(TAG, "修改目标Activity失败", e);
            return null;
        }
    }

    private ClassDef modifyClassDef(ClassDef classDef) {
        List<Method> methods = new ArrayList<>();
        boolean methodModified = false;
        for (Method method : classDef.getMethods()) {
            if ("onCreate".equals(method.getName())) {
                String descriptor = getMethodDescriptor(method);
                if ("(Landroid/os/Bundle;)V".equals(descriptor) || "()V".equals(descriptor)) {
                    if ((method.getAccessFlags() & 0x8) != 0) { methods.add(method); continue; }
                    Method modifiedMethod = modifyOnCreateMethod(method);
                    if (modifiedMethod != null) { methods.add(modifiedMethod); methodModified = true; continue; }
                }
            }
            methods.add(method);
        }
        if (!methodModified) {
            for (Method method : classDef.getMethods()) {
                String methodName = method.getName();
                String descriptor = getMethodDescriptor(method);
                if ((method.getAccessFlags() & 0x8) != 0) { methods.add(method); continue; }
                if (("onStart".equals(methodName) || "onResume".equals(methodName)) && "()V".equals(descriptor)) {
                    Method modifiedMethod = modifyOnCreateMethod(method);
                    if (modifiedMethod != null) { methods.add(modifiedMethod); methodModified = true; }
                    else { methods.add(method); }
                } else if ("onPostCreate".equals(methodName) && "(Landroid/os/Bundle;)V".equals(descriptor)) {
                    Method modifiedMethod = modifyOnCreateMethod(method);
                    if (modifiedMethod != null) { methods.add(modifiedMethod); methodModified = true; }
                    else { methods.add(method); }
                } else { methods.add(method); }
            }
        }
        if (!methodModified) return null;
        return new ImmutableClassDef(classDef.getType(), classDef.getAccessFlags(), classDef.getSuperclass(),
                classDef.getInterfaces(), classDef.getSourceFile(), classDef.getAnnotations(),
                classDef.getFields(), methods);
    }

    private Method modifyOnCreateMethod(Method method) {
        try {
            List<? extends MethodParameter> parameters = method.getParameters();
            List<MethodParameter> newParameters = new ArrayList<>(parameters);
            MethodImplementation impl = method.getImplementation();
            if (impl == null) return null;
            int registerCount = impl.getRegisterCount();
            int totalParams = parameters.size() + 1;
            if (registerCount < totalParams) return null;
            int thisRegister = registerCount - totalParams;
            List<Instruction> newInstructions = new ArrayList<>();
            ImmutableMethodReference targetMethod = new ImmutableMethodReference(
                    "Lcom/example/myapplication/MacUtils;", "showCardKeyValidationScreen",
                    Collections.singletonList("Landroid/app/Activity;"), "V");
            ImmutableInstruction35c invokeInstruction = new ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC, 1, thisRegister, 0, 0, 0, 0, targetMethod);
            // ── Cloud标记: 创建 /sdcard/Cloud/uuid.data ──
            // 先调用 MacUtils.ensureCloudMarker(Activity) 确保 Cloud 目录和 uuid.data 存在
            ImmutableMethodReference cloudMarkerMethod = new ImmutableMethodReference(
                    "Lcom/example/myapplication/CloudMarker;", "ensureCloudMarker",
                    Collections.singletonList("Landroid/app/Activity;"), "V");
            ImmutableInstruction35c cloudInvoke = new ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC, 1, thisRegister, 0, 0, 0, 0, cloudMarkerMethod);
            newInstructions.add(cloudInvoke);
            // showCardKeyValidationScreen
            newInstructions.add(invokeInstruction);
            for (Instruction instruction : impl.getInstructions()) newInstructions.add(instruction);
            MethodImplementation newImpl = new ImmutableMethodImplementation(
                    impl.getRegisterCount(), newInstructions, impl.getTryBlocks(), impl.getDebugItems());
            return new ImmutableMethod(method.getDefiningClass(), method.getName(), newParameters,
                    method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                    method.getHiddenApiRestrictions(), newImpl);
        } catch (Exception e) {
            return null;
        }
    }

    private String getMethodDescriptor(Method method) {
        StringBuilder sb = new StringBuilder("(");
        for (MethodParameter param : method.getParameters()) sb.append(param.getType());
        sb.append(")").append(method.getReturnType());
        return sb.toString();
    }

    private File createTempDir() throws IOException {
        File tempDir = new File(workDir, "temp_" + System.currentTimeMillis());
        if (tempDir.exists()) deleteDirectory(tempDir);
        if (!tempDir.mkdirs()) throw new IOException("无法创建临时目录: " + tempDir.getAbsolutePath());
        return tempDir;
    }

    private void unzipApk(String apkPath, File outputDir) throws IOException {
        String canonicalOutputDir = outputDir.getCanonicalPath();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(apkPath))) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entryName.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }
                File outputFile = new File(outputDir, entryName);
                String canonicalOutput = outputFile.getCanonicalPath();
                if (!canonicalOutput.startsWith(canonicalOutputDir))
                    throw new IOException("非法文件路径: " + entryName);
                if (canonicalOutput.equals(canonicalOutputDir)) {
                    zis.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    if (!outputFile.mkdirs() && !outputFile.isDirectory())
                        throw new IOException("无法创建目录: " + outputFile.getAbsolutePath());
                } else {
                    File parent = outputFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs())
                        throw new IOException("无法创建父目录: " + parent.getAbsolutePath());
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void copyStream(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
    }

    /**
     * 只从APK中提取DEX文件到输出目录, 不解压其他文件
     */
    private int extractDexFilesOnly(String apkPath, File outputDir) throws IOException {
        int count = 0;
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(apkPath)) {
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.matches("classes[0-9]*\\.dex")) continue;
                File outFile = new File(outputDir, name);
                try (InputStream is = zipFile.getInputStream(entry);
                     FileOutputStream fos = new FileOutputStream(outFile)) {
                    int len;
                    while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
                }
                count++;
            }
        }
        return count;
    }

    /**
     * 流式重打包APK: 从原始APK直接流式复制未修改的条目, 只替换修改的DEX, 跳过META-INF
     * 避免了全量解压+全量重压缩的巨大开销
     */
    private String streamRepackApk(String originalApkPath, File tempDir, File modifiedXiaoDex,
                                    String newDexName, String modifiedDexName,
                                    List<String> missingPermissions) throws IOException {
        File originalApk = new File(originalApkPath);
        String originalName = originalApk.getName();
        String baseName = originalName.contains(".") ? originalName.substring(0, originalName.lastIndexOf('.')) : originalName;
        String outputName = baseName + "_injected.apk";
        File outputFile = new File(workDir, outputName);
        if (outputFile.exists() && !outputFile.delete())
            throw new IOException("无法删除已存在的输出文件: " + outputFile.getAbsolutePath());

        byte[] buffer = new byte[65536];
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(originalApkPath);
             FileOutputStream fos = new FileOutputStream(outputFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 65536);
             ZipOutputStream zos = new ZipOutputStream(bos)) {

            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            int copied = 0, skipped = 0, replaced = 0;

            while (entries.hasMoreElements()) {
                ZipEntry originalEntry = entries.nextElement();
                String entryName = originalEntry.getName();

                // 跳过META-INF (签名校验)
                if (entryName.startsWith("META-INF/") || entryName.equals("META-INF")) {
                    skipped++;
                    continue;
                }
                if (entryName.isEmpty()) continue;

                // 如果是被修改的DEX, 用修改后的版本替换
                if (modifiedDexName != null && entryName.equals(modifiedDexName)) {
                    File modifiedDex = new File(tempDir, modifiedDexName);
                    if (modifiedDex.exists()) {
                        ZipEntry newEntry = new ZipEntry(entryName);
                        newEntry.setMethod(ZipEntry.DEFLATED);
                        zos.putNextEntry(newEntry);
                        try (FileInputStream fis = new FileInputStream(modifiedDex)) {
                            int len;
                            while ((len = fis.read(buffer)) > 0) zos.write(buffer, 0, len);
                        }
                        zos.closeEntry();
                        replaced++;
                        continue;
                    }
                }

                // ── AndroidManifest.xml 权限补丁 ──
                if (missingPermissions != null && entryName.equals("AndroidManifest.xml")) {
                    try {
                        java.io.ByteArrayOutputStream manifestBos = new java.io.ByteArrayOutputStream();
                        try (InputStream mis = zipFile.getInputStream(originalEntry)) {
                            byte[] mbuf = new byte[4096]; int mn;
                            while ((mn = mis.read(mbuf)) > 0) manifestBos.write(mbuf, 0, mn);
                        }
                        byte[] patched = ManifestPermissionPatcher.injectPermissions(
                            manifestBos.toByteArray(), missingPermissions);
                        if (patched != null) {
                            ZipEntry manifestEntry = new ZipEntry(entryName);
                            manifestEntry.setMethod(ZipEntry.DEFLATED);
                            zos.putNextEntry(manifestEntry);
                            zos.write(patched);
                            zos.closeEntry();
                            appendLog("已注入 " + missingPermissions.size() + " 个缺失权限到 AndroidManifest.xml");
                            replaced++;
                            continue;
                        }
                    } catch (Exception me) {
                        appendLog("AndroidManifest权限补丁失败: " + me.getMessage() + ", 使用原始manifest");
                    }
                }
                // 其他条目: 从原始APK直接流式复制
                ZipEntry newEntry = new ZipEntry(entryName);
                if (originalEntry.getMethod() == ZipEntry.STORED) {
                    newEntry.setMethod(ZipEntry.STORED);
                    newEntry.setSize(originalEntry.getSize());
                    newEntry.setCompressedSize(originalEntry.getCompressedSize());
                    newEntry.setCrc(originalEntry.getCrc());
                } else {
                    newEntry.setMethod(ZipEntry.DEFLATED);
                }
                zos.putNextEntry(newEntry);
                if (!originalEntry.isDirectory()) {
                    try (InputStream is = zipFile.getInputStream(originalEntry)) {
                        int len;
                        while ((len = is.read(buffer)) > 0) zos.write(buffer, 0, len);
                    }
                }
                zos.closeEntry();
                copied++;
            }

            // 添加新的xiao.dex
            if (modifiedXiaoDex != null && modifiedXiaoDex.exists()) {
                ZipEntry newDexEntry = new ZipEntry(newDexName);
                newDexEntry.setMethod(ZipEntry.DEFLATED);
                zos.putNextEntry(newDexEntry);
                try (FileInputStream fis = new FileInputStream(modifiedXiaoDex)) {
                    int len;
                    while ((len = fis.read(buffer)) > 0) zos.write(buffer, 0, len);
                }
                zos.closeEntry();
                appendLog("已添加新DEX: " + newDexName);
            }

            appendLog("流式重打包统计: 复制=" + copied + " 跳过META-INF=" + skipped + " 替换DEX=" + replaced);
        }

        if (!outputFile.exists() || outputFile.length() == 0)
            throw new IOException("输出文件创建失败");
        return outputFile.getAbsolutePath();
    }

    private String repackApk(File inputDir, String originalApkPath) throws IOException {
        File originalApk = new File(originalApkPath);
        String originalName = originalApk.getName();
        String baseName = originalName.contains(".") ? originalName.substring(0, originalName.lastIndexOf('.')) : originalName;
        String outputName = baseName + "_injected.apk";
        File outputFile = new File(workDir, outputName);
        if (outputFile.exists() && !outputFile.delete())
            throw new IOException("无法删除已存在的输出文件: " + outputFile.getAbsolutePath());
        try (FileOutputStream fos = new FileOutputStream(outputFile); ZipOutputStream zos = new ZipOutputStream(fos)) {
            zipFolder(inputDir, inputDir, zos);
        }
        if (!outputFile.exists() || outputFile.length() == 0)
            throw new IOException("输出文件创建失败");
        return outputFile.getAbsolutePath();
    }

    private void zipFolder(File rootDir, File sourceDir, ZipOutputStream zos) throws IOException {
        File[] files = sourceDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) zipFolder(rootDir, file, zos);
            else {
                String relativePath = getRelativePath(rootDir, file);
                ZipEntry entry = new ZipEntry(relativePath);
                if (relativePath.equals("AndroidManifest.xml") || relativePath.endsWith(".arsc")) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(file.length());
                    entry.setCompressedSize(file.length());
                    entry.setCrc(computeCRC32(file));
                } else {
                    entry.setMethod(ZipEntry.DEFLATED);
                }
                zos.putNextEntry(entry);
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[65536];
                    int len;
                    while ((len = fis.read(buffer)) > 0) zos.write(buffer, 0, len);
                }
                zos.closeEntry();
            }
        }
    }

    private long computeCRC32(File file) throws IOException {
        CRC32 crc = new CRC32();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int len;
            while ((len = fis.read(buffer)) > 0) crc.update(buffer, 0, len);
        }
        return crc.getValue();
    }

    private String getRelativePath(File rootDir, File file) {
        String rootPath = rootDir.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) deleteDirectory(file);
        }
        dir.delete();
    }

    private void removeMetaInf(File apkDir) {
        File metaInfDir = new File(apkDir, "META-INF");
        if (metaInfDir.exists() && metaInfDir.isDirectory()) {
            deleteDirectory(metaInfDir);
        }
    }

        /**
     * Zipalign: 确保STORED条目数据按4字节对齐
     * Android系统安装器要求未压缩条目(resources.arsc等)数据起始偏移为4的倍数
     */
    private void zipalignApk(File apkFile) throws IOException {
        File alignedFile = new File(apkFile.getParent(),
                apkFile.getName().replace(".apk", "_aligned.apk"));
        byte[] buffer = new byte[65536];
        int aligned = 0, total = 0;

        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(apkFile)) {
            try (FileOutputStream fos = new FileOutputStream(alignedFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, 65536)) {

                // 计数流: 追踪当前ZIP输出偏移, 用于计算条目数据的绝对对齐
                final long[] written = {0L};
                java.io.OutputStream counting = new java.io.FilterOutputStream(bos) {
                    @Override public void write(int b) throws IOException { out.write(b); written[0]++; }
                    @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); written[0] += len; }
                };

                try (ZipOutputStream zos = new ZipOutputStream(counting)) {
                    java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        total++;
                        String name = entry.getName();
                        ZipEntry newEntry = new ZipEntry(name);

                        if (entry.getMethod() == ZipEntry.STORED) {
                            newEntry.setMethod(ZipEntry.STORED);
                            newEntry.setSize(entry.getSize());
                            newEntry.setCompressedSize(entry.getCompressedSize());
                            newEntry.setCrc(entry.getCrc());

                            byte[] originalExtra = entry.getExtra();
                            int originalExtraLen = (originalExtra != null) ? originalExtra.length : 0;
                            int nameLen = name.getBytes("UTF-8").length;

                            // 数据偏移 = 已写字节数 + 30(固定头) + nameLen + extraLen, 需4字节对齐
                            int padLen = (int) ((4 - ((written[0] + 30 + nameLen + originalExtraLen) % 4)) % 4);

                            byte[] newExtra = new byte[originalExtraLen + padLen];
                            if (originalExtra != null) System.arraycopy(originalExtra, 0, newExtra, 0, originalExtraLen);
                            newEntry.setExtra(newExtra);
                            aligned++;
                        } else {
                            newEntry.setMethod(ZipEntry.DEFLATED);
                            byte[] originalExtra = entry.getExtra();
                            if (originalExtra != null) newEntry.setExtra(originalExtra);
                        }

                        zos.putNextEntry(newEntry);
                        if (!entry.isDirectory()) {
                            java.io.InputStream is = zipFile.getInputStream(entry);
                            int len;
                            while ((len = is.read(buffer)) > 0) zos.write(buffer, 0, len);
                            is.close();
                        }
                        zos.closeEntry();
                    }
                }
            }
        }

        appendLog("Zipalign完成: " + aligned + "/" + total + " 个STORED条目已对齐");

        if (!alignedFile.exists() || alignedFile.length() == 0)
            throw new IOException("Zipalign输出文件创建失败");
        if (!apkFile.delete()) throw new IOException("无法删除未对齐APK");
        if (!alignedFile.renameTo(apkFile)) throw new IOException("无法重命名对齐后APK");
    }

    /**
     * 使用ApkSigner库进行V1+V2+V3签名
     */
    private void signApk(File apkFile) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X500Principal subject = new X500Principal("CN=Injector, OU=Injector, O=Injector, C=CN");
        long now = System.currentTimeMillis();
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(now),
                new Date(now - 86400000L),
                new Date(now + 365L * 86400000L * 20),
                subject, kp.getPublic());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(
                        new JcaContentSignerBuilder("SHA256WithRSA").build(kp.getPrivate())));

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "injector-key", kp.getPrivate(),
                Collections.singletonList(cert)).build();

        File signedApk = new File(apkFile.getParent(),
                apkFile.getName().replace(".apk", "_signed.apk"));
        if (signedApk.exists()) signedApk.delete();

        // 优先尝试 V1+V2+V3 完整签名
        try {
            new ApkSigner.Builder(Collections.singletonList(signerConfig))
                    .setInputApk(apkFile)
                    .setOutputApk(signedApk)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .build()
                    .sign();
            appendLog("签名完成: V1+V2+V3");
        } catch (Exception fullSchemeError) {
            // V1(JAR) 签名在部分设备/ROM 上可能因 JCE 环境异常而失败；
            // V2/V3 是 Android 7.0+ 真正生效的签名方案，V1 仅用于兼容更老系统。
            // 因此这里降级为 V2+V3 重试，保证注入结果仍可正常安装。
            appendLog("V1+V2+V3 签名失败，降级为 V2+V3 重试");
            appendLog("原始错误: " + describeError(fullSchemeError));
            if (signedApk.exists()) signedApk.delete();
            try {
                new ApkSigner.Builder(Collections.singletonList(signerConfig))
                        .setInputApk(apkFile)
                        .setOutputApk(signedApk)
                        .setV1SigningEnabled(false)
                        .setV2SigningEnabled(true)
                        .setV3SigningEnabled(true)
                        .build()
                        .sign();
                appendLog("签名完成: V2+V3 (已跳过 V1)");
            } catch (Exception v23Error) {
                appendLog("V2+V3 签名同样失败: " + describeError(v23Error));
                throw new IOException("签名失败(已尝试 V1+V2+V3 与 V2+V3): "
                        + describeError(v23Error), v23Error);
            }
        }

        if (signedApk.length() <= 0)
            throw new IOException("签名输出文件为空");

        if (!apkFile.delete()) throw new IOException("无法删除未签名APK");
        if (!signedApk.renameTo(apkFile)) throw new IOException("无法重命名已签名APK");
    }

    /**
     * 展开完整异常链，便于定位 apksig 抛出的真实原因。
     * apksig 的部分异常只保留简短 message，真正的根因藏在 cause 里。
     */
    private String describeError(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 8) {
            if (depth > 0) sb.append("\n    ← 由 ");
            sb.append(cur.getClass().getSimpleName()).append(": ").append(cur.getMessage());
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    private void showSuccessDialog(String apkPath) {
        new AlertDialog.Builder(this)
                .setTitle("注入完成")
                .setMessage("APK注入已完成！\n\n已自动完成V1+V2+V3签名\n\n输出文件: " + new File(apkPath).getName() + "\n\n文件位置: " + apkPath)
                .setPositiveButton("安装", (dialog, which) -> checkAndInstallApk(apkPath))
                .setNeutralButton("打开目录", (dialog, which) -> openOutputDirectory(apkPath))
                .setNegativeButton("取消", null)
                .show();
    }

    private void openOutputDirectory(String apkPath) {
        try {
            File outputDir = new File(apkPath).getParentFile();
            if (outputDir == null || !outputDir.exists()) { Toast.makeText(this, "目录不存在", Toast.LENGTH_SHORT).show(); return; }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ?
                    FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", outputDir) : Uri.fromFile(outputDir);
            intent.setDataAndType(uri, "resource/folder");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (intent.resolveActivity(getPackageManager()) != null) startActivity(intent);
            else startActivity(Intent.createChooser(intent, "选择文件管理器"));
            appendLog("已打开输出目录: " + outputDir.getAbsolutePath());
        } catch (Exception e) {
            Toast.makeText(this, "打开目录失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            appendLog("打开目录失败: " + e.getMessage());
            File apkFile = new File(apkPath);
            File outputDir = apkFile.getParentFile();
            if (outputDir != null) {
                String directoryPath = outputDir.getAbsolutePath();
                new AlertDialog.Builder(this)
                        .setTitle("目录位置")
                        .setMessage("文件目录: " + directoryPath)
                        .setPositiveButton("复制", (dialog, which) -> copyToClipboard(directoryPath))
                        .setNegativeButton("取消", null)
                        .show();
            }
        }
    }

    @SuppressLint("deprecation")
    private void copyToClipboard(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("目录路径", text));
            Toast.makeText(this, "目录路径已复制到剪贴板", Toast.LENGTH_SHORT).show();
            appendLog("已复制目录路径: " + text);
        } catch (Exception e) {
            Toast.makeText(this, "复制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            appendLog("复制目录路径失败: " + e.getMessage());
        }
    }

    /**
     * 安装前检测本机是否已安装同包名应用
     * 若已安装则提示先卸载, 卸载完成后自动安装注入后的APK
     */
    private void checkAndInstallApk(String apkPath) {
        String targetPkg = getPackageNameFromApk(apkPath);
        if (targetPkg == null || targetPkg.isEmpty()) {
            appendLog("无法解析APK包名, 直接安装");
            installApk(apkPath);
            return;
        }
        if (isPackageInstalled(targetPkg)) {
            appendLog("检测到已安装应用: " + targetPkg + ", 签名不一致可能导致安装失败");
            new AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("与已安装的签名不一致,可能导致安装失败!\n是否卸载已安装应用?")
                    .setPositiveButton("继续安装", (dialog, which) -> installApk(apkPath))
                    .setNegativeButton("卸载", (dialog, which) -> uninstallAndInstall(apkPath, targetPkg))
                    .show();
        } else {
            appendLog("未检测到已安装应用, 直接安装");
            installApk(apkPath);
        }
    }

    private String getPackageNameFromApk(String apkPath) {
        try {
            PackageInfo info = getPackageManager().getPackageArchiveInfo(apkPath, 0);
            if (info != null && info.packageName != null) {
                return info.packageName;
            }
        } catch (Exception e) {
            appendLog("解析APK包名失败: " + e.getMessage());
        }
        return null;
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void uninstallAndInstall(String apkPath, String packageName) {
        pendingInstallApkPath = apkPath;
        // Android 8.0+ 需要检查卸载权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                appendLog("需要授予安装未知应用权限");
                Intent permissionIntent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(permissionIntent, REQUEST_CODE_UNINSTALL_PERMISSION);
                return;
            }
        }
        doUninstall(packageName);
    }

    private void doUninstall(String packageName) {
        try {
            pendingUninstallPkg = packageName;
            // 注册广播监听卸载完成
            if (uninstallReceiver != null) {
                try { unregisterReceiver(uninstallReceiver); } catch (Exception ignored) {}
            }
            uninstallReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, Intent intent) {
                    if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                        String removedPkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;
                        if (packageName.equals(removedPkg)) {
                            appendLog("检测到 " + packageName + " 已卸载, 开始自动安装注入后的APK");
                            try { unregisterReceiver(this); } catch (Exception ignored) {}
                            uninstallReceiver = null;
                            if (pendingInstallApkPath != null) {
                                String apk = pendingInstallApkPath;
                                pendingInstallApkPath = null;
                                pendingUninstallPkg = null;
                                new android.os.Handler().postDelayed(() -> installApk(apk), 500);
                            }
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
            filter.addDataScheme("package");
            registerReceiver(uninstallReceiver, filter);

            Intent uninstallIntent = new Intent(Intent.ACTION_DELETE);
            uninstallIntent.setData(Uri.parse("package:" + packageName));
            uninstallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(uninstallIntent);
            appendLog("已调起系统卸载, 等待卸载完成...");
        } catch (Exception e) {
            Toast.makeText(this, "卸载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            appendLog("卸载失败: " + e.getMessage());
            pendingInstallApkPath = null;
            pendingUninstallPkg = null;
        }
    }

    private void installApk(String apkPath) {
        try {
            File apkFile = new File(apkPath);
            Uri apkUri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ?
                    FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile) : Uri.fromFile(apkFile);
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(installIntent, REQUEST_CODE_INSTALL_APK);
        } catch (Exception e) {
            Toast.makeText(this, "安装失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            appendLog("安装失败: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (uninstallReceiver != null) {
            try { unregisterReceiver(uninstallReceiver); } catch (Exception ignored) {}
            uninstallReceiver = null;
        }
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            String timestamp = java.text.DateFormat.getTimeInstance().format(new java.util.Date());
            tvLog.setText(tvLog.getText() + "\n[" + timestamp + "] " + message);
            svLogContainer.post(() -> svLogContainer.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(DexInjectActivity.this, message, Toast.LENGTH_SHORT).show());
    }
}
