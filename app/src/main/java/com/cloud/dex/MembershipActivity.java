package com.cloud.dex;

import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.cloud.dex.widget.refresh.PtrFrameLayout;

import org.json.JSONException;
import org.json.JSONObject;
import android.content.pm.ResolveInfo;
import java.util.List;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class MembershipActivity extends AppCompatActivity {

    private static final String TAG = "MembershipActivity";
    private SharedPreferencesManager spManager;
    private int userId;
    private String username;
    private String currentMembershipType = "免费用户";
    private String startTime = "";
    private String expireTime = "";

    private PtrFrameLayout swipeRefreshLayout;
    private ProgressDialog progressDialog;
    private Handler handler;
    private Runnable statusCheckRunnable;
    private String currentOrderId = "";

    private AlertDialog currentPaymentDialog;

    // 支付状态管理变量
    private boolean isPaymentInProgress = false;
    private long lastPaymentCheckTime = 0;
    private static final long PAYMENT_TIMEOUT = 30000; // 30秒超时

    // 手动检查状态变量
    private boolean isManualCheck = false;
    private long manualCheckStartTime = 0;
    private static final long MANUAL_CHECK_TIMEOUT = 15000; // 手动检查15秒超时

    // 客服QQ号
    private static final String CUSTOMER_SERVICE_QQ = AppConfig.CUSTOMER_SERVICE_QQ;

    // ===== 新增：会员到期时间动态倒计时 =====
    private Handler countDownHandler = new Handler(Looper.getMainLooper());
    private Runnable countDownRunnable;
    // =========================================

    // ===== 会员价格（从服务器动态获取） =====
    private double basicPrice = 9.9;
    private double premiumPrice = 19.9;
    private double lifetimePrice = 99.9;
    private String basicName = "基础会员";
    private String premiumName = "高级会员";
    private String lifetimeName = "终身会员";
    // =========================================

    // 网络请求回调接口
    public interface ApiCallback {
        void onSuccess(JSONObject response);
        void onError(String error);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_membership);

        // 设置未捕获异常处理器
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "未捕获异常: ", throwable);
        });

        setupActionBar();

        spManager = new SharedPreferencesManager(this);
        handler = new Handler(Looper.getMainLooper());

        userId = spManager.getUserId();
        username = spManager.getUsername();

        Log.d(TAG, "当前用户信息 - 用户ID: ***, 用户名: ***");

        initViews();
        setupSwipeRefresh();
        setupButtonClickListeners();

        // ★ 先从本地缓存加载会员类型，避免短暂显示”免费用户”
        loadLocalMembershipStatus();
        // 再通过网络请求获取最新数据
        loadMembershipStatus();
        // 从服务器获取会员价格
        loadMembershipPrices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次页面可见时刷新会员状态
        loadMembershipStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStatusCheck();
        isManualCheck = false;
        stopCountDown(); // 停止会员到期倒计时

        if (currentPaymentDialog != null && currentPaymentDialog.isShowing()) {
            currentPaymentDialog.dismiss();
            currentPaymentDialog = null;
        }
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    private void setupActionBar() {
        try {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.card_bg)));
                SpannableString title = new SpannableString("会员服务");
                title.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.text_black)), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                getSupportActionBar().setTitle(title);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置ActionBar失败", e);
        }
    }

    private void initViews() {
        // 第一步：初始化 SwipeRefreshLayout
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        if (swipeRefreshLayout == null) {
            Log.e(TAG, "找不到 swipeRefreshLayout，请检查 activity_membership.xml");
            Toast.makeText(this, "界面初始化失败", Toast.LENGTH_SHORT).show();
            finish(); // 或直接关闭 Activity，防止后续使用时再次崩溃
            return;
        }
        swipeRefreshLayout.setColorSchemeColors(
                Color.parseColor("#2196F3"),
                Color.parseColor("#4CAF50"),
                Color.parseColor("#FF9800")
        );

        // 第二步：初始化其他控件
        TextView tvUsername = findViewById(R.id.tvUsername);
        TextView tvUserId = findViewById(R.id.tvUserId);

        if (tvUsername != null) {
            if (username != null && !username.isEmpty()) {
                tvUsername.setText("欢迎，" + username);
            } else {
                tvUsername.setText("欢迎，用户");
            }
        } else {
            Log.e(TAG, "tvUsername 控件未找到");
        }

        if (tvUserId != null) {
            tvUserId.setText("用户ID: " + userId);
        } else {
            Log.e(TAG, "tvUserId 控件未找到");
        }

        // 联系客服的点击事件
        setupContactService();
    }

    /**
     * 设置联系客服功能
     */
    private void setupContactService() {
        try {
            TextView tvContactService = findViewById(R.id.tvContactService);
            tvContactService.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    contactCustomerService();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "设置联系客服失败", e);
        }
    }

    private void setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener(new PtrFrameLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                Log.d(TAG, "下拉刷新触发");
                loadMembershipStatus();
                Toast.makeText(MembershipActivity.this, "正在刷新会员状态...", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupButtonClickListeners() {
        Button btnBasic = findViewById(R.id.btnBasic);
        btnBasic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createAlipayQrPayment(basicName, basicPrice);
            }
        });

        Button btnPremium = findViewById(R.id.btnPremium);
        btnPremium.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createAlipayQrPayment(premiumName, premiumPrice);
            }
        });

        Button btnLifetime = findViewById(R.id.btnLifetime);
        btnLifetime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createAlipayQrPayment(lifetimeName, lifetimePrice);
            }
        });
    }

    /**
     * 检查网络连接
     */
    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager connectivityManager =
                    (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
                return activeNetworkInfo != null && activeNetworkInfo.isConnected();
            }
        } catch (Exception e) {
            Log.e(TAG, "检查网络状态失败", e);
        }
        return false;
    }

    /**
     * 检测用户从支付宝返回的状态
     */
    private void checkPaymentReturnStatus() {
        if (isPaymentInProgress && currentOrderId != null && !currentOrderId.isEmpty()) {
            long currentTime = System.currentTimeMillis();

            if (currentTime - lastPaymentCheckTime < PAYMENT_TIMEOUT) {
                checkPaymentStatusInternal(currentOrderId, false);
                showReturnFromAlipayDialog();
            }
        }
    }

    /**
     * 显示从支付宝返回的提示对话框
     */
    private void showReturnFromAlipayDialog() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    AlertDialog.Builder builder = new AlertDialog.Builder(MembershipActivity.this);
                    builder.setTitle("支付提示")
                            .setMessage("检测到您已从支付宝返回，但支付尚未完成。\n\n请确认是否已完成支付？")
                            .setPositiveButton("我已支付完成", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (currentOrderId != null && !currentOrderId.isEmpty()) {
                                        checkPaymentStatusManually(currentOrderId);
                                    }
                                }
                            })
                            .setNegativeButton("重新支付", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    Toast.makeText(MembershipActivity.this, "请重新选择支付方式", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNeutralButton("取消支付", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    stopStatusCheck();
                                    if (currentPaymentDialog != null && currentPaymentDialog.isShowing()) {
                                        currentPaymentDialog.dismiss();
                                    }
                                    Toast.makeText(MembershipActivity.this, "支付已取消", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setCancelable(false)
                            .show();

                    Log.d(TAG, "显示支付宝返回提示对话框");
                } catch (Exception e) {
                    Log.e(TAG, "显示返回提示对话框失败", e);
                }
            }
        });
    }

    /**
     * 手动检查支付状态（用户点击"我已支付完成"时调用）
     */
    private void checkPaymentStatusManually(final String orderId) {
        if (!isNetworkAvailable()) {
            dismissProgressDialog();
            Toast.makeText(this, "网络不可用，请检查网络连接", Toast.LENGTH_SHORT).show();
            return;
        }

        isManualCheck = true;
        manualCheckStartTime = System.currentTimeMillis();

        showProgressDialog("正在验证支付状态...");

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isManualCheck) {
                    Log.w(TAG, "手动检查支付状态超时");
                    dismissProgressDialog();
                    isManualCheck = false;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showCheckTimeoutDialog(orderId);
                        }
                    });
                }
            }
        }, MANUAL_CHECK_TIMEOUT);

        checkPaymentStatusInternal(orderId, true);
    }

    /**
     * 显示检查超时对话框
     */
    private void showCheckTimeoutDialog(final String orderId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AlertDialog.Builder builder = new AlertDialog.Builder(MembershipActivity.this);
                builder.setTitle("检查超时")
                        .setMessage("支付状态检查超时，可能的原因：\n\n• 网络连接缓慢\n• 服务器响应延迟\n• 支付信息同步中\n\n建议您：\n1. 等待几分钟后再次检查\n2. 确认支付宝扣款记录\n3. 联系客服协助处理")
                        .setPositiveButton("再次检查", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                checkPaymentStatusManually(orderId);
                            }
                        })
                        .setNegativeButton("稍后检查", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Toast.makeText(MembershipActivity.this, "系统会继续在后台检查支付状态", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNeutralButton("联系客服", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                contactCustomerService();
                            }
                        })
                        .setCancelable(false)
                        .show();
            }
        });
    }

    /**
     * 联系客服 - 跳转到QQ应用并打开指定QQ号的个人资料界面
     */
    private void contactCustomerService() {
        try {
            String qqNumber = CUSTOMER_SERVICE_QQ;

            String url = "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=" + qqNumber;

            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));

            if (isQQInstalled()) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setPackage("com.tencent.mobileqq");
                startActivity(intent);
                Log.d(TAG, "跳转到QQ个人资料成功，QQ号: " + qqNumber);
            } else {
                showQQNotInstalledDialog(qqNumber);
            }
        } catch (Exception e) {
            Log.e(TAG, "跳转QQ个人资料失败", e);
            tryAlternativeQQSchemes();
        }
    }

    /**
     * 尝试备选的QQ跳转方案
     */
    private void tryAlternativeQQSchemes() {
        final String qqNumber = CUSTOMER_SERVICE_QQ;

        try {
            String url = "mqq://card/show_pslcard?src_type=internal&uin=" + qqNumber;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                Log.d(TAG, "使用备选方案1跳转QQ个人资料成功");
                return;
            }
        } catch (Exception e1) {
            Log.e(TAG, "备选方案1失败", e1);
        }

        try {
            String url = "mqqapi://card/show_pslcard?uin=" + qqNumber;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                Log.d(TAG, "使用备选方案2跳转QQ个人资料成功");
                return;
            }
        } catch (Exception e2) {
            Log.e(TAG, "备选方案2失败", e2);
        }

        showAlternativeContactMethods();
    }

    /**
     * 检查QQ是否安装
     */
    private boolean isQQInstalled() {
        try {
            PackageManager packageManager = getPackageManager();
            try {
                packageManager.getPackageInfo("com.tencent.mobileqq", PackageManager.GET_ACTIVITIES);
                Log.d(TAG, "QQ包存在");
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                Log.d(TAG, "QQ包不存在");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "检查QQ安装状态失败", e);
            return false;
        }
    }

    /**
     * 显示QQ未安装的对话框
     */
    private void showQQNotInstalledDialog(final String qqNumber) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("QQ未安装")
                .setMessage("检测到您的设备未安装QQ，是否前往下载？\n\n客服QQ: " + qqNumber)
                .setPositiveButton("下载QQ", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent marketIntent = new Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=com.tencent.mobileqq"));
                        try {
                            startActivity(marketIntent);
                        } catch (Exception e) {
                            Intent webIntent = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://im.qq.com"));
                            startActivity(webIntent);
                        }
                    }
                })
                .setNegativeButton("复制QQ号", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyQQToClipboard(qqNumber);
                    }
                })
                .setNeutralButton("取消", null)
                .setCancelable(false)
                .show();
    }

    /**
     * 复制QQ号到剪贴板
     */
    private void copyQQToClipboard(String qqNumber) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("客服QQ号", qqNumber);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "客服QQ号已复制到剪贴板: " + qqNumber, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "复制QQ号到剪贴板失败", e);
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 显示备选联系方案
     */
    private void showAlternativeContactMethods() {
        final String qqNumber = CUSTOMER_SERVICE_QQ;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("联系客服")
                .setMessage("无法自动跳转QQ，请选择联系方式：\n\n客服QQ: " + qqNumber)
                .setPositiveButton("复制QQ号", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyQQToClipboard(qqNumber);
                    }
                })
                .setNeutralButton("手动打开QQ", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showManualQQGuide(qqNumber);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 显示手动打开QQ的指引
     */
    private void showManualQQGuide(final String qqNumber) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("手动联系客服")
                .setMessage("请按以下步骤操作：\n\n1. 打开QQ应用\n2. 点击右上角\"+\"号\n3. 选择\"加好友/群\"\n4. 输入QQ号: " + qqNumber + "\n5. 搜索并添加客服\n\n或者您也可以复制QQ号后手动添加")
                .setPositiveButton("复制QQ号", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyQQToClipboard(qqNumber);
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    /**
     * 处理手动检查时的支付成功
     */
    private void handlePaymentSuccess(String orderId) {
        stopStatusCheck();
        isManualCheck = false;

        dismissProgressDialog();

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (currentPaymentDialog != null && currentPaymentDialog.isShowing()) {
                    currentPaymentDialog.dismiss();
                    currentPaymentDialog = null;
                }

                showPaymentSuccessDialog();

                swipeRefreshLayout.setRefreshing(true);
                loadMembershipStatus();

                Log.d(TAG, "支付成功，订单号: " + orderId);
            }
        });
    }

    /**
     * 处理手动检查时支付未完成
     */
    private void handleManualCheckNotPaid() {
        isManualCheck = false;
        dismissProgressDialog();

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AlertDialog.Builder builder = new AlertDialog.Builder(MembershipActivity.this);
                builder.setTitle("支付状态")
                        .setMessage("尚未检测到支付成功信息\n\n可能的原因：\n• 支付尚未完成\n• 银行处理延迟\n• 网络同步延迟\n\n建议您：\n1. 确认支付宝是否扣款成功\n2. 稍后再次点击\"我已支付\"\n3. 或联系客服确认")
                        .setPositiveButton("再次检查", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (currentOrderId != null && !currentOrderId.isEmpty()) {
                                    checkPaymentStatusManually(currentOrderId);
                                }
                            }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                            }
                        })
                        .setNeutralButton("联系客服", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                contactCustomerService();
                            }
                        })
                        .setCancelable(false)
                        .show();
            }
        });
    }

    /**
     * 处理手动检查错误
     */
    private void handleManualCheckError(String errorMessage) {
        isManualCheck = false;
        dismissProgressDialog();

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AlertDialog.Builder builder = new AlertDialog.Builder(MembershipActivity.this);
                builder.setTitle("检查失败")
                        .setMessage("支付状态检查失败：\n" + errorMessage + "\n\n请检查网络连接后重试")
                        .setPositiveButton("重试", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (currentOrderId != null && !currentOrderId.isEmpty()) {
                                    checkPaymentStatusManually(currentOrderId);
                                }
                            }
                        })
                        .setNegativeButton("取消", null)
                        .setCancelable(false)
                        .show();
            }
        });
    }

    /**
     * 创建支付宝当面付（扫码支付）
     */
    private void createAlipayQrPayment(final String membershipType, final double price) {
        Log.d(TAG, "开始创建支付订单 - 会员类型: " + membershipType + ", 价格: " + price);

        if (!isNetworkAvailable()) {
            Toast.makeText(this, "网络不可用，请检查网络连接", Toast.LENGTH_LONG).show();
            return;
        }

        showProgressDialog("正在生成支付二维码...");

        try {
            final JSONObject params = new JSONObject();
            params.put("user_id", userId);
            params.put("app_id", 0);
            params.put("membership_type", membershipType);
            params.put("amount", price);

            final String apiUrl = AppConfig.CREATE_PAYMENT_URL;
            Log.d(TAG, "请求URL: " + apiUrl);
            Log.d(TAG, "请求参数: " + params.toString());

            post(apiUrl, params, new ApiCallback() {
                @Override
                public void onSuccess(JSONObject response) {
                    Log.d(TAG, "收到服务器响应 (size=" + (response != null ? response.toString().length() : 0) + ")");
                    dismissProgressDialog();
                    try {
                        boolean success = response.getBoolean("success");
                        if (success) {
                            String orderId = response.getString("order_id");
                            String qrCode = response.getString("qr_code");

                            Log.d(TAG, "当面付订单创建成功: " + orderId);

                            showQrCodePaymentDialog(qrCode, orderId, membershipType, price);
                        } else {
                            String errorMsg = response.getString("message");
                            Log.e(TAG, "服务器返回错误: " + errorMsg);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MembershipActivity.this, "创建订单失败: " + errorMsg, Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "解析响应JSON失败", e);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MembershipActivity.this, "解析服务器响应失败", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }

                @Override
                public void onError(final String error) {
                    Log.e(TAG, "网络请求失败: " + error);
                    dismissProgressDialog();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MembershipActivity.this, "网络请求失败: " + error, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });

        } catch (JSONException e) {
            Log.e(TAG, "创建请求参数失败", e);
            dismissProgressDialog();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MembershipActivity.this, "创建请求参数失败", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * 显示二维码支付对话框
     */
    private void showQrCodePaymentDialog(final String qrCode, final String orderId,
                                          final String membershipType, final double price) {
        try {
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_qr_payment, null);

            ImageView ivQrCode = dialogView.findViewById(R.id.ivQrCode);
            TextView tvOrderId = dialogView.findViewById(R.id.tvOrderId);
            TextView tvCountdown = dialogView.findViewById(R.id.tvCountdown);
            Button btnOpenAlipay = dialogView.findViewById(R.id.btnOpenAlipay);

            Bitmap qrBitmap = generateQRCode(qrCode, 400, 400);
            if (qrBitmap != null) {
                ivQrCode.setImageBitmap(qrBitmap);
            } else {
                Log.e(TAG, "生成二维码失败");
                Toast.makeText(this, "生成二维码失败", Toast.LENGTH_SHORT).show();
                return;
            }

            tvOrderId.setText("订单号: " + orderId + "\n商品: " + membershipType + "  ¥" + String.format("%.1f", price));

            btnOpenAlipay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tryOpenAlipay(qrCode);
                }
            });

            Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
            Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setView(dialogView)
                    .setTitle("支付宝支付 - " + membershipType)
                    .setCancelable(false);

            currentPaymentDialog = builder.create();
            currentPaymentDialog.show();

            btnConfirm.setOnClickListener(v -> {
                currentPaymentDialog.dismiss();
                checkPaymentStatusManually(orderId);
            });

            btnCancel.setOnClickListener(v -> {
                currentPaymentDialog.dismiss();
                stopStatusCheck();
            });

            startCountdown(tvCountdown);
            startStatusCheck(orderId);

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tryOpenAlipay(qrCode);
                }
            }, 0000);

        } catch (Exception e) {
            Log.e(TAG, "显示支付对话框失败", e);
            Toast.makeText(this, "显示支付界面失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 尝试跳转到支付宝
     */
    private void tryOpenAlipay(String qrCode) {
        try {
            String alipayUrl = parseAlipayUrlFromQrCode(qrCode);

            if (alipayUrl != null && !alipayUrl.isEmpty()) {
                Log.d(TAG, "准备跳转的URL: " + alipayUrl);

                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(alipayUrl));

                if (isAlipayInstalled()) {
                    Log.d(TAG, "支付宝已安装，开始跳转");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setPackage("com.eg.android.AlipayGphone");

                    PackageManager packageManager = getPackageManager();
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent);
                        Toast.makeText(this, "正在跳转到支付宝...", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "跳转支付宝成功");
                    } else {
                        Log.e(TAG, "没有应用可以处理此Intent");
                        showAlternativePaymentMethods(qrCode);
                    }
                } else {
                    Log.d(TAG, "支付宝未安装");
                    showAlipayNotInstalledDialog();
                }
            } else {
                Log.e(TAG, "无法解析支付链接");
                Toast.makeText(this, "无法解析支付链接，请使用扫码支付", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "跳转支付宝失败", e);
            Toast.makeText(this, "跳转支付宝失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            showAlternativePaymentMethods(qrCode);
        }
    }

    /**
     * 显示支付宝未安装的对话框
     */
    private void showAlipayNotInstalledDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("支付宝未安装")
                .setMessage("检测到您的设备未安装支付宝，是否前往下载？")
                .setPositiveButton("下载支付宝", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent marketIntent = new Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=com.eg.android.AlipayGphone"));
                        try {
                            startActivity(marketIntent);
                        } catch (Exception e) {
                            Intent webIntent = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://www.alipay.com"));
                            startActivity(webIntent);
                        }
                    }
                })
                .setNegativeButton("使用扫码支付", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setCancelable(false)
                .show();
    }

    /**
     * 显示备选支付方式
     */
    private void showAlternativePaymentMethods(String qrCode) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("支付方式选择")
                .setMessage("自动跳转失败，请选择支付方式：")
                .setPositiveButton("保存二维码到相册", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        saveQrCodeToGallery(qrCode);
                    }
                })
                .setNeutralButton("手动复制支付链接", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyPaymentLinkToClipboard(qrCode);
                    }
                })
                .setNegativeButton("继续扫码支付", null)
                .show();
    }

    /**
     * 保存二维码到相册
     */
    private void saveQrCodeToGallery(String qrCode) {
        try {
            Bitmap qrBitmap = generateQRCode(qrCode, 400, 400);
            if (qrBitmap != null) {
                Toast.makeText(this, "二维码已保存到相册", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "生成二维码失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "保存二维码失败", e);
            Toast.makeText(this, "保存二维码失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 复制支付链接到剪贴板
     */
    private void copyPaymentLinkToClipboard(String qrCode) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("支付链接", qrCode);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "支付链接已复制到剪贴板", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "复制到剪贴板失败", e);
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 从二维码内容中解析支付宝URL
     */
    private String parseAlipayUrlFromQrCode(String qrCode) {
        try {
            Log.d(TAG, "原始二维码内容: " + qrCode);

            if (qrCode.startsWith("https://qr.alipay.com/")) {
                String encodedUrl = URLEncoder.encode(qrCode, "UTF-8");
                String alipayScheme = "alipays://platformapi/startapp?saId=10000007&clientVersion=3.7.0.0718&qrcode=" + encodedUrl;
                Log.d(TAG, "构建支付宝Scheme: " + alipayScheme);
                return alipayScheme;
            }

            if (qrCode.startsWith("alipay") || qrCode.startsWith("alipays")) {
                return qrCode;
            }

            if (qrCode.contains("alipay") && qrCode.startsWith("http")) {
                String encodedUrl = URLEncoder.encode(qrCode, "UTF-8");
                return "alipays://platformapi/startapp?saId=10000007&qrcode=" + encodedUrl;
            }

            if (qrCode.length() > 10 && qrCode.length() < 100) {
                String encodedCode = URLEncoder.encode(qrCode, "UTF-8");
                return "alipays://platformapi/startapp?saId=10000007&qrcode=" + encodedCode;
            }

            Log.d(TAG, "无法识别的二维码格式");
            return null;

        } catch (Exception e) {
            Log.e(TAG, "解析支付宝URL失败", e);
            return null;
        }
    }

    /**
     * 检查支付宝是否安装 - 增强版
     */
    private boolean isAlipayInstalled() {
        try {
            PackageManager packageManager = getPackageManager();

            try {
                packageManager.getPackageInfo("com.eg.android.AlipayGphone", PackageManager.GET_ACTIVITIES);
                Log.d(TAG, "支付宝包存在");
            } catch (PackageManager.NameNotFoundException e) {
                Log.d(TAG, "支付宝包不存在");
                return false;
            }

            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("alipays://"));
            List<ResolveInfo> activities = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            boolean canHandleAlipay = !activities.isEmpty();
            Log.d(TAG, "能处理支付宝Scheme: " + canHandleAlipay);

            return canHandleAlipay;

        } catch (Exception e) {
            Log.e(TAG, "检查支付宝安装状态失败", e);
            return false;
        }
    }

    /**
     * 开始支付状态轮询
     */
    private void startStatusCheck(final String orderId) {
        currentOrderId = orderId;
        isPaymentInProgress = true;
        lastPaymentCheckTime = System.currentTimeMillis();

        statusCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkPaymentStatusInternal(orderId, false);
                handler.postDelayed(this, 1000);
            }
        };

        handler.postDelayed(statusCheckRunnable, 1000);
    }

    /**
     * 停止状态轮询
     */
    private void stopStatusCheck() {
        if (statusCheckRunnable != null) {
            handler.removeCallbacks(statusCheckRunnable);
            statusCheckRunnable = null;
        }
        currentOrderId = "";
        isPaymentInProgress = false;
        lastPaymentCheckTime = 0;
    }

    /**
     * 内部支付状态检查方法
     */
    private void checkPaymentStatusInternal(final String orderId, final boolean isManual) {
        if (!isNetworkAvailable()) {
            Log.e(TAG, "检查支付状态时网络不可用");
            if (isManual) {
                handleManualCheckError("网络不可用");
            }
            return;
        }

        // 每轮都主动向支付宝查询交易状态（解决回调未收到的问题）
        String apiUrl = AppConfig.CHECK_PAYMENT_URL + "?order_id=" + orderId + "&query_alipay=1";

        get(apiUrl, new ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                try {
                    boolean success = response.getBoolean("success");
                    if (success) {
                        boolean paid = response.getBoolean("paid");
                        if (paid) {
                            handlePaymentSuccess(orderId);
                        } else {
                            if (isManual) {
                                handleManualCheckNotPaid();
                            } else {
                                lastPaymentCheckTime = System.currentTimeMillis();
                                Log.d(TAG, "支付尚未完成，继续轮询");
                            }
                        }
                    } else {
                        String errorMsg = response.getString("message");
                        Log.e(TAG, "检查支付状态失败: " + errorMsg);
                        if (isManual) {
                            handleManualCheckError("服务器返回错误: " + errorMsg);
                        }
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "解析支付状态失败", e);
                    if (isManual) {
                        handleManualCheckError("解析响应失败");
                    }
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "检查支付状态失败: " + error);
                if (isManual) {
                    handleManualCheckError("网络请求失败: " + error);
                }
            }
        });
    }

    /**
     * 支付对话框倒计时
     */
    private void startCountdown(final TextView tvCountdown) {
        final AtomicInteger countdown = new AtomicInteger(300);

        final Handler countdownHandler = new Handler();
        final Runnable countdownRunnable = new Runnable() {
            @Override
            public void run() {
                int currentCount = countdown.get();
                if (currentCount > 0) {
                    int minutes = currentCount / 60;
                    int seconds = currentCount % 60;
                    String timeText = String.format("支付剩余时间: %02d:%02d", minutes, seconds);
                    tvCountdown.setText(timeText);
                    countdown.decrementAndGet();
                    countdownHandler.postDelayed(this, 1000);
                } else {
                    tvCountdown.setText("支付已超时");
                    stopStatusCheck();
                }
            }
        };

        countdownHandler.post(countdownRunnable);
    }

    /**
     * 显示支付成功对话框
     */
    private void showPaymentSuccessDialog() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AlertDialog.Builder builder = new AlertDialog.Builder(MembershipActivity.this);
                builder.setTitle("支付成功")
                        .setMessage("会员开通成功！\n感谢您的购买，会员权益已立即生效。")
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                swipeRefreshLayout.setRefreshing(true);
                                loadMembershipStatus();
                            }
                        })
                        .setCancelable(false)
                        .show();

                Log.d(TAG, "支付成功对话框已显示，订单号: " + currentOrderId);
            }
        });
    }

    /**
     * POST网络请求 - 使用表单格式
     */
    private void post(final String url, final JSONObject params, final ApiCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    Log.d(TAG, "开始网络请求: " + url);
                    URL apiUrl = new URL(url);
                    connection = (HttpURLConnection) apiUrl.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(15000);
                    connection.setDoOutput(true);

                    StringBuilder formData = new StringBuilder();
                    formData.append("user_id=").append(params.optInt("user_id"));
                    formData.append("&app_id=").append(params.optInt("app_id"));
                    formData.append("&membership_type=").append(URLEncoder.encode(params.optString("membership_type"), "UTF-8"));
                    formData.append("&amount=").append(params.optDouble("amount"));

                    String formDataString = formData.toString();
                    Log.d(TAG, "表单数据: " + formDataString);

                    try (OutputStream os = connection.getOutputStream()) {
                        byte[] input = formDataString.getBytes(StandardCharsets.UTF_8);
                        os.write(input, 0, input.length);
                    }

                    int responseCode = connection.getResponseCode();
                    Log.d(TAG, "HTTP响应码: " + responseCode);

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();

                        String responseBody = response.toString();
                        Log.d(TAG, "响应体: " + InputValidator.sanitizeForLog(responseBody));

                        final JSONObject jsonResponse = new JSONObject(responseBody);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(jsonResponse);
                            }
                        });
                    } else {
                        BufferedReader errorReader = new BufferedReader(
                                new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8));
                        StringBuilder errorResponse = new StringBuilder();
                        String line;
                        while ((line = errorReader.readLine()) != null) {
                            errorResponse.append(line);
                        }
                        errorReader.close();

                        Log.e(TAG, "HTTP错误: " + responseCode + " - " + errorResponse.toString());
                        final String finalError = "HTTP错误: " + responseCode;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(finalError);
                            }
                        });
                    }
                } catch (final Exception e) {
                    Log.e(TAG, "网络请求异常", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError("请求异常: " + e.getMessage());
                        }
                    });
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }).start();
    }

    /**
     * GET网络请求
     */
    private void get(final String url, final ApiCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    Log.d(TAG, "开始GET请求: " + url);
                    URL apiUrl = new URL(url);
                    connection = (HttpURLConnection) apiUrl.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);

                    int responseCode = connection.getResponseCode();
                    Log.d(TAG, "GET响应码: " + responseCode);

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();

                        String responseBody = response.toString();
                        Log.d(TAG, "GET响应体: " + InputValidator.sanitizeForLog(responseBody));

                        final JSONObject jsonResponse = new JSONObject(responseBody);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                callback.onSuccess(jsonResponse);
                            }
                        });
                    } else {
                        String errorResponse = "";
                        try {
                            BufferedReader errorReader = new BufferedReader(
                                    new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8));
                            StringBuilder errorBuilder = new StringBuilder();
                            String line;
                            while ((line = errorReader.readLine()) != null) {
                                errorBuilder.append(line);
                            }
                            errorReader.close();
                            errorResponse = errorBuilder.toString();
                        } catch (Exception e) {
                            Log.e(TAG, "读取GET错误流失败", e);
                        }

                        Log.e(TAG, "GET HTTP错误: " + responseCode + " - " + errorResponse);
                        final String finalError = "HTTP错误: " + responseCode;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                callback.onError(finalError);
                            }
                        });
                    }
                } catch (final Exception e) {
                    Log.e(TAG, "GET请求异常", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError("请求异常: " + e.getMessage());
                        }
                    });
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }).start();
    }

    /**
     * 生成二维码
     */
    private Bitmap generateQRCode(String content, int width, int height) {
        try {
            com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
            com.google.zxing.common.BitMatrix bitMatrix = writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, width, height);

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "生成二维码失败", e);
            return null;
        }
    }

    private void showProgressDialog(String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (progressDialog == null) {
                        progressDialog = new ProgressDialog(MembershipActivity.this);
                        progressDialog.setCancelable(false);
                    }
                    progressDialog.setMessage(message);
                    if (!progressDialog.isShowing()) {
                        progressDialog.show();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "显示进度对话框失败", e);
                }
            }
        });
    }

    private void dismissProgressDialog() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "关闭进度对话框失败", e);
                }
            }
        });
    }

    // ==================== 从服务器获取会员价格 ====================
    private void loadMembershipPrices() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(AppConfig.MEMBERSHIP_PRICES_URL);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(8000);

                    int responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();

                        JSONObject jsonResponse = new JSONObject(response.toString());
                        if (jsonResponse.getBoolean("success")) {
                            JSONObject data = jsonResponse.getJSONObject("data");
                            JSONObject basic = data.getJSONObject("basic");
                            JSONObject premium = data.getJSONObject("premium");
                            JSONObject lifetime = data.getJSONObject("lifetime");

                            basicPrice = basic.getDouble("price");
                            premiumPrice = premium.getDouble("price");
                            lifetimePrice = lifetime.getDouble("price");
                            basicName = basic.getString("name");
                            premiumName = premium.getString("name");
                            lifetimeName = lifetime.getString("name");

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    updatePriceDisplay();
                                }
                            });

                            Log.d(TAG, "会员价格已更新: " + basicName + "=" + basicPrice
                                    + ", " + premiumName + "=" + premiumPrice
                                    + ", " + lifetimeName + "=" + lifetimePrice);
                        }
                    }
                } catch (final Exception e) {
                    Log.e(TAG, "获取会员价格失败", e);
                    // 使用默认价格，不打扰用户
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }).start();
    }

    private void updatePriceDisplay() {
        try {
            TextView tvBasicPrice = findViewById(R.id.tvBasicPrice);
            TextView tvPremiumPrice = findViewById(R.id.tvPremiumPrice);
            TextView tvLifetimePrice = findViewById(R.id.tvLifetimePrice);

            if (tvBasicPrice != null) {
                tvBasicPrice.setText("¥" + String.format("%.1f", basicPrice) + "/月");
            }
            if (tvPremiumPrice != null) {
                tvPremiumPrice.setText("¥" + String.format("%.1f", premiumPrice) + "/月");
            }
            if (tvLifetimePrice != null) {
                tvLifetimePrice.setText("¥" + String.format("%.1f", lifetimePrice));
            }
        } catch (Exception e) {
            Log.e(TAG, "更新价格显示失败", e);
        }
    }

    // ==================== 本地缓存 ====================
    private void loadLocalMembershipStatus() {
        String localType = spManager.getMembershipType();
        if (localType != null && !localType.isEmpty() && !localType.equals("免费用户")) {
            currentMembershipType = localType;
            startTime = "";
            expireTime = "";
            updateMembershipUI();
        }
    }

    // ==================== 会员状态加载 ====================
    private void loadMembershipStatus() {
        if (userId == 0) {
            Log.e(TAG, "用户ID为0，无法获取会员状态");
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MembershipActivity.this, "用户未登录，无法获取会员状态", Toast.LENGTH_SHORT).show();
                    swipeRefreshLayout.setRefreshing(false);
                }
            });
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String apiUrl = TokenAuthHelper.appendTokenToUrl(AppConfig.MEMBERSHIP_STATUS_URL + "?user_id=" + userId, MembershipActivity.this);

                    Log.d(TAG, "请求会员状态API: " + apiUrl);

                    URL url = new URL(apiUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(8000);

                    int responseCode = connection.getResponseCode();
                    Log.d(TAG, "API响应码: " + responseCode);

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        reader.close();

                        Log.d(TAG, "API响应内容: " + InputValidator.sanitizeForLog(response.toString()));

                        final JSONObject jsonResponse = new JSONObject(response.toString());
                        boolean success = jsonResponse.getBoolean("success");

                        if (success) {
                            if (jsonResponse.has("membership") && !jsonResponse.isNull("membership")) {
                                JSONObject membership = jsonResponse.getJSONObject("membership");
                                currentMembershipType = membership.getString("membership_type");
                                startTime = membership.getString("start_time");
                                expireTime = membership.getString("expire_time");

                                // ★ 同步到本地缓存
                                spManager.setMembershipType(currentMembershipType);

                                Log.d(TAG, "会员信息: " + currentMembershipType + ", " + startTime + " - " + expireTime);
                            } else {
                                // 未返回会员信息，回退到本地缓存
                                loadLocalMembershipStatus();
                                Log.d(TAG, "用户没有会员信息，使用本地缓存");
                            }

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    updateMembershipUI();
                                    swipeRefreshLayout.setRefreshing(false);
                                    Toast.makeText(MembershipActivity.this, "会员状态已更新", Toast.LENGTH_SHORT).show();
                                }
                            });
                        } else {
                            final String errorMsg = jsonResponse.getString("message");
                            Log.e(TAG, "API返回错误: " + errorMsg);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MembershipActivity.this, "获取会员状态失败: " + errorMsg, Toast.LENGTH_SHORT).show();
                                    swipeRefreshLayout.setRefreshing(false);
                                }
                            });
                        }
                    } else {
                        Log.e(TAG, "HTTP请求失败，响应码: " + responseCode);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MembershipActivity.this, "网络请求失败，请检查网络连接", Toast.LENGTH_SHORT).show();
                                swipeRefreshLayout.setRefreshing(false);
                            }
                        });
                    }
                } catch (final Exception e) {
                    Log.e(TAG, "获取会员状态失败", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MembershipActivity.this, "获取会员状态失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            swipeRefreshLayout.setRefreshing(false);
                        }
                    });
                }
            }
        }).start();
    }

    // ==================== UI 更新（含动态倒计时） ====================
    private void updateMembershipUI() {
        try {
            LinearLayout layoutStatus = findViewById(R.id.layoutMembershipStatus);
            TextView tvMembershipTitle = findViewById(R.id.tvMembershipTitle);
            TextView tvCurrentMembership = findViewById(R.id.tvCurrentMembership);
            TextView tvStartTime = findViewById(R.id.tvStartTime);
            TextView tvExpireTime = findViewById(R.id.tvExpireTime);

            // 兜底：如果类型为空，从本地再读一次
            if (currentMembershipType == null || currentMembershipType.isEmpty()) {
                String localType = spManager.getMembershipType();
                if (localType != null && !localType.isEmpty()) {
                    currentMembershipType = localType;
                } else {
                    currentMembershipType = "免费用户";
                }
            }

            // 使用 ImageSpan 将等级图标紧跟在会员名称后面
            if (!"免费用户".equals(currentMembershipType) && !TextUtils.isEmpty(currentMembershipType)) {
                int iconResId = getMemberLevelIcon(currentMembershipType);
                Drawable icon = getResources().getDrawable(iconResId);
                icon.setBounds(0, 0, (int)(icon.getIntrinsicWidth() * 1.0f), (int)(icon.getIntrinsicHeight() * 1.0f));
                SpannableStringBuilder ssb = new SpannableStringBuilder("当前会员状态: " + currentMembershipType + " ");
                ssb.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_BASELINE), ssb.length() - 1, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                tvCurrentMembership.setText(ssb);
            } else {
                tvCurrentMembership.setText("当前会员状态: " + currentMembershipType);
            }

            if ("免费用户".equals(currentMembershipType)) {
                tvCurrentMembership.setTextColor(Color.parseColor("#757575"));
                tvStartTime.setVisibility(View.GONE);
                tvExpireTime.setVisibility(View.GONE);
                stopCountDown(); // 免费用户不需要倒计时
            } else {
                tvCurrentMembership.setTextColor(Color.parseColor("#4CAF50"));

                // 开始时间
                if (!startTime.isEmpty()) {
                    String formattedStartTime = formatDateTime(startTime);
                    tvStartTime.setText("开始时间: " + formattedStartTime);
                    tvStartTime.setVisibility(View.VISIBLE);
                } else {
                    tvStartTime.setVisibility(View.GONE);
                }

                // 到期时间
                if ("终身会员".equals(currentMembershipType) || "永久有效".equals(expireTime) || "永久".equals(expireTime)) {
                    tvExpireTime.setText("到期时间: 永久有效");
                    tvExpireTime.setVisibility(View.VISIBLE);
                    stopCountDown();
                } else if (expireTime != null && !expireTime.isEmpty()) {
                    tvExpireTime.setVisibility(View.VISIBLE);
                    startCountDown(); // 启动每秒刷新倒计时
                } else {
                    tvExpireTime.setVisibility(View.GONE);
                    stopCountDown();
                }
            }

            updateButtonStates();
        } catch (Exception e) {
            Log.e(TAG, "更新会员状态UI失败", e);
        }
    }

    // ==================== 动态到期倒计时 ====================
    private void startCountDown() {
        stopCountDown();
        countDownRunnable = new Runnable() {
            @Override
            public void run() {
                updateExpireTimeDisplay();
                countDownHandler.postDelayed(this, 1000);
            }
        };
        countDownHandler.post(countDownRunnable);
    }

    private void stopCountDown() {
        if (countDownRunnable != null) {
            countDownHandler.removeCallbacks(countDownRunnable);
            countDownRunnable = null;
        }
    }

    private void updateExpireTimeDisplay() {
        TextView tvExpireTime = findViewById(R.id.tvExpireTime);
        if (tvExpireTime == null || expireTime == null || expireTime.isEmpty()) return;

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date expireDate = sdf.parse(expireTime);
            long diff = expireDate.getTime() - System.currentTimeMillis();

            if (diff <= 0) {
                tvExpireTime.setText("到期时间: 已到期");
                stopCountDown();
                return;
            }

            long days = diff / (1000 * 60 * 60 * 24);
            long hours = (diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60);
            long minutes = (diff % (1000 * 60 * 60)) / (1000 * 60);
            long seconds = (diff % (1000 * 60)) / 1000;

            StringBuilder sb = new StringBuilder("到期时间: ");
            if (days > 0) sb.append(days).append("天 ");
            sb.append(String.format("%02d:%02d:%02d", hours, minutes, seconds));
            tvExpireTime.setText(sb.toString());

        } catch (ParseException e) {
            Log.e(TAG, "解析到期时间失败", e);
            tvExpireTime.setText("到期时间: " + expireTime);
            stopCountDown();
        }
    }

    private String formatDateTime(String dateTime) {
        try {
            if (dateTime.length() >= 16) {
                String datePart = dateTime.substring(0, 10);
                String timePart = dateTime.substring(11, 16);
                String[] dateParts = datePart.split("-");
                if (dateParts.length == 3) {
                    return dateParts[0] + "年" + dateParts[1] + "月" + dateParts[2] + "日 " + timePart;
                }
            }
            return dateTime;
        } catch (Exception e) {
            return dateTime;
        }
    }

    private void updateButtonStates() {
        Button btnBasic = findViewById(R.id.btnBasic);
        Button btnPremium = findViewById(R.id.btnPremium);
        Button btnLifetime = findViewById(R.id.btnLifetime);

        if ("基础会员".equals(currentMembershipType)) {
            btnBasic.setEnabled(false);
            btnBasic.setText("已开通");
            btnBasic.setBackgroundColor(Color.GRAY);
        } else if ("高级会员".equals(currentMembershipType)) {
            btnPremium.setEnabled(false);
            btnPremium.setText("已开通");
            btnPremium.setBackgroundColor(Color.GRAY);
        } else if ("终身会员".equals(currentMembershipType)) {
            btnLifetime.setEnabled(false);
            btnLifetime.setText("已开通");
            btnLifetime.setBackgroundColor(Color.GRAY);
        }
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