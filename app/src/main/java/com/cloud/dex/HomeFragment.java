package com.cloud.dex;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.cloud.dex.widget.refresh.PtrFrameLayout;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import androidx.cardview.widget.CardView;

import org.json.JSONException;
import org.json.JSONObject;

public class HomeFragment extends Fragment {

    private static final String TAG = "HomeFragment";

    private SharedPreferencesManager spManager;
    private RequestQueue requestQueue;
    private boolean isDestroyed = false;

    private PtrFrameLayout swipeRefreshLayout;
    private TextView tvAppCount, tvCardCount, tvOnlineCount, tvNoticeCount;
    private View viewOnlineIndicator;
    private CardView cardAppCount, cardCardCount, cardOnlineCount, cardNoticeCount;
    private TextView tvQuote;


    private BroadcastReceiver statsRefreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "收到刷新统计广播");
            fetchStatisticsWithVolley();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        spManager = new SharedPreferencesManager(requireContext());
        requestQueue = Volley.newRequestQueue(requireContext());

        initViews(view);
        setupSwipeRefreshLayout(view);
        setupCardClickListeners(view);

        // 自动开启每日一言的跑马灯滚动
        tvQuote.setSelected(true);

        fetchStatisticsWithVolley();
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle("");

        try {
            LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
                    statsRefreshReceiver,
                    new IntentFilter("com.cloud.dex.ACTION_REFRESH_STATISTICS")
            );
            fetchStatisticsWithVolley();
        } catch (Exception e) {
            Log.e(TAG, "onResume error", e);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(statsRefreshReceiver);
        } catch (Exception e) {
            Log.e(TAG, "unregister receiver failed", e);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        isDestroyed = true;
    }

    private void initViews(View view) {
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        tvAppCount = view.findViewById(R.id.tvAppCount);
        tvCardCount = view.findViewById(R.id.tvCardCount);
        tvOnlineCount = view.findViewById(R.id.tvOnlineCount);
        tvNoticeCount = view.findViewById(R.id.tvNoticeCount);
        viewOnlineIndicator = view.findViewById(R.id.viewOnlineIndicator);
        cardAppCount = view.findViewById(R.id.cardAppCount);
        cardCardCount = view.findViewById(R.id.cardCardCount);
        cardOnlineCount = view.findViewById(R.id.cardOnlineCount);
        cardNoticeCount = view.findViewById(R.id.cardNoticeCount);
        tvQuote = view.findViewById(R.id.tvQuote);
    }

    private void setupSwipeRefreshLayout(View view) {
        swipeRefreshLayout.setColorSchemeColors(
                android.graphics.Color.parseColor("#2196F3"),
                android.graphics.Color.parseColor("#4CAF50"),
                android.graphics.Color.parseColor("#FF9800")
        );

        swipeRefreshLayout.setOnRefreshListener(() -> {
            startRefreshAnimation();
            verifyAndRefreshData();
        });
    }

    private void setupCardClickListeners(View view) {
        cardAppCount.setOnClickListener(v -> {
            v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100)
                    .withEndAction(() -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                        navigateToAppList();
                    }).start();
        });

        cardCardCount.setOnClickListener(v -> {
            v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100)
                    .withEndAction(() -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                        navigateToKamiManagement();
                    }).start();
        });

        cardOnlineCount.setOnClickListener(v -> {
            v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100)
                    .withEndAction(() -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                        startActivity(new Intent(requireContext(), UserStatisticsActivity.class));
                    }).start();
        });

        cardNoticeCount.setOnClickListener(v -> {
            v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100)
                    .withEndAction(() -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                        navigateToNoticeManagement();
                    }).start();
        });
    }

    private void navigateToAppList() {
        try {
            startActivity(new Intent(requireContext(), AppListActivity.class));
        } catch (Exception e) {
            Log.e(TAG, "navigateToAppList failed", e);
        }
    }

    private void navigateToKamiManagement() {
        try {
            startActivity(new Intent(requireContext(), KamiManagementActivity.class));
        } catch (Exception e) {
            Log.e(TAG, "navigateToKamiManagement failed", e);
        }
    }

    private void navigateToNoticeManagement() {
        try {
            startActivity(new Intent(requireContext(), NoticeManagementActivity.class));
        } catch (Exception e) {
            Log.e(TAG, "navigateToNoticeManagement failed", e);
        }
    }

    // ===================== Quote =====================

    private void fetchQuote() {
        if (isDestroyed || tvQuote == null) return;

        StringRequest quoteRequest = new StringRequest(Request.Method.GET, AppConfig.YIYAN_API_URL,
                response -> {
                    if (isDestroyed) return;
                    try {
                        JSONObject json = new JSONObject(response);
                        if (json.optInt("code") == 200) {
                            JSONObject data = json.getJSONObject("data");
                            String content = data.optString("content");
                            String author = data.optString("author");
                            String displayText = content;
                            if (!TextUtils.isEmpty(author)) {
                                displayText += " —— " + author;
                            }
                            tvQuote.setText(displayText);
                        } else {
                            tvQuote.setText(R.string.quote_load_failed);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "parse quote failed", e);
                        tvQuote.setText(R.string.quote_parse_failed);
                    }
                },
                error -> {
                    Log.e(TAG, "fetch quote failed", error);
                    if (!isDestroyed) tvQuote.setText(R.string.quote_network_error);
                });

        if (requestQueue == null) requestQueue = Volley.newRequestQueue(requireContext());
        requestQueue.add(quoteRequest);
    }

    // ===================== Refresh Animation =====================

    private void startRefreshAnimation() {
        if (isDestroyed || getActivity() == null) return;

        getActivity().runOnUiThread(() -> {
            try {
                int currentAppCount, currentCardCount, currentOnlineCount, currentNoticeCount;
                try { currentAppCount = Integer.parseInt(tvAppCount.getText().toString()); } catch (NumberFormatException e) { currentAppCount = 0; }
                try { currentCardCount = Integer.parseInt(tvCardCount.getText().toString()); } catch (NumberFormatException e) { currentCardCount = 0; }
                try { currentOnlineCount = Integer.parseInt(tvOnlineCount.getText().toString()); } catch (NumberFormatException e) { currentOnlineCount = 0; }
                try { currentNoticeCount = Integer.parseInt(tvNoticeCount.getText().toString()); } catch (NumberFormatException e) { currentNoticeCount = 0; }

                createRefreshPulseAnimation(tvAppCount, currentAppCount);
                createRefreshPulseAnimation(tvCardCount, currentCardCount);
                createRefreshPulseAnimation(tvOnlineCount, currentOnlineCount);
                createRefreshPulseAnimation(tvNoticeCount, currentNoticeCount);
            } catch (Exception e) {
                Log.e(TAG, "startRefreshAnimation failed", e);
            }
        });
    }

    private void createRefreshPulseAnimation(final TextView textView, final int originalValue) {
        if (isDestroyed || getActivity() == null) return;
        try {
            textView.animate()
                    .scaleX(1.1f).scaleY(1.1f).setDuration(200)
                    .setInterpolator(new OvershootInterpolator())
                    .withEndAction(() -> {
                        if (isDestroyed || getActivity() == null) return;
                        textView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start();
                    }).start();

            final Handler handler = new Handler();
            final int[] pulseCount = {0};
            final int maxPulses = 3;

            Runnable pulseRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isDestroyed || getActivity() == null || pulseCount[0] >= maxPulses) {
                        textView.setText(String.valueOf(originalValue));
                        return;
                    }
                    int pulseValue = originalValue + (pulseCount[0] % 2 == 0 ? 1 : -1);
                    textView.setText(String.valueOf(pulseValue));
                    pulseCount[0]++;
                    handler.postDelayed(this, 150);
                }
            };
            handler.postDelayed(pulseRunnable, 100);
        } catch (Exception e) {
            Log.e(TAG, "createRefreshPulseAnimation failed", e);
        }
    }

    // ===================== Statistics =====================

    private void verifyAndRefreshData() {
        if (isDestroyed) {
            if (swipeRefreshLayout.isRefreshing()) swipeRefreshLayout.setRefreshing(false);
            return;
        }
        fetchStatisticsWithVolley();
    }


    private void fetchStatisticsWithVolley() {
        if (isDestroyed) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        if (!spManager.isUserDataValid()) {
            swipeRefreshLayout.setRefreshing(false);
            showToast("登录信息异常，请重新登录");
            directLogout();
            return;
        }

        int userId = spManager.getUserId();
        String url = TokenAuthHelper.appendTokenToUrl(AppConfig.STATISTICS_URL + "?user_id=" + userId, requireContext());

        StringRequest stringRequest = new StringRequest(Request.Method.GET, url,
                response -> {
                    if (isDestroyed) return;
                    try {
                        JSONObject jsonResponse = new JSONObject(response);
                        int code = jsonResponse.getInt("code");
                        if (code == 200) {
                            JSONObject data = jsonResponse.getJSONObject("data");
                            int appCount = data.getInt("appCount");
                            int cardCount = data.getInt("cardCount");
                            int onlineCount = data.getInt("onlineCount");
                            int noticeCount = data.optInt("noticeCount", 0);
                            updateStatisticsUI(appCount, cardCount, onlineCount, noticeCount);
                            fetchQuote();
                        } else {
                            if (isAuthenticationError(code, jsonResponse.optString("message", ""))) {
                                showToast("登录已过期，请重新登录");
                                directLogout();
                            } else {
                                showToast("数据加载失败");
                                updateStatisticsUI(0, 0, 0, 0);
                            }
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "parse stats failed", e);
                        updateStatisticsUI(0, 0, 0, 0);
                    } finally {
                        if (!isDestroyed) {
                            swipeRefreshLayout.setRefreshing(false);
                        }
                    }
                },
                error -> {
                    if (isDestroyed) return;
                    swipeRefreshLayout.setRefreshing(false);
                    if (error.networkResponse != null) {
                        int statusCode = error.networkResponse.statusCode;
                        if (statusCode == 401 || statusCode == 403) {
                            showToast("登录已过期，请重新登录");
                            directLogout();
                        } else {
                            updateStatisticsUI(0, 0, 0, 0);
                        }
                    } else {
                        updateStatisticsUI(0, 0, 0, 0);
                    }
                }) {
            @Override
            public java.util.Map<String, String> getHeaders() {
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("Content-Type", "application/json");
                headers.put("Accept", "application/json");
                return headers;
            }
        };

        requestQueue.add(stringRequest);
    }

    private void updateStatisticsUI(int appCount, int cardCount, int onlineCount, int noticeCount) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (isDestroyed || getActivity() == null) return;
            try {
                animateNumberChange(tvAppCount, appCount);
                animateNumberChange(tvCardCount, cardCount);
                animateNumberChange(tvOnlineCount, onlineCount);
                updateOnlineIndicatorColor(onlineCount);
                animateNumberChange(tvNoticeCount, noticeCount);
            } catch (Exception e) {
                try {
                    tvAppCount.setText(String.valueOf(appCount));
                    tvCardCount.setText(String.valueOf(cardCount));
                    tvOnlineCount.setText(String.valueOf(onlineCount));
                    tvNoticeCount.setText(String.valueOf(noticeCount));
                    updateOnlineIndicatorColor(onlineCount);
                } catch (Exception ex) {
                    Log.e(TAG, "fallback setText failed", ex);
                }
            }
        });
    }

    private void animateNumberChange(final TextView textView, int targetValue) {
        if (getActivity() == null) return;
        try {
            String currentText = textView.getText().toString();
            int startValue;
            try { startValue = Integer.parseInt(currentText); } catch (NumberFormatException e) { startValue = 0; }
            if (startValue == targetValue) return;

            ValueAnimator animator = ValueAnimator.ofInt(startValue, targetValue);
            animator.setDuration(1000);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(animation -> {
                if (isDestroyed || getActivity() == null) { animation.cancel(); return; }
                textView.setText(String.valueOf((int) animation.getAnimatedValue()));
            });
            animator.addListener(new Animator.AnimatorListener() {
                @Override public void onAnimationStart(Animator animation) {}
                @Override public void onAnimationEnd(Animator animation) {
                    if (!isDestroyed && getActivity() != null) textView.setText(String.valueOf(targetValue));
                }
                @Override public void onAnimationCancel(Animator animation) {
                    if (!isDestroyed && getActivity() != null) textView.setText(String.valueOf(targetValue));
                }
                @Override public void onAnimationRepeat(Animator animation) {}
            });
            animator.start();
        } catch (Exception e) {
            Log.e(TAG, "animateNumberChange failed", e);
            if (!isDestroyed) textView.setText(String.valueOf(targetValue));
        }
    }

    private void updateOnlineIndicatorColor(int onlineCount) {
        if (viewOnlineIndicator == null) return;
        viewOnlineIndicator.setBackgroundResource(
                onlineCount > 0 ? R.drawable.dot_online : R.drawable.dot_offline
        );
    }

    private boolean isAuthenticationError(int code, String message) {
        if (code == 401 || code == 403) return true;
        if (message == null || message.isEmpty()) return false;
        // 仅匹配明确的认证失败信号,避免误杀(如统计接口消息中带"登录"字样)
        return message.contains("未登录")
                || message.contains("登录已过期")
                || message.contains("登录过期")
                || message.contains("token无效")
                || message.contains("token 无效")
                || message.contains("token已失效")
                || message.contains("认证失败")
                || message.contains("session失效")
                || message.contains("session 失效");
    }

    private void directLogout() {
        spManager.clearLoginInfo();
        spManager.clearMembershipInfo();
        Intent intent = new Intent(requireContext(), LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        requireContext().startActivity(intent);
        if (getActivity() != null) getActivity().finish();
    }

    private void showToast(String message) {
        if (getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }
}
