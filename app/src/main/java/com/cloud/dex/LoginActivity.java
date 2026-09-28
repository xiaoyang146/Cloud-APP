package com.cloud.dex;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";

    private TextInputEditText etUsername, etPassword;
    private TextInputLayout tilUsername, tilPassword;
    private Button btnLogin;
    private TextView tvForgotPassword, tvRegister;
    private ProgressBar progressBar;
    private ImageView ivGif;
    private RequestQueue requestQueue;
    private SharedPreferencesManager spManager;
    private AppUpdateManager updateManager;


    private long lastLoginAttemptTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: 开始初始化");

        setContentView(R.layout.activity_login);

        spManager = new SharedPreferencesManager(this);
        spManager.diagnose();

        // 检查登录状态，如果数据完整则直接跳转
        if (spManager.isUserDataValid()) {
            Log.d(TAG, "用户数据完整，直接跳转到主界面");
            navigateToMainActivity();
            return;
        } else {
            Log.d(TAG, "用户数据不完整，显示登录界面");
            if (spManager.getLoginStatus()) {
                Log.w(TAG, "登录状态为true但数据不完整，清除登录状态");
                spManager.clearLoginInfo();
            }
        }

        setupActionBar();
        initViews();
        setupClickListeners();

        initUpdateManager();
        loadLocalGif();
        loadSavedCredentials();

        requestQueue = Volley.newRequestQueue(this);

        Log.d(TAG, "onCreate: 初始化完成");
    }

    private void initUpdateManager() {
        Log.d(TAG, "初始化AppUpdateManager");
        try {
            updateManager = new AppUpdateManager(this);
            Log.d(TAG, "AppUpdateManager初始化成功");
        } catch (Exception e) {
            Log.e(TAG, "AppUpdateManager初始化失败", e);
            Toast.makeText(this, "更新管理器初始化失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupActionBar() {
        try {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.card_bg)));

                SpannableString title = new SpannableString("注册与登录");
                title.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.text_black)), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                SpannableString subtitle = new SpannableString("若没有账号需要你自行注册");
                subtitle.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.text_black)), 0, subtitle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                getSupportActionBar().setTitle(title);
                getSupportActionBar().setSubtitle(subtitle);
                getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置ActionBar失败", e);
        }
    }

    private void initViews() {
        try {
            tilUsername = findViewById(R.id.tilUsername);
            tilPassword = findViewById(R.id.tilPassword);
            etUsername = findViewById(R.id.etUsername);
            etPassword = findViewById(R.id.etPassword);
            btnLogin = findViewById(R.id.btnLogin);
            tvForgotPassword = findViewById(R.id.tvForgotPassword);
            tvRegister = findViewById(R.id.tvRegister);
            progressBar = findViewById(R.id.progressBar);
            ivGif = findViewById(R.id.ivGif);
            Log.d(TAG, "视图初始化成功");
        } catch (Exception e) {
            Log.e(TAG, "视图初始化失败", e);
            Toast.makeText(this, "界面初始化失败，请重启应用", Toast.LENGTH_LONG).show();
        }
    }

    private void loadSavedCredentials() {
        try {
            String savedUsername = spManager.getUsername();
            String savedPassword = spManager.getPassword();

            if (!TextUtils.isEmpty(savedUsername)) {
                etUsername.setText(savedUsername);
                if (!TextUtils.isEmpty(savedPassword)) {
                    etPassword.setText(savedPassword);
                    btnLogin.requestFocus();
                } else {
                    etPassword.requestFocus();
                }
            }
            savedPassword = null;  // 尽快清除密码明文,减少内存留存
            Log.d(TAG, "加载保存的凭据完成");
        } catch (Exception e) {
            Log.e(TAG, "加载保存的凭据失败", e);
        }
    }

    private void loadLocalGif() {
        try {
            Log.d(TAG, "开始加载GIF动画");
            Glide.with(this)
                    .asGif()
                    .load(R.raw.login_animation)
                    .into(new SimpleTarget<GifDrawable>() {
                        @Override
                        public void onResourceReady(GifDrawable resource, Transition<? super GifDrawable> transition) {
                            Log.d(TAG, "GIF加载完成，开始检查更新");
                            ivGif.setImageDrawable(resource);
                            resource.start();
                            checkAppUpdate();
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "加载GIF失败", e);
            try {
                ivGif.setImageResource(android.R.drawable.ic_menu_gallery);
                checkAppUpdate();
            } catch (Exception ex) {
                Log.e(TAG, "设置备用图片也失败", ex);
                checkAppUpdate();
            }
        }
    }

    private void checkAppUpdate() {
        Log.d(TAG, "开始检查应用更新");
        if (updateManager != null) {
            updateManager.checkUpdate(true);
        } else {
            Log.e(TAG, "AppUpdateManager为null，无法检查更新");
            Toast.makeText(this, "更新检查失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String getAppNameForUpdate() {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(getPackageName(), 0)).toString();
        } catch (Exception e) {
            return "My Application";
        }
    }

    /**
     * 登录前检测版本更新，有新版本则阻止登录并弹窗
     */
    private void checkUpdateBeforeLogin(final String username, final String password) {
        final String appName = getAppNameForUpdate();
        final String packageName = getPackageName();
        final int versionCode = BuildConfig.VERSION_CODE;

        // CHECK_UPDATE_URL replaced with AppConfig.CHECK_VERSION_URL below

        StringRequest request = new StringRequest(Request.Method.POST, AppConfig.CHECK_VERSION_URL,
                response -> {
                    try {
                        JSONObject json = new JSONObject(response);
                        boolean needsUpdate = json.optBoolean("needs_update", false);

                        if (needsUpdate) {
                            JSONObject latest = json.getJSONObject("latest_version");
                            boolean forceUpdate = latest.optBoolean("force_update", false);

                            if (forceUpdate) {
                                String latestName = latest.getString("version_name");
                                String downloadUrl = latest.getString("download_url");
                                String updateLog = latest.optString("update_log", "暂无更新日志");
                                showUpdateBlockDialog(latestName, updateLog, downloadUrl);
                            } else {
                                performLogin(username, password);
                            }
                        } else {
                            // 已是最新版本，继续登录
                            performLogin(username, password);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "解析版本检查响应失败", e);
                        // 解析失败时不阻塞登录
                        performLogin(username, password);
                    }
                },
                error -> {
                    Log.e(TAG, "版本检查网络错误", error);
                    // 网络错误时不阻塞登录
                    performLogin(username, password);
                }) {
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                params.put("app_name", appName);
                params.put("package_name", packageName);
                params.put("current_version_code", String.valueOf(versionCode));
                return params;
            }

            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/x-www-form-urlencoded");
                return headers;
            }
        };

        requestQueue.add(request);
    }

    private void showUpdateBlockDialog(String versionName, String updateLog, String downloadUrl) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("发现新版本 " + versionName);
            builder.setMessage("更新内容：\n" + updateLog + "\n\n请更新至最新版本后再登录");
            builder.setCancelable(false);
            builder.setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
                    startActivity(intent);
                }
            });
            builder.show();
        } catch (Exception e) {
            Log.e(TAG, "显示版本更新对话框失败", e);
        }
    }

    private void setupClickListeners() {
        try {
            btnLogin.setOnClickListener(v -> attemptLogin());

            tvForgotPassword.setOnClickListener(v -> {
                Intent intent = new Intent(LoginActivity.this, ForgotPasswordActivity.class);
                startActivity(intent);
            });

            tvRegister.setOnClickListener(v -> {
                Intent intent = new Intent(LoginActivity.this, RegisterActivity.class);
                startActivity(intent);
            });

            tilUsername.setEndIconOnClickListener(v -> etUsername.setText(""));
            Log.d(TAG, "点击监听器设置完成");
        } catch (Exception e) {
            Log.e(TAG, "设置点击监听器失败", e);
        }
    }

    private void attemptLogin() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastLoginAttemptTime < AppConfig.LOGIN_DEBOUNCE_MS) {
                showToast("请勿频繁操作");
                return;
            }

            final String username = etUsername.getText().toString().trim();
            String password = etPassword.getText().toString().trim();

            tilUsername.setErrorEnabled(false);
            tilPassword.setErrorEnabled(false);

            if (TextUtils.isEmpty(username)) {
                tilUsername.setError("请输入用户名");
                etUsername.requestFocus();
                return;
            }

            if (!InputValidator.isValidUsername(username)) {
                tilUsername.setError("用户名格式不正确：5-15位，只能包含大小写字母和数字");
                etUsername.requestFocus();
                return;
            }

            if (TextUtils.isEmpty(password)) {
                tilPassword.setError("请输入密码");
                etPassword.requestFocus();
                return;
            }

            if (!InputValidator.isValidPassword(password)) {
                tilPassword.setError("密码格式不正确：最低五位数，最高十位数，支持字母、数字和符号");
                etPassword.requestFocus();
                return;
            }

            lastLoginAttemptTime = now;
            // 输入验证通过后，先检测版本更新
            checkUpdateBeforeLogin(username, password);
        } catch (Exception e) {
            Log.e(TAG, "登录尝试过程中发生异常", e);
            Toast.makeText(this, "登录过程发生错误，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void performLogin(final String username, final String password) {
        try {
            showProgress(true);

            StringRequest stringRequest = new StringRequest(Request.Method.POST, AppConfig.LOGIN_URL,
                    response -> {
                        Log.d(TAG, "登录响应已收到 (size=" + (response != null ? response.length() : 0) + ")");
                        handleLoginResponse(response, username, password);
                    },
                    error -> {
                        showProgress(false);
                        String errorMessage = "网络连接错误，请检查网络设置";
                        if (error.networkResponse != null) {
                            errorMessage = "网络请求失败，响应码: " + error.networkResponse.statusCode;
                        }
                        showToast(errorMessage);
                        Log.e(TAG, "登录网络错误", error);
                    }) {
                @Override
                protected Map<String, String> getParams() {
                    Map<String, String> params = new HashMap<>();
                    params.put("username", username);
                    params.put("password", password);
                    return params;
                }

                @Override
                public Map<String, String> getHeaders() {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/x-www-form-urlencoded");
                    return headers;
                }
            };

            requestQueue.add(stringRequest);

        } catch (Exception e) {
            Log.e(TAG, "执行登录请求时发生异常", e);
            showProgress(false);
            Toast.makeText(this, "登录请求失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleLoginResponse(String response, String username, String password) {
        try {
            JSONObject jsonResponse = new JSONObject(response);
            boolean success = jsonResponse.getBoolean("success");

            if (success) {
                int userId = extractUserIdFromResponse(jsonResponse);
                if (userId > 0) {
                    saveLoginInfo(username, password, userId);
                    // 保存头像
                    if (jsonResponse.has("avatar_url") && !jsonResponse.isNull("avatar_url")) {
                        spManager.setAvatar(jsonResponse.getString("avatar_url"));
                    }
                    // 保存邮箱
                    if (jsonResponse.has("email") && !jsonResponse.isNull("email")) {
                        spManager.setEmail(jsonResponse.getString("email"));
                    }
                    // 保存 token
                    if (jsonResponse.has("access_token")) {
                        String accessToken = jsonResponse.getString("access_token");
                        String refreshToken = jsonResponse.getString("refresh_token");
                        String accessExpires = jsonResponse.getString("access_expires_at");
                        String refreshExpires = jsonResponse.getString("refresh_expires_at");
                        // 同步写入 TokenManager,确保 TokenManager.refreshAccessTokenSync() 也能读到
                        TokenManager.getInstance(LoginActivity.this).saveTokens(accessToken, refreshToken, accessExpires, refreshExpires);
                    }
                    showToast("登录成功");
                    navigateToMainActivity();
                } else {
                    String message = jsonResponse.optString("message", "登录成功但无法获取用户信息");
                    Log.e(TAG, "登录响应中未找到有效的用户ID");
                    showToast(message + "，请联系管理员");
                    showProgress(false);
                }
            } else {
                String message = jsonResponse.optString("message", "账号或密码错误！");
                showToast(message);
                showProgress(false);
            }
        } catch (JSONException e) {
            Log.e(TAG, "登录响应JSON解析失败", e);
            showToast("服务器响应格式错误");
            showProgress(false);
        } catch (Exception e) {
            Log.e(TAG, "处理登录响应时发生错误", e);
            showToast("处理登录响应时发生错误");
            showProgress(false);
        }
    }

    /**
     * 增强版用户ID提取，支持多种常见响应格式
     */
    private int extractUserIdFromResponse(JSONObject jsonResponse) {
        try {
            // 1. 顶层字段
            if (jsonResponse.has("user_id")) {
                int id = jsonResponse.getInt("user_id");
                if (id > 0) {
                    Log.d(TAG, "从 user_id 字段获取用户ID成功");
                    return id;
                }
            }
            if (jsonResponse.has("id")) {
                int id = jsonResponse.getInt("id");
                if (id > 0) {
                    Log.d(TAG, "从 id 字段获取用户ID成功");
                    return id;
                }
            }
            if (jsonResponse.has("userId")) {
                int id = jsonResponse.getInt("userId");
                if (id > 0) {
                    Log.d(TAG, "从 userId 字段获取用户ID成功");
                    return id;
                }
            }

            // 2. data 嵌套对象
            if (jsonResponse.has("data")) {
                JSONObject data = jsonResponse.getJSONObject("data");
                if (data.has("user_id")) {
                    int id = data.getInt("user_id");
                    if (id > 0) {
                        Log.d(TAG, "从 data.user_id 获取用户ID成功");
                        return id;
                    }
                }
                if (data.has("id")) {
                    int id = data.getInt("id");
                    if (id > 0) {
                        Log.d(TAG, "从 data.id 获取用户ID成功");
                        return id;
                    }
                }
                if (data.has("userId")) {
                    int id = data.getInt("userId");
                    if (id > 0) {
                        Log.d(TAG, "从 data.userId 获取用户ID成功");
                        return id;
                    }
                }
            }

            // 3. user 对象（部分接口返回 user 字段）
            if (jsonResponse.has("user")) {
                JSONObject user = jsonResponse.getJSONObject("user");
                if (user.has("id")) {
                    int id = user.getInt("id");
                    if (id > 0) {
                        Log.d(TAG, "从 user.id 获取用户ID成功");
                        return id;
                    }
                }
                if (user.has("user_id")) {
                    int id = user.getInt("user_id");
                    if (id > 0) {
                        Log.d(TAG, "从 user.user_id 获取用户ID成功");
                        return id;
                    }
                }
            }

            Log.w(TAG, "未能从响应中解析到有效用户ID");
        } catch (JSONException e) {
            Log.e(TAG, "解析用户ID时发生错误", e);
        }
        return 0;
    }

    /**
     * 保存登录信息并立即验证
     */
    private void saveLoginInfo(String username, String password, int userId) {
        try {
            Log.d(TAG, "开始保存用户信息 - 用户名: ***, 用户ID: ***");

            spManager.setLoginStatus(true);
            spManager.setUsername(username);
            spManager.setPassword(password);
            spManager.setUserId(userId);
            spManager.setLoginTime(System.currentTimeMillis());

            // 强制同步写入，确保数据持久化
            boolean isValid = spManager.isUserDataValid();
            Log.d(TAG, "用户数据验证结果: " + isValid);

            if (!isValid) {
                Log.e(TAG, "用户数据保存后验证失败！尝试重新保存...");
                // 再次尝试保存
                spManager.clearLoginInfo();
                spManager.setLoginStatus(true);
                spManager.setUsername(username);
                spManager.setPassword(password);
                spManager.setUserId(userId);
                spManager.setLoginTime(System.currentTimeMillis());

                isValid = spManager.isUserDataValid();
                if (!isValid) {
                    Log.e(TAG, "重新保存后仍然失败，请检查 SharedPreferences 是否可用");
                    showToast("保存登录信息失败，请稍后重试");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "保存登录信息时发生异常", e);
            spManager.clearLoginInfo();
        }
    }

    private void navigateToMainActivity() {
        try {
            showProgress(false);

            if (!spManager.isUserDataValid()) {
                Log.e(TAG, "跳转前验证失败：用户数据不完整");
                showToast("用户信息保存失败，请重新登录");
                spManager.clearLoginInfo();
                return;
            }

            Log.d(TAG, "准备跳转到主界面");

            Intent intent = new Intent(LoginActivity.this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "跳转到主界面时发生异常", e);
            Toast.makeText(this, "跳转失败，请重启应用", Toast.LENGTH_LONG).show();
        }
    }

    private void openWebPage(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            showToast("无法打开链接，请检查网络连接");
            Log.e(TAG, "打开网页失败", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == AppUpdateManager.getRequestInstallPermissionCode()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (resultCode == RESULT_OK && getPackageManager().canRequestPackageInstalls()) {
                    Log.d(TAG, "Install permission granted");
                    if (updateManager != null) {
                        updateManager.retryDownload();
                    }
                } else {
                    Log.w(TAG, "Install permission denied");
                    Toast.makeText(this, "安装权限被拒绝，无法自动更新应用", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void showProgress(boolean show) {
        try {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            btnLogin.setEnabled(!show);
            btnLogin.setAlpha(show ? 0.5f : 1.0f);
        } catch (Exception e) {
            Log.e(TAG, "显示/隐藏进度条失败", e);
        }
    }

    private void showToast(String message) {
        try {
            Toast.makeText(LoginActivity.this, message, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "显示Toast失败: " + message, e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        try {
            if (requestQueue != null) {
                requestQueue.cancelAll(this);
            }
            if (updateManager != null) {
                updateManager.destroy();
            }
        } catch (Exception e) {
            Log.e(TAG, "取消请求队列时发生异常", e);
        }
    }
}