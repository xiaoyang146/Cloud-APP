package com.cloud.dex;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.cloud.dex.widget.refresh.PtrFrameLayout;

import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONException;
import org.json.JSONObject;

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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class MembershipFragment extends Fragment {

    private static final String TAG = "MembershipFragment";
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

    private android.app.AlertDialog currentPaymentDialog;

    private boolean isPaymentInProgress = false;
    private long lastPaymentCheckTime = 0;
    private static final long PAYMENT_TIMEOUT = 30000;

    private boolean isManualCheck = false;
    private long manualCheckStartTime = 0;
    private static final long MANUAL_CHECK_TIMEOUT = 15000;

    private static final String CUSTOMER_SERVICE_QQ = AppConfig.CUSTOMER_SERVICE_QQ;

    private Handler countDownHandler = new Handler(Looper.getMainLooper());
    private Runnable countDownRunnable;

    private double basicPrice = 9.9;
    private double premiumPrice = 19.9;
    private double lifetimePrice = 99.9;
    private String basicName = "基础会员";
    private String premiumName = "高级会员";
    private String lifetimeName = "终身会员";

    private boolean isDestroyed = false;

    public interface ApiCallback {
        void onSuccess(JSONObject response);
        void onError(String error);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_membership, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        spManager = new SharedPreferencesManager(requireContext());
        handler = new Handler(Looper.getMainLooper());

        userId = spManager.getUserId();
        username = spManager.getUsername();

        initViews(view);
        setupSwipeRefresh(view);
        setupButtonClickListeners(view);

        loadLocalMembershipStatus();
        loadMembershipStatus();
        loadMembershipPrices();
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle("会员服务");
        loadMembershipStatus();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        isDestroyed = true;
        stopStatusCheck();
        isManualCheck = false;
        stopCountDown();
        if (currentPaymentDialog != null && currentPaymentDialog.isShowing()) {
            currentPaymentDialog.dismiss();
            currentPaymentDialog = null;
        }
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    private void initViews(View view) {
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        if (swipeRefreshLayout == null) {
            Log.e(TAG, "swipeRefreshLayout not found");
            Toast.makeText(requireContext(), "界面初始化失败", Toast.LENGTH_SHORT).show();
            return;
        }
        swipeRefreshLayout.setColorSchemeColors(
                Color.parseColor("#2196F3"),
                Color.parseColor("#4CAF50"),
                Color.parseColor("#FF9800")
        );

        TextView tvUsername = view.findViewById(R.id.tvUsername);
        TextView tvUserId = view.findViewById(R.id.tvUserId);

        if (tvUsername != null) {
            tvUsername.setText((username != null && !username.isEmpty()) ? "欢迎，" + username : "欢迎，用户");
        }
        if (tvUserId != null) {
            tvUserId.setText("用户ID: " + userId);
        }

        setupContactService(view);
    }

    private void setupContactService(View view) {
        try {
            TextView tvContactService = view.findViewById(R.id.tvContactService);
            tvContactService.setOnClickListener(v -> contactCustomerService());
        } catch (Exception e) {
            Log.e(TAG, "setup contact service failed", e);
        }
    }

    private void setupSwipeRefresh(View view) {
        swipeRefreshLayout.setOnRefreshListener(() -> {
            Log.d(TAG, "swipeRefresh triggered");
            loadMembershipStatus();
            Toast.makeText(requireContext(), "正在刷新会员状态...", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupButtonClickListeners(View view) {
        Button btnBasic = view.findViewById(R.id.btnBasic);
        btnBasic.setOnClickListener(v -> createAlipayQrPayment(basicName, basicPrice));

        Button btnPremium = view.findViewById(R.id.btnPremium);
        btnPremium.setOnClickListener(v -> createAlipayQrPayment(premiumName, premiumPrice));

        Button btnLifetime = view.findViewById(R.id.btnLifetime);
        btnLifetime.setOnClickListener(v -> createAlipayQrPayment(lifetimeName, lifetimePrice));
    }

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) requireContext().getSystemService(requireContext().CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                return activeNetwork != null && activeNetwork.isConnected();
            }
        } catch (Exception e) {
            Log.e(TAG, "check network failed", e);
        }
        return false;
    }

    private void checkPaymentReturnStatus() {
        if (isPaymentInProgress && currentOrderId != null && !currentOrderId.isEmpty()) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastPaymentCheckTime < PAYMENT_TIMEOUT) {
                checkPaymentStatusInternal(currentOrderId, false);
                showReturnFromAlipayDialog();
            }
        }
    }

    private void showReturnFromAlipayDialog() {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            try {
                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle("支付提示")
                        .setMessage("检测到您已从支付宝返回，但支付尚未完成。\n\n请确认是否已完成支付？")
                        .setPositiveButton("我已支付完成", (dialog, which) -> {
                            if (currentOrderId != null && !currentOrderId.isEmpty()) {
                                checkPaymentStatusManually(currentOrderId);
                            }
                        })
                        .setNegativeButton("重新支付", (dialog, which) ->
                                Toast.makeText(requireContext(), "请重新选择支付方式", Toast.LENGTH_SHORT).show())
                        .setNeutralButton("取消支付", (dialog, which) -> {
                            stopStatusCheck();
                            if (currentPaymentDialog != null && currentPaymentDialog.isShowing()) {
                                currentPaymentDialog.dismiss();
                            }
                            Toast.makeText(requireContext(), "支付已取消", Toast.LENGTH_SHORT).show();
                        })
                        .setCancelable(false)
                        .show();
            } catch (Exception e) {
                Log.e(TAG, "show return dialog failed", e);
            }
        });
    }

    private void checkPaymentStatusManually(final String orderId) {
        if (!isNetworkAvailable()) {
            dismissProgressDialog();
            Toast.makeText(requireContext(), "网络不可用，请检查网络连接", Toast.LENGTH_SHORT).show();
            return;
        }

        isManualCheck = true;
        manualCheckStartTime = System.currentTimeMillis();
        showProgressDialog("正在验证支付状态...");

        handler.postDelayed(() -> {
            if (isManualCheck) {
                Log.w(TAG, "manual check timeout");
                dismissProgressDialog();
                isManualCheck = false;
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> showCheckTimeoutDialog(orderId));
                }
            }
        }, MANUAL_CHECK_TIMEOUT);

        checkPaymentStatusInternal(orderId, true);
    }

    private void showCheckTimeoutDialog(final String orderId) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            new android.app.AlertDialog.Builder(requireContext())
                    .setTitle("检查超时")
                    .setMessage("支付状态检查超时，可能的原因：\n\n• 网络连接缓慢\n• 服务器响应延迟\n• 支付信息同步中\n\n建议您：\n1. 等待几分钟后再次检查\n2. 确认支付宝扣款记录\n3. 联系客服协助处理")
                    .setPositiveButton("再次检查", (dialog, which) -> checkPaymentStatusManually(orderId))
                    .setNegativeButton("稍后检查", (dialog, which) ->
                            Toast.makeText(requireContext(), "系统会继续在后台检查支付状态", Toast.LENGTH_SHORT).show())
                    .setNeutralButton("联系客服", (dialog, which) -> contactCustomerService())
                    .setCancelable(false)
                    .show();
        });
    }

    private void contactCustomerService() {
        try {
            String qqNumber = CUSTOMER_SERVICE_QQ;
            String url = "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=" + qqNumber;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (isQQInstalled()) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setPackage("com.tencent.mobileqq");
                startActivity(intent);
            } else {
                showQQNotInstalledDialog(qqNumber);
            }
        } catch (Exception e) {
            Log.e(TAG, "contact QQ failed", e);
            tryAlternativeQQSchemes();
        }
    }

    private void tryAlternativeQQSchemes() {
        final String qqNumber = CUSTOMER_SERVICE_QQ;
        try {
            String url = "mqq://card/show_pslcard?src_type=internal&uin=" + qqNumber;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(requireContext().getPackageManager()) != null) {
                startActivity(intent);
                return;
            }
        } catch (Exception e) { Log.e(TAG, "alt1 failed", e); }
        try {
            String url = "mqqapi://card/show_pslcard?uin=" + qqNumber;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(requireContext().getPackageManager()) != null) {
                startActivity(intent);
                return;
            }
        } catch (Exception e) { Log.e(TAG, "alt2 failed", e); }
        showAlternativeContactMethods();
    }

    private boolean isQQInstalled() {
        try {
            PackageManager pm = requireContext().getPackageManager();
            try {
                pm.getPackageInfo("com.tencent.mobileqq", PackageManager.GET_ACTIVITIES);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void showQQNotInstalledDialog(final String qqNumber) {
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("QQ未安装")
                .setMessage("检测到您的设备未安装QQ，是否前往下载？\n\n客服QQ: " + qqNumber)
                .setPositiveButton("下载QQ", (dialog, which) -> {
                    Intent marketIntent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=com.tencent.mobileqq"));
                    try { startActivity(marketIntent); }
                    catch (Exception e) {
                        Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://im.qq.com"));
                        startActivity(webIntent);
                    }
                })
                .setNegativeButton("复制QQ号", (dialog, which) -> copyQQToClipboard(qqNumber))
                .setNeutralButton("取消", null)
                .setCancelable(false)
                .show();
    }

    private void copyQQToClipboard(String qqNumber) {
        try {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(requireContext().CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("客服QQ号", qqNumber);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(requireContext(), "客服QQ号已复制到剪贴板: " + qqNumber, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "copy QQ failed", e);
        }
    }

    private void showAlternativeContactMethods() {
        final String qqNumber = CUSTOMER_SERVICE_QQ;
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("联系客服")
                .setMessage("无法自动跳转QQ，请选择联系方式：\n\n客服QQ: " + qqNumber)
                .setPositiveButton("复制QQ号", (dialog, which) -> copyQQToClipboard(qqNumber))
                .setNeutralButton("手动打开QQ", (dialog, which) -> showManualQQGuide(qqNumber))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showManualQQGuide(final String qqNumber) {
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("手动联系客服")
                .setMessage("请按以下步骤操作：\n\n1. 打开QQ应用\n2. 点击右上角\"+\"号\n3. 选择\"加好友/群\"\n4. 输入QQ号: " + qqNumber + "\n5. 搜索并添加客服\n\n或者您也可以复制QQ号后手动添加")
                .setPositiveButton("复制QQ号", (dialog, which) -> copyQQToClipboard(qqNumber))
                .setNegativeButton("知道了", null)
                .show();
    }

    private void handlePaymentSuccess(String orderId) {
        stopStatusCheck();
        isManualCheck = false;
        dismissProgressDialog();
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                if (currentPaymentDialog != null && currentPaymentDialog.isShowing()) {
                    currentPaymentDialog.dismiss();
                    currentPaymentDialog = null;
                }
                showPaymentSuccessDialog();
                swipeRefreshLayout.setRefreshing(true);
                loadMembershipStatus();
            });
        }
    }

    private void handleManualCheckNotPaid() {
        isManualCheck = false;
        dismissProgressDialog();
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle("支付状态")
                        .setMessage("尚未检测到支付成功信息\n\n可能的原因：\n• 支付尚未完成\n• 银行处理延迟\n• 网络同步延迟\n\n建议您：\n1. 确认支付宝是否扣款成功\n2. 稍后再次点击\"我已支付\"\n3. 或联系客服确认")
                        .setPositiveButton("再次检查", (dialog, which) -> {
                            if (currentOrderId != null && !currentOrderId.isEmpty()) {
                                checkPaymentStatusManually(currentOrderId);
                            }
                        })
                        .setNegativeButton("取消", null)
                        .setNeutralButton("联系客服", (dialog, which) -> contactCustomerService())
                        .setCancelable(false)
                        .show();
            });
        }
    }

    private void handleManualCheckError(String errorMessage) {
        isManualCheck = false;
        dismissProgressDialog();
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle("检查失败")
                        .setMessage("支付状态检查失败：\n" + errorMessage + "\n\n请检查网络连接后重试")
                        .setPositiveButton("重试", (dialog, which) -> {
                            if (currentOrderId != null && !currentOrderId.isEmpty()) {
                                checkPaymentStatusManually(currentOrderId);
                            }
                        })
                        .setNegativeButton("取消", null)
                        .setCancelable(false)
                        .show();
            });
        }
    }

    private void createAlipayQrPayment(final String membershipType, final double price) {
        Log.d(TAG, "create payment - type: " + membershipType + ", price: " + price);
        if (!isNetworkAvailable()) {
            Toast.makeText(requireContext(), "网络不可用，请检查网络连接", Toast.LENGTH_LONG).show();
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
            post(apiUrl, params, new ApiCallback() {
                @Override
                public void onSuccess(JSONObject response) {
                    dismissProgressDialog();
                    try {
                        if (response.getBoolean("success")) {
                            String orderId = response.getString("order_id");
                            String qrCode = response.getString("qr_code");
                            showQrCodePaymentDialog(qrCode, orderId, membershipType, price);
                        } else {
                            String errorMsg = response.getString("message");
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() ->
                                        Toast.makeText(requireContext(), "创建订单失败: " + errorMsg, Toast.LENGTH_LONG).show());
                            }
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "parse response failed", e);
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() ->
                                    Toast.makeText(requireContext(), "解析服务器响应失败", Toast.LENGTH_SHORT).show());
                        }
                    }
                }

                @Override
                public void onError(final String error) {
                    dismissProgressDialog();
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), "网络请求失败: " + error, Toast.LENGTH_LONG).show());
                    }
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "create params failed", e);
            dismissProgressDialog();
            if (getActivity() != null) {
                getActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "创建请求参数失败", Toast.LENGTH_SHORT).show());
            }
        }
    }

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
                Log.e(TAG, "generate QR code failed");
                Toast.makeText(requireContext(), "生成二维码失败", Toast.LENGTH_SHORT).show();
                return;
            }

            tvOrderId.setText("订单号: " + orderId + "\n商品: " + membershipType + "  ¥" + String.format("%.1f", price));
            btnOpenAlipay.setOnClickListener(v -> tryOpenAlipay(qrCode));

            Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
            Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);

            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(requireContext());
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

            handler.postDelayed(() -> tryOpenAlipay(qrCode), 0);

        } catch (Exception e) {
            Log.e(TAG, "show payment dialog failed", e);
            Toast.makeText(requireContext(), "显示支付界面失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void tryOpenAlipay(String qrCode) {
        try {
            String alipayUrl = parseAlipayUrlFromQrCode(qrCode);
            if (alipayUrl != null && !alipayUrl.isEmpty()) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(alipayUrl));
                if (isAlipayInstalled()) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setPackage("com.eg.android.AlipayGphone");
                    PackageManager pm = requireContext().getPackageManager();
                    if (intent.resolveActivity(pm) != null) {
                        startActivity(intent);
                        Toast.makeText(requireContext(), "正在跳转到支付宝...", Toast.LENGTH_SHORT).show();
                    } else {
                        showAlternativePaymentMethods(qrCode);
                    }
                } else {
                    showAlipayNotInstalledDialog();
                }
            } else {
                Log.e(TAG, "parse payment url failed");
                Toast.makeText(requireContext(), "无法解析支付链接，请使用扫码支付", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "open alipay failed", e);
            Toast.makeText(requireContext(), "跳转支付宝失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            showAlternativePaymentMethods(qrCode);
        }
    }

    private void showAlipayNotInstalledDialog() {
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("支付宝未安装")
                .setMessage("检测到您的设备未安装支付宝，是否前往下载？")
                .setPositiveButton("下载支付宝", (dialog, which) -> {
                    Intent marketIntent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=com.eg.android.AlipayGphone"));
                    try { startActivity(marketIntent); }
                    catch (Exception e) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.alipay.com")));
                    }
                })
                .setNegativeButton("使用扫码支付", (dialog, which) -> dialog.dismiss())
                .setCancelable(false)
                .show();
    }

    private void showAlternativePaymentMethods(String qrCode) {
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("支付方式选择")
                .setMessage("自动跳转失败，请选择支付方式：")
                .setPositiveButton("保存二维码到相册", (dialog, which) -> saveQrCodeToGallery(qrCode))
                .setNeutralButton("手动复制支付链接", (dialog, which) -> copyPaymentLinkToClipboard(qrCode))
                .setNegativeButton("继续扫码支付", null)
                .show();
    }

    private void saveQrCodeToGallery(String qrCode) {
        try {
            Bitmap qrBitmap = generateQRCode(qrCode, 400, 400);
            if (qrBitmap != null) {
                Toast.makeText(requireContext(), "二维码已保存到相册", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "生成二维码失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "save QR failed", e);
            Toast.makeText(requireContext(), "保存二维码失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyPaymentLinkToClipboard(String qrCode) {
        try {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(requireContext().CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("支付链接", qrCode);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(requireContext(), "支付链接已复制到剪贴板", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "copy link failed", e);
            Toast.makeText(requireContext(), "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String parseAlipayUrlFromQrCode(String qrCode) {
        try {
            if (qrCode.startsWith("https://qr.alipay.com/")) {
                String encodedUrl = URLEncoder.encode(qrCode, "UTF-8");
                return "alipays://platformapi/startapp?saId=10000007&clientVersion=3.7.0.0718&qrcode=" + encodedUrl;
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
            return null;
        } catch (Exception e) {
            Log.e(TAG, "parse alipay url failed", e);
            return null;
        }
    }

    private boolean isAlipayInstalled() {
        try {
            PackageManager pm = requireContext().getPackageManager();
            try {
                pm.getPackageInfo("com.eg.android.AlipayGphone", PackageManager.GET_ACTIVITIES);
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("alipays://"));
            List<ResolveInfo> activities = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            return !activities.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void startStatusCheck(final String orderId) {
        currentOrderId = orderId;
        isPaymentInProgress = true;
        lastPaymentCheckTime = System.currentTimeMillis();
        statusCheckRunnable = () -> {
            checkPaymentStatusInternal(orderId, false);
            handler.postDelayed(statusCheckRunnable, 1000);
        };
        handler.postDelayed(statusCheckRunnable, 1000);
    }

    private void stopStatusCheck() {
        if (statusCheckRunnable != null) {
            handler.removeCallbacks(statusCheckRunnable);
            statusCheckRunnable = null;
        }
        currentOrderId = "";
        isPaymentInProgress = false;
        lastPaymentCheckTime = 0;
    }

    private void checkPaymentStatusInternal(final String orderId, final boolean isManual) {
        if (!isNetworkAvailable()) {
            Log.e(TAG, "network not available");
            if (isManual) handleManualCheckError("网络不可用");
            return;
        }
        String apiUrl = AppConfig.CHECK_PAYMENT_URL + "?order_id=" + orderId + "&query_alipay=1";
        get(apiUrl, new ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                try {
                    if (response.getBoolean("success")) {
                        boolean paid = response.getBoolean("paid");
                        if (paid) {
                            handlePaymentSuccess(orderId);
                        } else {
                            if (isManual) {
                                handleManualCheckNotPaid();
                            } else {
                                lastPaymentCheckTime = System.currentTimeMillis();
                            }
                        }
                    } else {
                        if (isManual) handleManualCheckError("服务器返回错误");
                    }
                } catch (JSONException e) {
                    if (isManual) handleManualCheckError("解析响应失败");
                }
            }
            @Override
            public void onError(String error) {
                if (isManual) handleManualCheckError("网络请求失败: " + error);
            }
        });
    }

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
                    tvCountdown.setText(String.format("支付剩余时间: %02d:%02d", minutes, seconds));
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

    private void showPaymentSuccessDialog() {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            new android.app.AlertDialog.Builder(requireContext())
                    .setTitle("支付成功")
                    .setMessage("会员开通成功！\n感谢您的购买，会员权益已立即生效。")
                    .setPositiveButton("确定", (dialog, which) -> {
                        swipeRefreshLayout.setRefreshing(true);
                        loadMembershipStatus();
                    })
                    .setCancelable(false)
                    .show();
        });
    }

    private void post(final String url, final JSONObject params, final ApiCallback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
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

                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = formData.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                    reader.close();
                    final JSONObject jsonResponse = new JSONObject(response.toString());
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> callback.onSuccess(jsonResponse));
                    }
                } else {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> callback.onError("HTTP错误: " + responseCode));
                    }
                }
            } catch (final Exception e) {
                Log.e(TAG, "post request failed", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> callback.onError("请求异常: " + e.getMessage()));
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void get(final String url, final ApiCallback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL apiUrl = new URL(url);
                connection = (HttpURLConnection) apiUrl.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                    reader.close();
                    final JSONObject jsonResponse = new JSONObject(response.toString());
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> callback.onSuccess(jsonResponse));
                    }
                } else {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> callback.onError("HTTP错误: " + responseCode));
                    }
                }
            } catch (final Exception e) {
                Log.e(TAG, "get request failed", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> callback.onError("请求异常: " + e.getMessage()));
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private Bitmap generateQRCode(String content, int width, int height) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, width, height);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "generate QR failed", e);
            return null;
        }
    }

    private void showProgressDialog(String message) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            try {
                if (progressDialog == null) {
                    progressDialog = new ProgressDialog(requireContext());
                    progressDialog.setCancelable(false);
                }
                progressDialog.setMessage(message);
                if (!progressDialog.isShowing()) progressDialog.show();
            } catch (Exception e) {
                Log.e(TAG, "show progress failed", e);
            }
        });
    }

    private void dismissProgressDialog() {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            try {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
            } catch (Exception e) {
                Log.e(TAG, "dismiss progress failed", e);
            }
        });
    }

    private void loadMembershipPrices() {
        new Thread(() -> {
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
                    while ((line = reader.readLine()) != null) response.append(line);
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
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> updatePriceDisplay());
                        }
                    }
                }
            } catch (final Exception e) {
                Log.e(TAG, "load prices failed", e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private void updatePriceDisplay() {
        try {
            if (getView() == null) return;
            TextView tvBasicPrice = getView().findViewById(R.id.tvBasicPrice);
            TextView tvPremiumPrice = getView().findViewById(R.id.tvPremiumPrice);
            TextView tvLifetimePrice = getView().findViewById(R.id.tvLifetimePrice);
            if (tvBasicPrice != null) tvBasicPrice.setText("¥" + String.format("%.1f", basicPrice) + "/月");
            if (tvPremiumPrice != null) tvPremiumPrice.setText("¥" + String.format("%.1f", premiumPrice) + "/月");
            if (tvLifetimePrice != null) tvLifetimePrice.setText("¥" + String.format("%.1f", lifetimePrice));
        } catch (Exception e) {
            Log.e(TAG, "update price display failed", e);
        }
    }

    private void loadLocalMembershipStatus() {
        String localType = spManager.getMembershipType();
        if (localType != null && !localType.isEmpty() && !localType.equals("免费用户")) {
            currentMembershipType = localType;
            startTime = "";
            expireTime = "";
            updateMembershipUI();
        }
    }

    private void loadMembershipStatus() {
        if (userId == 0) {
            Log.e(TAG, "user id is 0");
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), "用户未登录，无法获取会员状态", Toast.LENGTH_SHORT).show();
                    swipeRefreshLayout.setRefreshing(false);
                });
            }
            return;
        }

        new Thread(() -> {
            try {
                String apiUrl = TokenAuthHelper.appendTokenToUrl(AppConfig.MEMBERSHIP_STATUS_URL + "?user_id=" + userId, requireContext());
                URL url = new URL(apiUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                    reader.close();

                    final JSONObject jsonResponse = new JSONObject(response.toString());
                    if (jsonResponse.getBoolean("success")) {
                        if (jsonResponse.has("membership") && !jsonResponse.isNull("membership")) {
                            JSONObject membership = jsonResponse.getJSONObject("membership");
                            currentMembershipType = membership.getString("membership_type");
                            startTime = membership.getString("start_time");
                            expireTime = membership.getString("expire_time");
                            spManager.setMembershipType(currentMembershipType);
                        } else {
                            loadLocalMembershipStatus();
                        }
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                updateMembershipUI();
                                swipeRefreshLayout.setRefreshing(false);
                                // Toast removed per user request: no need to show "会员状态已更新" when selecting the tab
                            });
                        }
                    } else {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                Toast.makeText(requireContext(), "获取会员状态失败", Toast.LENGTH_SHORT).show();
                                swipeRefreshLayout.setRefreshing(false);
                            });
                        }
                    }
                } else {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            Toast.makeText(requireContext(), "网络请求失败", Toast.LENGTH_SHORT).show();
                            swipeRefreshLayout.setRefreshing(false);
                        });
                    }
                }
            } catch (final Exception e) {
                Log.e(TAG, "load membership status failed", e);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(requireContext(), "获取会员状态失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        swipeRefreshLayout.setRefreshing(false);
                    });
                }
            }
        }).start();
    }

    private void updateMembershipUI() {
        if (getView() == null) return;
        try {
            TextView tvMembershipTitle = getView().findViewById(R.id.tvMembershipTitle);
            TextView tvCurrentMembership = getView().findViewById(R.id.tvCurrentMembership);
            TextView tvStartTime = getView().findViewById(R.id.tvStartTime);
            TextView tvExpireTime = getView().findViewById(R.id.tvExpireTime);

            if (currentMembershipType == null || currentMembershipType.isEmpty()) {
                String localType = spManager.getMembershipType();
                currentMembershipType = (localType != null && !localType.isEmpty()) ? localType : "免费用户";
            }

            if (!"免费用户".equals(currentMembershipType) && !TextUtils.isEmpty(currentMembershipType)) {
                int iconResId = getMemberLevelIcon(currentMembershipType);
                Drawable icon = getResources().getDrawable(iconResId);
                icon.setBounds(0, 0, (int)(icon.getIntrinsicWidth() * 1.0f), (int)(icon.getIntrinsicHeight() * 1.0f));
                SpannableStringBuilder ssb = new SpannableStringBuilder("当前会员状态: " + currentMembershipType + " ");
                ssb.setSpan(new ImageSpan(icon, ImageSpan.ALIGN_BASELINE), ssb.length() - 1, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tvCurrentMembership.setText(ssb);
            } else {
                tvCurrentMembership.setText("当前会员状态: " + currentMembershipType);
            }

            if ("免费用户".equals(currentMembershipType)) {
                tvCurrentMembership.setTextColor(Color.parseColor("#757575"));
                tvStartTime.setVisibility(View.GONE);
                tvExpireTime.setVisibility(View.GONE);
                stopCountDown();
            } else {
                tvCurrentMembership.setTextColor(Color.parseColor("#4CAF50"));
                if (!startTime.isEmpty()) {
                    tvStartTime.setText("开始时间: " + formatDateTime(startTime));
                    tvStartTime.setVisibility(View.VISIBLE);
                } else {
                    tvStartTime.setVisibility(View.GONE);
                }
                if ("终身会员".equals(currentMembershipType) || "永久有效".equals(expireTime) || "永久".equals(expireTime)) {
                    tvExpireTime.setText("到期时间: 永久有效");
                    tvExpireTime.setVisibility(View.VISIBLE);
                    stopCountDown();
                } else if (expireTime != null && !expireTime.isEmpty()) {
                    tvExpireTime.setVisibility(View.VISIBLE);
                    startCountDown();
                } else {
                    tvExpireTime.setVisibility(View.GONE);
                    stopCountDown();
                }
            }
            updateButtonStates();
        } catch (Exception e) {
            Log.e(TAG, "updateMembershipUI failed", e);
        }
    }

    private void startCountDown() {
        stopCountDown();
        countDownRunnable = () -> {
            updateExpireTimeDisplay();
            countDownHandler.postDelayed(countDownRunnable, 1000);
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
        if (getView() == null) return;
        TextView tvExpireTime = getView().findViewById(R.id.tvExpireTime);
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
            Log.e(TAG, "parse expire time failed", e);
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
        if (getView() == null) return;
        Button btnBasic = getView().findViewById(R.id.btnBasic);
        Button btnPremium = getView().findViewById(R.id.btnPremium);
        Button btnLifetime = getView().findViewById(R.id.btnLifetime);

        if ("基础会员".equals(currentMembershipType)) {
            btnBasic.setEnabled(false); btnBasic.setText("已开通"); btnBasic.setBackgroundColor(Color.GRAY);
        } else if ("高级会员".equals(currentMembershipType)) {
            btnPremium.setEnabled(false); btnPremium.setText("已开通"); btnPremium.setBackgroundColor(Color.GRAY);
        } else if ("终身会员".equals(currentMembershipType)) {
            btnLifetime.setEnabled(false); btnLifetime.setText("已开通"); btnLifetime.setBackgroundColor(Color.GRAY);
        }
    }

    private int getMemberLevelIcon(String membershipType) {
        if (membershipType.contains("基础会员")) return R.drawable.ic_member_level_basic;
        else if (membershipType.contains("高级会员")) return R.drawable.ic_member_level_premium;
        else if (membershipType.contains("终身会员")) return R.drawable.ic_member_level_lifetime;
        else return R.drawable.ic_member_level;
    }
}
