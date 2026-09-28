package com.cloud.dex;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.request.RequestOptions;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.FormBody;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ProfileActivity extends AppCompatActivity {

    private static final String TAG = "ProfileActivity";
    private static final int PICK_IMAGE_REQUEST = 100;

    private SharedPreferencesManager spManager;
    private ImageView ivAvatar;
    private TextView tvChangeAvatar;
    private TextView tvUsername;
    private TextView tvCardMembership;
    private TextView tvVersionName;
    private OkHttpClient okHttpClient;

    // URL unified: AppConfig.UPLOAD_AVATAR_URL

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        spManager = new SharedPreferencesManager(this);

        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        setupActionBar();
        initViews();
        loadUserInfo();
        loadAvatar();
    }

    private void setupActionBar() {
        try {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.card_bg)));

                SpannableString title = new SpannableString("我的");
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

    private void initViews() {
        ivAvatar = findViewById(R.id.ivAvatar);
        tvChangeAvatar = findViewById(R.id.tvChangeAvatar);
        tvUsername = findViewById(R.id.tvProfileUsername);
        tvCardMembership = findViewById(R.id.tvCardMembership);
        tvVersionName = findViewById(R.id.tvVersionName);
        tvVersionName.setText("v" + getVersionName());
        tvVersionName.setOnClickListener(v -> checkVersionUpdate());

        View.OnClickListener pickAvatarListener = v -> openImagePicker();
        ivAvatar.setOnClickListener(pickAvatarListener);
        tvChangeAvatar.setOnClickListener(pickAvatarListener);

        findViewById(R.id.cardMembership).setOnClickListener(v -> openMembershipPage());
        findViewById(R.id.cardAbout).setOnClickListener(v -> startActivity(new Intent(ProfileActivity.this, AboutCloudActivity.class)));
        findViewById(R.id.cardEditInfo).setOnClickListener(v -> showChangePasswordDialog());
        findViewById(R.id.cardDexInject).setOnClickListener(v -> startActivity(new Intent(ProfileActivity.this, DexInjectActivity.class)));
        findViewById(R.id.cardLogout).setOnClickListener(v -> showLogoutConfirm());
    }

    private void loadUserInfo() {
        String username = spManager.getUsername();
        String membershipType = spManager.getMembershipType();

        tvUsername.setText(username.isEmpty() ? "用户" : username);
        String displayMembership = (membershipType == null || membershipType.isEmpty()) ? "免费用户" : membershipType;
        tvCardMembership.setText(displayMembership);

        // 根据会员类型设置右侧等级图标
        if (!TextUtils.isEmpty(membershipType)) {
            int iconResId = getMemberLevelIcon(membershipType);
            tvCardMembership.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, iconResId, 0);
            tvCardMembership.setCompoundDrawablePadding(4);
        } else {
            tvCardMembership.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
        }
    }

    private void loadAvatar() {
        String avatarUrl = spManager.getAvatar();
        Log.d(TAG, "加载头像URL: " + avatarUrl);
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            Glide.with(this)
                    .load(avatarUrl)
                    .apply(RequestOptions.bitmapTransform(new CircleCrop())
                            .placeholder(R.drawable.ic_default_avatar)
                            .error(R.drawable.ic_default_avatar))
                    .into(ivAvatar);
        } else {
            ivAvatar.setImageResource(R.drawable.ic_default_avatar);
        }
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            uploadAvatar(data.getData());
        }
    }

    private void uploadAvatar(Uri imageUri) {
        int userId = spManager.getUserId();
        if (userId <= 0) {
            showToast("用户信息无效");
            return;
        }

        tvChangeAvatar.setText("上传中...");
        tvChangeAvatar.setEnabled(false);

        try {
            InputStream inputStream = getContentResolver().openInputStream(imageUri);
            if (inputStream == null) {
                showToast("无法读取图片");
                tvChangeAvatar.setText("点击更换头像");
                tvChangeAvatar.setEnabled(true);
                return;
            }

            File tempFile = new File(getCacheDir(), "avatar_upload_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream outputStream = new FileOutputStream(tempFile);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.close();
            inputStream.close();

            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("user_id", String.valueOf(userId))
                    .addFormDataPart("avatar", "avatar.jpg",
                            RequestBody.create(MediaType.parse("image/jpeg"), tempFile))
                    .build();

            Request request = new Request.Builder()
                    .url(TokenAuthHelper.appendTokenToUrl(AppConfig.UPLOAD_AVATAR_URL, ProfileActivity.this))
                    .post(requestBody)
                    .build();

            okHttpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "头像上传失败", e);
                    tempFile.delete();
                    runOnUiThread(() -> {
                        showToast("上传失败: " + e.getMessage());
                        tvChangeAvatar.setText("点击更换头像");
                        tvChangeAvatar.setEnabled(true);
                    });
                }

                @Override
                public void onResponse(Call call, Response response) {
                    tempFile.delete();
                    String responseBody;
                    try {
                        responseBody = response.body() != null ? response.body().string() : "";
                    } catch (IOException e) {
                        Log.e(TAG, "读取响应失败", e);
                        runOnUiThread(() -> {
                            showToast("网络响应异常");
                            tvChangeAvatar.setText("点击更换头像");
                            tvChangeAvatar.setEnabled(true);
                        });
                        return;
                    }
                    Log.d(TAG, "头像上传响应已收到 (size=" + responseBody.length() + ")");

                    try {
                        JSONObject json = new JSONObject(responseBody);
                        if (json.getBoolean("success")) {
                            String avatarUrl = json.getString("avatar_url");
                            spManager.setAvatar(avatarUrl);
                            runOnUiThread(() -> {
                                loadAvatar();
                                showToast("头像上传成功");
                                tvChangeAvatar.setText("点击更换头像");
                                tvChangeAvatar.setEnabled(true);
                            });
                        } else {
                            String message = json.optString("message", "上传失败");
                            runOnUiThread(() -> {
                                showToast(message);
                                tvChangeAvatar.setText("点击更换头像");
                                tvChangeAvatar.setEnabled(true);
                            });
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "解析上传响应失败", e);
                        runOnUiThread(() -> {
                            showToast("服务器响应异常");
                            tvChangeAvatar.setText("点击更换头像");
                            tvChangeAvatar.setEnabled(true);
                        });
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "上传头像异常", e);
            showToast("上传失败");
            tvChangeAvatar.setText("点击更换头像");
            tvChangeAvatar.setEnabled(true);
        }
    }

    private void openMembershipPage() {
        try {
            Intent intent = new Intent(this, MembershipActivity.class);
            intent.putExtra("user_id", spManager.getUserId());
            intent.putExtra("app_id", 0);
            intent.putExtra("username", spManager.getUsername());
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "跳转会员页面失败", e);
            showToast("无法打开会员页面");
        }
    }

    private void showChangePasswordDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("修改密码");

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 10);

        com.google.android.material.textfield.TextInputLayout tilOldPwd = new com.google.android.material.textfield.TextInputLayout(this);
        tilOldPwd.setHint("原密码");
        tilOldPwd.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        com.google.android.material.textfield.TextInputEditText etOldPwd = new com.google.android.material.textfield.TextInputEditText(this);
        etOldPwd.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tilOldPwd.addView(etOldPwd);
        layout.addView(tilOldPwd);

        android.view.ViewGroup.MarginLayoutParams params = (android.view.ViewGroup.MarginLayoutParams) tilOldPwd.getLayoutParams();
        if (params != null) params.bottomMargin = (int) (12 * getResources().getDisplayMetrics().density);
        tilOldPwd.setLayoutParams(params);

        com.google.android.material.textfield.TextInputLayout tilNewPwd = new com.google.android.material.textfield.TextInputLayout(this);
        tilNewPwd.setHint("新密码");
        tilNewPwd.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        com.google.android.material.textfield.TextInputEditText etNewPwd = new com.google.android.material.textfield.TextInputEditText(this);
        etNewPwd.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tilNewPwd.addView(etNewPwd);
        layout.addView(tilNewPwd);

        com.google.android.material.textfield.TextInputLayout tilConfirmPwd = new com.google.android.material.textfield.TextInputLayout(this);
        tilConfirmPwd.setHint("确认新密码");
        tilConfirmPwd.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        com.google.android.material.textfield.TextInputEditText etConfirmPwd = new com.google.android.material.textfield.TextInputEditText(this);
        etConfirmPwd.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tilConfirmPwd.addView(etConfirmPwd);
        layout.addView(tilConfirmPwd);

        builder.setView(layout);
        builder.setPositiveButton("确定", null);
        builder.setNegativeButton("取消", null);

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();

        setupPasswordToggle(tilOldPwd, etOldPwd);
        setupPasswordToggle(tilNewPwd, etNewPwd);
        setupPasswordToggle(tilConfirmPwd, etConfirmPwd);

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String oldPwd = etOldPwd.getText().toString().trim();
            String newPwd = etNewPwd.getText().toString().trim();
            String confirmPwd = etConfirmPwd.getText().toString().trim();

            if (oldPwd.isEmpty() || newPwd.isEmpty() || confirmPwd.isEmpty()) {
                showToast("请填写完整信息");
                return;
            }
            if (!newPwd.equals(confirmPwd)) {
                showToast("两次输入的新密码不一致");
                return;
            }
            if (newPwd.length() < 6) {
                showToast("新密码至少6位");
                return;
            }

            int userId = spManager.getUserId();
            if (userId <= 0) {
                showToast("用户信息无效");
                return;
            }

            dialog.dismiss();
            showToast("修改中...");

            new Thread(() -> {
                try {
                    okhttp3.RequestBody formBody = new okhttp3.FormBody.Builder()
                            .add("user_id", String.valueOf(userId))
                            .add("new_password", newPwd)
                            .build();

                    okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(TokenAuthHelper.appendTokenToUrl(AppConfig.UPDATE_PASSWORD_URL, ProfileActivity.this))
                            .post(formBody)
                            .build();

                    okhttp3.Response response = okHttpClient.newCall(request).execute();
                    String responseBody = response.body() != null ? response.body().string() : "";

                    org.json.JSONObject json = new org.json.JSONObject(responseBody);
                    int code = json.optInt("code", 0);
                    String message = json.optString("message", "修改失败");

                    runOnUiThread(() -> {
                        if (code == 200) {
                            showToast("密码修改成功");
                        } else {
                            showToast(message);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "修改密码失败", e);
                    runOnUiThread(() -> showToast("网络错误"));
                }
            }).start();
        });
    }

    private void setupPasswordToggle(com.google.android.material.textfield.TextInputLayout til,
                                      com.google.android.material.textfield.TextInputEditText et) {
        til.setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE);
    }

    private void showLogoutConfirm() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("退出登录😭")
                .setMessage("确定要退出登录吗？😭")
                .setPositiveButton("确定", (dialog, which) -> logout())
                .setNegativeButton("取消", null)
                .show();
    }

    private void logout() {
        spManager.clearLoginInfo();
        spManager.clearMembershipInfo();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private String getVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName != null ? info.versionName : "1.0.0";
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0.0";
        }
    }

    private String getApplicationName() {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(getPackageName(), 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return "My Application";
        }
    }

    private int getVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    private void checkVersionUpdate() {
        String appName = getApplicationName();
        String packageName = getPackageName();
        int versionCode = getVersionCode();

        showToast("正在检查更新...");

        RequestBody formBody = new FormBody.Builder()
                .add("app_name", appName)
                .add("package_name", packageName)
                .add("current_version_code", String.valueOf(versionCode))
                .build();

        Request request = new Request.Builder()
                .url(AppConfig.CHECK_VERSION_URL)
                .post(formBody)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "检查版本更新失败", e);
                runOnUiThread(() -> showToast("网络错误，检查更新失败"));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(responseBody);

                    if (json.getBoolean("success")) {
                        boolean needsUpdate = json.getBoolean("needs_update");
                        if (needsUpdate) {
                            JSONObject latest = json.getJSONObject("latest_version");
                            String latestName = latest.getString("version_name");
                            String downloadUrl = latest.getString("download_url");
                            String updateLog = latest.getString("update_log");
                            boolean force = latest.optBoolean("force_update", false);

                            runOnUiThread(() -> showUpdateDialog(latestName, updateLog, downloadUrl, force));
                        } else {
                            runOnUiThread(() -> showToast("已是最新版"));
                        }
                    } else {
                        runOnUiThread(() -> showToast("检查更新失败"));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析版本检查响应失败", e);
                    runOnUiThread(() -> showToast("解析失败"));
                }
            }
        });
    }

    private void showUpdateDialog(String latestVersion, String updateLog, String downloadUrl, boolean forceUpdate) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("发现新版本 " + latestVersion)
                .setMessage("更新内容：\n" + updateLog + "\n\n是否立即下载更新？")
                .setPositiveButton("立即更新", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        showToast("无法打开下载链接");
                    }
                })
                .setNegativeButton(forceUpdate ? null : "稍后", null)
                .setCancelable(!forceUpdate)
                .show();
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * 根据会员类型返回对应的等级图标资源ID
     */
    private int getMemberLevelIcon(String membershipType) {
        if (membershipType.contains("基础会员")) {
            return R.drawable.ic_member_level_basic;
        } else if (membershipType.contains("高级会员")) {
            return R.drawable.ic_member_level_premium;
        } else if (membershipType.contains("终身会员")) {
            return R.drawable.ic_member_level_lifetime;
        } else {
            return R.drawable.ic_member_level;
        }
    }
}
