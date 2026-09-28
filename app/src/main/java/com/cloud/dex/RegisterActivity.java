package com.cloud.dex;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

public class RegisterActivity extends AppCompatActivity {

    private TextInputEditText etUsername, etPassword, etEmail, etVerificationCode;
    private Button btnSendCode, btnRegister;
    private TextView tvLogin;
    private CountDownTimer countDownTimer;
    private boolean isCountingDown = false;

    // URL unified: use AppConfig.BASE_URL + "/register/"
    private RequestQueue requestQueue;
    private String sessionId;

    private static final String TAG = "RegisterActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        // 初始化Volley请求队列
        requestQueue = Volley.newRequestQueue(this);

        initViews();
        setupClickListeners();
        // 设置ActionBar背景为白色，标题为黑色
        setupActionBar();
    }

    private void setupActionBar() {
        try {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.card_bg)));

                SpannableString title = new SpannableString("注册账号");
                title.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.text_black)), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                getSupportActionBar().setTitle(title);
                getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置ActionBar失败", e);
        }
    }

    private void initViews() {
        // 初始化输入框
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        etEmail = findViewById(R.id.etEmail);
        etVerificationCode = findViewById(R.id.etVerificationCode);

        // 初始化其他视图
        btnSendCode = findViewById(R.id.btnSendCode);
        btnRegister = findViewById(R.id.btnRegister);
        tvLogin = findViewById(R.id.tvLogin);
    }

    private void setupClickListeners() {
        // 发送验证码按钮
        btnSendCode.setOnClickListener(v -> {
            if (validateEmailForCode()) {
                sendVerificationCode();
            }
        });






        // 注册按钮
        btnRegister.setOnClickListener(v -> {
            if (validateAllFields()) {
                registerUser();
            }
        });

        // 登录链接
        tvLogin.setOnClickListener(v -> {
            // 跳转到登录页面
            Intent intent = new Intent(RegisterActivity.this, LoginActivity.class);
            startActivity(intent);
            finish();
        });

        // 输入框焦点变化监听
        setupFocusChangeListeners();
    }

    private void setupFocusChangeListeners() {
        View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
            if (!hasFocus) {
                if (v == etUsername) validateUsername();
                else if (v == etPassword) validatePassword();
                else if (v == etEmail) validateEmail();
                else if (v == etVerificationCode) validateVerificationCode();
            }
        };

        etUsername.setOnFocusChangeListener(focusListener);
        etPassword.setOnFocusChangeListener(focusListener);
        etEmail.setOnFocusChangeListener(focusListener);
        etVerificationCode.setOnFocusChangeListener(focusListener);
    }

    private boolean validateUsername() {
        String username = etUsername.getText().toString().trim();
        return !TextUtils.isEmpty(username) && username.length() >= 3;
    }

    private boolean validatePassword() {
        String password = etPassword.getText().toString().trim();
        return !TextUtils.isEmpty(password) && password.length() >= 6;
    }

    private boolean validateEmail() {
        String email = etEmail.getText().toString().trim();
        return !TextUtils.isEmpty(email) && isValidEmail(email);
    }

    private boolean validateEmailForCode() {
        String email = etEmail.getText().toString().trim();
        if (TextUtils.isEmpty(email)) {
            showToast("请输入邮箱地址", Toast.LENGTH_SHORT);
            return false;
        }
        if (!isValidEmail(email)) {
            showToast("请输入有效的邮箱地址", Toast.LENGTH_SHORT);
            return false;
        }
        return true;
    }

    private boolean validateVerificationCode() {
        String code = etVerificationCode.getText().toString().trim();
        if (TextUtils.isEmpty(code)) {
            return false;
        }
        if (code.length() != 6) {
            showToast("验证码必须是6位数字", Toast.LENGTH_SHORT);
            return false;
        }
        return true;
    }

    private boolean validateAllFields() {
        if (TextUtils.isEmpty(etUsername.getText())) {
            showToast("请输入用户名", Toast.LENGTH_SHORT);
            return false;
        }
        if (TextUtils.isEmpty(etPassword.getText())) {
            showToast("请输入密码", Toast.LENGTH_SHORT);
            return false;
        }
        if (TextUtils.isEmpty(etEmail.getText())) {
            showToast("请输入邮箱地址", Toast.LENGTH_SHORT);
            return false;
        }
        if (TextUtils.isEmpty(etVerificationCode.getText())) {
            showToast("请输入验证码", Toast.LENGTH_SHORT);
            return false;
        }
        return validateUsername() && validatePassword() && validateEmail() && validateVerificationCode();
    }

    private void sendVerificationCode() {
        String email = etEmail.getText().toString().trim();

        // 显示发送中状态
        btnSendCode.setEnabled(false);
        btnSendCode.setText("发送中...");

        try {
            JsonObjectRequest request = new JsonObjectRequest(
                    Request.Method.POST,
                    AppConfig.BASE_URL + "/register/register_api.php",
                    createSendCodeJson(email),
                    response -> {
                        try {
                            Log.d("RegisterActivity", "验证码发送响应: " + InputValidator.sanitizeForLog(response.toString()));

                            if (response.getBoolean("success")) {
                                showToast("验证码已发送到您的邮箱", Toast.LENGTH_LONG);
                                startCountDownTimer();

                                // 保存调试信息
                                if (response.has("debug")) {
                                    JSONObject debug = response.getJSONObject("debug");
                                    if (debug.has("session_id")) {
                                        sessionId = debug.getString("session_id");
                                        Log.d("RegisterActivity", "保存sessionId: ***");
                                    }
                                }
                            } else {
                                String errorMsg = response.optString("error", "未知错误");
                                showToast(errorMsg, Toast.LENGTH_LONG);
                                btnSendCode.setEnabled(true);
                                btnSendCode.setText("发送验证码");
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                            showToast("响应解析错误", Toast.LENGTH_SHORT);
                            btnSendCode.setEnabled(true);
                            btnSendCode.setText("发送验证码");
                        }
                    },
                    error -> {
                        showToast("网络请求失败: " + getVolleyErrorMessage(error), Toast.LENGTH_SHORT);
                        btnSendCode.setEnabled(true);
                        btnSendCode.setText("发送验证码");
                    }
            ) {
                @Override
                public Map<String, String> getHeaders() {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/json; charset=utf-8");
                    headers.put("Accept", "application/json");
                    // 如果已有sessionId，则添加到请求头
                    if (sessionId != null) {
                        headers.put("Cookie", "PHPSESSID=" + sessionId);
                    }
                    return headers;
                }
            };

            requestQueue.add(request);
        } catch (Exception e) {
            e.printStackTrace();
            showToast("请求创建失败", Toast.LENGTH_SHORT);
            btnSendCode.setEnabled(true);
            btnSendCode.setText("发送验证码");
        }
    }

    private void startCountDownTimer() {
        countDownTimer = new CountDownTimer(60000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                isCountingDown = true;
                btnSendCode.setText(String.format("重新发送(%ds)", millisUntilFinished / 1000));
            }

            @Override
            public void onFinish() {
                isCountingDown = false;
                btnSendCode.setEnabled(true);
                btnSendCode.setText("发送验证码");
            }
        }.start();
    }

    private void registerUser() {
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        String code = etVerificationCode.getText().toString().trim();

        if (!validateAllFields()) {
            return;
        }

        // 显示注册中状态
        btnRegister.setEnabled(false);
        btnRegister.setText("注册中...");

        try {
            JsonObjectRequest request = new JsonObjectRequest(
                    Request.Method.POST,
                    AppConfig.BASE_URL + "/register/register_api.php",
                    createRegisterJson(username, password, email, code),
                    response -> {
                        btnRegister.setEnabled(true);
                        btnRegister.setText("注        册");

                        try {
                            // 打印服务器响应以便调试
                            Log.d("RegisterActivity", "服务器响应: " + InputValidator.sanitizeForLog(response.toString()));

                            if (response.getBoolean("success")) {
                                showToast("注册成功！", Toast.LENGTH_LONG);
                                // 跳转到登录页面
                                new Handler().postDelayed(() -> {
                                    Intent intent = new Intent(RegisterActivity.this, LoginActivity.class);
                                    startActivity(intent);
                                    finish();
                                }, 2000);
                            } else {
                                String errorMsg = response.optString("error", "未知错误");
                                // 显示详细的错误信息，包括调试信息
                                if (response.has("debug")) {
                                    JSONObject debug = response.getJSONObject("debug");
                                    Log.e("RegisterActivity", "调试信息: " + InputValidator.sanitizeForLog(debug.toString()));
                                    // 如果有session信息，更新sessionId
                                    if (debug.has("session_id")) {
                                        sessionId = debug.getString("session_id");
                                    }
                                }
                                showToast(errorMsg, Toast.LENGTH_LONG);
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                            showToast("响应解析错误", Toast.LENGTH_SHORT);
                        }
                    },
                    error -> {
                        btnRegister.setEnabled(true);
                        btnRegister.setText("注        册");

                        // 详细错误处理
                        if (error.networkResponse != null && error.networkResponse.data != null) {
                            try {
                                String responseBody = new String(error.networkResponse.data, "utf-8");
                                Log.e("RegisterActivity", "服务器返回原始数据: " + InputValidator.sanitizeForLog(responseBody));

                                if (responseBody.contains("<html") || responseBody.contains("<!DOCTYPE")) {
                                    showToast("服务器返回HTML页面，可能是URL错误或服务器配置问题", Toast.LENGTH_LONG);
                                } else {
                                    showToast("服务器响应格式错误: " + responseBody.substring(0, Math.min(100, responseBody.length())), Toast.LENGTH_LONG);
                                }
                            } catch (UnsupportedEncodingException e) {
                                e.printStackTrace();
                                showToast("网络响应解析失败", Toast.LENGTH_SHORT);
                            }
                        } else {
                            showToast("网络请求失败: " + getVolleyErrorMessage(error), Toast.LENGTH_SHORT);
                        }
                    }
            ) {
                @Override
                public Map<String, String> getHeaders() {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/json; charset=utf-8");
                    headers.put("Accept", "application/json");
                    // 如果已有sessionId，则添加到请求头
                    if (sessionId != null) {
                        headers.put("Cookie", "PHPSESSID=" + sessionId);
                        Log.d("RegisterActivity", "注册请求携带Cookie: PHPSESSID=***");
                    }
                    return headers;
                }
            };

            requestQueue.add(request);
        } catch (Exception e) {
            e.printStackTrace();
            showToast("请求创建失败", Toast.LENGTH_SHORT);
            btnRegister.setEnabled(true);
            btnRegister.setText("注        册");
        }
    }

    private JSONObject createSendCodeJson(String email) {
        JSONObject json = new JSONObject();
        try {
            json.put("action", "send_verification_code");
            json.put("email", email);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }

    private JSONObject createRegisterJson(String username, String password, String email, String code) {
        JSONObject json = new JSONObject();
        try {
            json.put("action", "register");
            json.put("username", username);
            json.put("password", password);
            json.put("email", email);
            json.put("verification_code", code);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }

    private boolean isValidEmail(String email) {
        String emailPattern = "[a-zA-Z0-9._-]+@[a-z]+\\.+[a-z]+";
        return email.matches(emailPattern);
    }

    private String getVolleyErrorMessage(com.android.volley.VolleyError error) {
        if (error.networkResponse != null) {
            return "服务器错误: " + error.networkResponse.statusCode;
        } else if (error.getMessage() != null) {
            return error.getMessage();
        } else {
            return "未知网络错误";
        }
    }

    private void showToast(String message, int duration) {
        Toast.makeText(this, message, duration).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        if (requestQueue != null) {
            requestQueue.cancelAll(this);
        }
    }
}