package com.cloud.dex;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.material.textfield.TextInputEditText;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

public class ForgotPasswordActivity extends AppCompatActivity {

    private static final String TAG = "ForgotPasswordActivity";

    private TextInputEditText etEmail, etVerificationCode;
    private Button btnSendCode, btnConfirm;
    private RequestQueue requestQueue;

    private CountDownTimer countDownTimer;
    private boolean isCountingDown = false;
    private String sessionId;

    // URL unified: AppConfig.FORGOT_PASSWORD_URL

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        initViews();
        setupClickListeners();
        // 设置ActionBar背景为白色，标题为黑色
        setupActionBar();

        requestQueue = Volley.newRequestQueue(this);
    }
    private void setupActionBar() {
        try {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.card_bg)));

                SpannableString title = new SpannableString("找回密码");
                title.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.text_black)), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                getSupportActionBar().setTitle(title);
                getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置ActionBar失败", e);
        }
    }


    private void initViews() {
        // 只初始化 TextInputEditText 和按钮，不再使用 TextInputLayout
        etEmail = findViewById(R.id.etEmail);
        etVerificationCode = findViewById(R.id.etVerificationCode);
        btnSendCode = findViewById(R.id.btnSendCode);
        btnConfirm = findViewById(R.id.btnConfirm);
    }

    private void setupClickListeners() {
        // 发送验证码按钮点击
        btnSendCode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendVerificationCode();
            }
        });

        // 确认按钮点击
        btnConfirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                verifyCodeAndProceed();
            }
        });
    }

    private void sendVerificationCode() {
        String email = etEmail.getText().toString().trim();

        if (TextUtils.isEmpty(email)) {
            etEmail.setError("请输入邮箱地址");
            etEmail.requestFocus();
            return;
        }

        if (!isValidEmail(email)) {
            etEmail.setError("邮箱格式不正确");
            etEmail.requestFocus();
            return;
        }

        // 清除错误状态
        etEmail.setError(null);

        // 禁用发送按钮并开始倒计时
        startCountDownTimer();

        // 创建请求JSON - 修正为发送验证码的action
        JSONObject requestJson = new JSONObject();
        try {
            requestJson.put("action", "send_verification_code"); // 确保action正确
            requestJson.put("email", email);
        } catch (JSONException e) {
            Log.e(TAG, "创建JSON请求失败", e);
            resetCountDownTimer(); // 出错时重置按钮
            return;
        }

        // 发送验证码请求
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST,
                AppConfig.FORGOT_PASSWORD_URL,
                requestJson,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        Log.d(TAG, "发送验证码响应已收到");
                        try {
                            boolean success = response.getBoolean("success");
                            String message = response.getString("message");

                            if (success) {
                                Toast.makeText(ForgotPasswordActivity.this, "验证码发送成功", Toast.LENGTH_SHORT).show();
                                // 保存session信息用于调试
                                if (response.has("debug")) {
                                    JSONObject debug = response.getJSONObject("debug");
                                    if (debug.has("session_id")) {
                                        sessionId = debug.getString("session_id");
                                        Log.d(TAG, "收到sessionId: ***");
                                    }
                                }
                            } else {
                                Toast.makeText(ForgotPasswordActivity.this, message, Toast.LENGTH_SHORT).show();
                                resetCountDownTimer(); // 发送失败时重置按钮
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析发送验证码响应失败", e);
                            Toast.makeText(ForgotPasswordActivity.this, "验证码发送失败", Toast.LENGTH_SHORT).show();
                            resetCountDownTimer(); // 解析失败时重置按钮
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "发送验证码网络错误", error);
                        Toast.makeText(ForgotPasswordActivity.this, "网络连接错误，请重试", Toast.LENGTH_SHORT).show();
                        resetCountDownTimer(); // 网络错误时重置按钮
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
                    Log.d(TAG, "发送请求时携带sessionId: " + sessionId);
                }
                return headers;
            }
        };

        requestQueue.add(jsonObjectRequest);
    }
    private void startCountDownTimer() {
        isCountingDown = true;
        btnSendCode.setEnabled(false);
        btnSendCode.setBackgroundResource(R.drawable.button_disabled_background);

        countDownTimer = new CountDownTimer(60000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                btnSendCode.setText("重新发送(" + millisUntilFinished / 1000 + "s)");
            }

            @Override
            public void onFinish() {
                resetCountDownTimer();
            }
        }.start();
    }

    private void resetCountDownTimer() {
        isCountingDown = false;
        btnSendCode.setEnabled(true);
        btnSendCode.setText("发送验证码");
        btnSendCode.setBackgroundResource(R.drawable.button_code_background);

        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }

    private void verifyCodeAndProceed() {
        String email = etEmail.getText().toString().trim();
        String code = etVerificationCode.getText().toString().trim();

        if (TextUtils.isEmpty(email)) {
            etEmail.setError("请输入邮箱地址");
            etEmail.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(code)) {
            etVerificationCode.setError("请输入验证码");
            etVerificationCode.requestFocus();
            return;
        }

        // 清除错误状态
        etEmail.setError(null);
        etVerificationCode.setError(null);

        // 创建请求JSON
        JSONObject requestJson = new JSONObject();
        try {
            requestJson.put("action", "verify_code");
            requestJson.put("email", email);
            requestJson.put("verification_code", code);
        } catch (JSONException e) {
            Log.e(TAG, "创建JSON请求失败", e);
            return;
        }

        // 验证验证码
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST,
                AppConfig.FORGOT_PASSWORD_URL,
                requestJson,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        Log.d(TAG, "验证验证码响应已收到");
                        try {
                            boolean success = response.getBoolean("success");
                            String message = response.getString("message");

                            if (success) {
                                // 验证成功，获取用户信息并跳转到重置密码界面
                                JSONObject userInfo = response.getJSONObject("user_info");
                                String userId = userInfo.getString("id");
                                String username = userInfo.getString("username");
                                String userEmail = userInfo.getString("email");

                                // 跳转到重置密码界面
                                Intent intent = new Intent(ForgotPasswordActivity.this, ResetPasswordActivity.class);
                                intent.putExtra("email", userEmail);
                                intent.putExtra("user_id", Integer.parseInt(userId));
                                intent.putExtra("username", username);
                                startActivity(intent);

                                Toast.makeText(ForgotPasswordActivity.this, "验证成功，请设置新密码", Toast.LENGTH_SHORT).show();
                            } else {
                                etVerificationCode.setError(message);
                                etVerificationCode.requestFocus();

                                // 如果有调试信息，记录到日志
                                if (response.has("debug")) {
                                    Log.d(TAG, "验证失败: " + response.optString("message", ""));
                                }
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析验证验证码响应失败", e);
                            Toast.makeText(ForgotPasswordActivity.this, "验证失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "验证验证码网络错误", error);
                        Toast.makeText(ForgotPasswordActivity.this, "网络连接错误，请重试", Toast.LENGTH_SHORT).show();
                    }
                }
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json; charset=utf-8");
                headers.put("Accept", "application/json");
                // 确保验证请求也携带相同的sessionId
                if (sessionId != null) {
                    headers.put("Cookie", "PHPSESSID=" + sessionId);
                    Log.d(TAG, "验证请求携带sessionId: " + sessionId);
                }
                return headers;
            }
        };

        requestQueue.add(jsonObjectRequest);
    }

    private boolean isValidEmail(String email) {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches();
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