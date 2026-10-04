package com.cloud.dex;

import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import okhttp3.*;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;

public class ResetPasswordActivity extends AppCompatActivity {

    private TextView tvUserId, tvUsername, tvEmail;
    private com.google.android.material.textfield.TextInputEditText etNewPassword, etConfirmPassword;
    private Button btnResetPassword;
    private ProgressDialog progressDialog;

    private int userId;
    private String username, email;

    // URL unified: AppConfig.UPDATE_PASSWORD_URL
    private final OkHttpClient client = new OkHttpClient();

    private static final String TAG = "ResetPasswordActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reset_password);

        initViews();
        getUserInfoFromIntent();
        setupClickListeners();
        // 设置ActionBar背景为白色，标题为黑色
        setupActionBar();

    }

    private void setupActionBar() {
        try {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.card_bg)));

                SpannableString title = new SpannableString("重置密码");
                title.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.text_black)), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                getSupportActionBar().setTitle(title);
                getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置ActionBar失败", e);
        }
    }





    private void initViews() {
        tvUserId = findViewById(R.id.tvUserId);
        tvUsername = findViewById(R.id.tvUsername);
        tvEmail = findViewById(R.id.tvEmail);
        etNewPassword = findViewById(R.id.etNewPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        btnResetPassword = findViewById(R.id.btnResetPassword);

        // 初始化加载对话框
        progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("正在重置密码...");
        progressDialog.setCancelable(false);
    }

    private void getUserInfoFromIntent() {
        Intent intent = getIntent();
        userId = intent.getIntExtra("user_id", -1);
        username = intent.getStringExtra("username");
        email = intent.getStringExtra("email");

        if (userId <= 0 || username == null || email == null) {
            showToast("用户信息不完整，请重新操作");
            finish();
            return;
        }

        // 显示用户信息
        tvUserId.setText(String.valueOf(userId));
        tvUsername.setText(username);
        tvEmail.setText(email);
    }

    private void setupClickListeners() {
        btnResetPassword.setOnClickListener(v -> attemptResetPassword());
    }

    private void attemptResetPassword() {
        String newPassword = etNewPassword.getText().toString().trim();
        String confirmPassword = etConfirmPassword.getText().toString().trim();

        // 验证输入
        if (newPassword.isEmpty()) {
            showToast("请输入新密码");
            return;
        }

        if (confirmPassword.isEmpty()) {
            showToast("请确认新密码");
            return;
        }

        if (!newPassword.equals(confirmPassword)) {
            showToast("两次输入的密码不一致");
            return;
        }

        if (newPassword.length() < 6) {
            showToast("密码长度至少6位");
            return;
        }

        // 执行重置密码
        resetPassword(userId, newPassword);
    }

    private void resetPassword(int userId, String newPassword) {
        progressDialog.show();

        RequestBody formBody = new FormBody.Builder()
                .add("user_id", String.valueOf(userId))
                .add("new_password", newPassword)
                .build();

        Request request = new Request.Builder()
                .url(TokenAuthHelper.appendTokenToUrl(AppConfig.UPDATE_PASSWORD_URL, ResetPasswordActivity.this))
                .post(formBody)
                .build();

        Log.d("ResetPassword", "发送请求: user_id=***, new_password=***");

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("ResetPassword", "网络请求失败", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    progressDialog.dismiss();
                    showToast("网络请求失败: " + e.getMessage());
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body().string();
                Log.d("ResetPassword", "收到响应 (size=" + responseBody.length() + ")");
                Log.d("ResetPassword", "响应码: " + response.code());

                new Handler(Looper.getMainLooper()).post(() -> {
                    progressDialog.dismiss();
                    handleResetResponse(responseBody);
                });
            }
        });
    }

    private void handleResetResponse(String response) {
        try {
            Log.d("ResetPassword", "开始解析响应 (size=" + response.length() + ")");

            JSONObject jsonResponse = new JSONObject(response);

            if (!jsonResponse.has("code")) {
                showToast("响应格式错误: 缺少code字段");
                return;
            }

            int code = jsonResponse.getInt("code");
            String message = jsonResponse.getString("message");

            if (code == 200) {
                showToast("密码重置成功");
                // 跳转到登录界面
                new Handler().postDelayed(() -> {
                    Intent intent = new Intent(ResetPasswordActivity.this, LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                }, 1000);
            } else {
                showToast("重置失败: " + message);
            }
        } catch (JSONException e) {
            Log.e("ResetPassword", "JSON解析错误", e);
            showToast("响应解析错误: " + e.getMessage());
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }
}