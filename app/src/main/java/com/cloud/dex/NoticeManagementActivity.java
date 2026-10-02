package com.cloud.dex;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.cloud.dex.widget.refresh.PtrFrameLayout;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.graphics.Rect;
import androidx.annotation.NonNull;

public class NoticeManagementActivity extends AppCompatActivity {

    private static final String TAG = "NoticeManagementActivity";

    private SharedPreferencesManager spManager;
    private RequestQueue requestQueue;

    private PtrFrameLayout swipeRefreshLayout;
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private FloatingActionButton fabSearch;
    private OptionSelector spinnerApps;

    private NoticeAdapter noticeAdapter;
    private List<NoticeItem> allNoticeList = new ArrayList<>();
    private List<NoticeItem> currentNoticeList = new ArrayList<>();
    private List<AppItem> appList = new ArrayList<>();
    private int currentAppId = 0;
    private boolean isSearchMode = false;
    private String lastSearchKeyword = "";

    // API
    // URL unified: AppConfig.GET_APPS_URL
    // URL unified: AppConfig.GET_USER_NOTICES_URL
    // URL unified: AppConfig.UPDATE_NOTICE_URL
    // URL unified: AppConfig.DELETE_NOTICE_URL
    // URL unified: AppConfig.UPDATE_NOTICE_ENABLED_URL

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notice_management);

        setupActionBar();
        initViews();
        setupRecyclerView();
        setupSwipeRefresh();
        setupFabScrollBehavior();
        fabSearch.setOnClickListener(v -> showSearchDialog());

        requestQueue = Volley.newRequestQueue(this);
        spManager = new SharedPreferencesManager(this);

        if (!checkUserIdOrRedirect()) {
            return;
        }

        if (!isNetworkAvailable()) {
            showToast("网络不可用，请检查网络连接");
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("网络不可用，请检查连接");
            return;
        }

        loadUserApps();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private boolean checkUserIdOrRedirect() {
        int userId = spManager.getUserId();
        if (userId <= 0) {
            Log.e(TAG, "用户ID无效或未登录，跳转到登录页面");
            showToast("用户信息无效，请重新登录");
            navigateToLogin();
            return false;
        }
        return true;
    }

    private void navigateToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo active = cm.getActiveNetworkInfo();
                return active != null && active.isConnected();
            }
        } catch (Exception e) {
            Log.e(TAG, "网络检查失败", e);
        }
        return false;
    }

    private void initViews() {
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        recyclerView = findViewById(R.id.recyclerView);
        progressBar = findViewById(R.id.progressBar);
        tvEmpty = findViewById(R.id.tvEmpty);
        fabSearch = findViewById(R.id.fabSearch);
        spinnerApps = findViewById(R.id.spinnerApps);
    }

    private void setupRecyclerView() {
        noticeAdapter = new NoticeAdapter(currentNoticeList, new NoticeAdapter.OnNoticeActionListener() {
            @Override
            public void onEditClick(NoticeItem notice) {
                showEditNoticeDialog(notice);
            }

            @Override
            public void onDeleteClick(NoticeItem notice) {
                showDeleteConfirmationDialog(notice);
            }
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(noticeAdapter);

        recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                float density = getResources().getDisplayMetrics().density;
                int verticalSpacing = (int) (8 * density);
                outRect.top = verticalSpacing / 2;
                outRect.bottom = verticalSpacing / 2;
            }
        });

        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);
        recyclerView.setRecycledViewPool(new RecyclerView.RecycledViewPool());
        recyclerView.getRecycledViewPool().setMaxRecycledViews(0, 15);
    }

    private void setupSwipeRefresh() {
        swipeRefreshLayout.setColorSchemeColors(
                Color.parseColor("#2196F3"),
                Color.parseColor("#4CAF50"),
                Color.parseColor("#FF9800")
        );
        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (!isNetworkAvailable()) {
                showToast("网络不可用，请检查网络连接");
                swipeRefreshLayout.setRefreshing(false);
                return;
            }
            if (!checkUserIdOrRedirect()) {
                swipeRefreshLayout.setRefreshing(false);
                return;
            }
            loadAllNotices();
        });
    }

    private void setupFabScrollBehavior() {
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING ||
                        newState == RecyclerView.SCROLL_STATE_SETTLING) {
                    hideFab();
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    showFab();
                }
            }
        });
    }

    private void hideFab() {
        if (fabSearch != null && fabSearch.getVisibility() == View.VISIBLE) {
            fabSearch.animate()
                    .scaleX(0f).scaleY(0f).alpha(0f)
                    .setDuration(200)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> fabSearch.setVisibility(View.INVISIBLE))
                    .start();
        }
    }

    private void showFab() {
        if (fabSearch != null && fabSearch.getVisibility() != View.VISIBLE) {
            fabSearch.setVisibility(View.VISIBLE);
            fabSearch.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void setupActionBar() {
        try {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.card_bg)));
                SpannableString title = new SpannableString("公告管理");
                title.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.text_black)), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                getSupportActionBar().setTitle(title);
                getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_arrow_back);
            }
        } catch (Exception e) {
            Log.e(TAG, "ActionBar设置失败", e);
        }
    }

    // ======================= 应用列表 =======================

    private void loadUserApps() {
        int userId = spManager.getUserId();
        if (userId <= 0) {
            Log.e(TAG, "用户ID无效，无法加载应用列表");
            showToast("用户信息无效");
            navigateToLogin();
            return;
        }

        String url = TokenAuthHelper.appendTokenToUrl(AppConfig.GET_APPS_URL + "?user_id=" + userId, NoticeManagementActivity.this);
        StringRequest request = new StringRequest(Request.Method.GET, url,
                response -> {
                    try {
                        JSONObject json = new JSONObject(response);
                        if (json.getBoolean("success")) {
                            JSONArray appsArray = json.getJSONArray("data");
                            setupAppsSpinner(appsArray);
                        } else {
                            showToast("加载应用列表失败: " + json.getString("message"));
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "解析应用列表失败", e);
                        showToast("解析应用列表失败");
                    }
                },
                error -> {
                    Log.e(TAG, "应用列表网络错误", error);
                    showToast("加载应用列表失败");
                });
        requestQueue.add(request);
    }

    private void setupAppsSpinner(JSONArray appsArray) throws JSONException {
        appList.clear();
        for (int i = 0; i < appsArray.length(); i++) {
            JSONObject obj = appsArray.getJSONObject(i);
            int appId = obj.getInt("app_id");
            String appName = obj.getString("app_name");
            String appDesc = obj.getString("app_desc");
            appList.add(new AppItem(appId, appName, appDesc));
        }

        if (appList.isEmpty()) {
            showToast("暂无应用");
            return;
        }

        // 构建下拉列表显示项：第一项为"所有应用"
        List<String> spinnerItems = new ArrayList<>();
        spinnerItems.add("全部公告");
        for (AppItem app : appList) {
            spinnerItems.add(app.getAppName() + " - (" + app.getAppId() + ")");
        }

        spinnerApps.setSheetTitle("选择应用");
        spinnerApps.setOptionList(spinnerItems);

        spinnerApps.setSelection(0);

        spinnerApps.setOnItemSelectedListener(position -> {
            if (position == 0) {
                currentAppId = 0; // 0 表示所有应用
            } else {
                currentAppId = appList.get(position - 1).getAppId();
            }
            isSearchMode = false;
            lastSearchKeyword = "";
            refreshCurrentView();
        });

        loadAllNotices();
    }

    // ======================= 公告数据 =======================

    private void loadAllNotices() {
        int userId = spManager.getUserId();
        if (userId <= 0) {
            Log.e(TAG, "用户ID无效，无法加载公告");
            navigateToLogin();
            return;
        }

        String url = TokenAuthHelper.appendTokenToUrl(AppConfig.GET_USER_NOTICES_URL + "?user_id=" + userId, NoticeManagementActivity.this);
        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        JsonObjectRequest request = new JsonObjectRequest(Request.Method.GET, url, null,
                response -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    try {
                        if (response.getBoolean("success")) {
                            JSONArray data = response.getJSONArray("data");
                            parseAllNoticesData(data);
                            refreshCurrentView();
                        } else {
                            showToast("加载失败: " + response.getString("message"));
                            tvEmpty.setVisibility(View.VISIBLE);
                            tvEmpty.setText("加载失败");
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "解析公告数据失败", e);
                        showToast("数据解析失败");
                        tvEmpty.setVisibility(View.VISIBLE);
                        tvEmpty.setText("数据解析失败");
                    }
                },
                error -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    Log.e(TAG, "公告请求失败", error);
                    showToast("网络请求失败");
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("网络请求失败");
                }) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                headers.put("Accept", "application/json");
                return headers;
            }
        };
        request.setRetryPolicy(new com.android.volley.DefaultRetryPolicy(10000,
                com.android.volley.DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                com.android.volley.DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        requestQueue.add(request);
    }

    // 关键修改：使用 optInt 解析 enabled
    private void parseAllNoticesData(JSONArray array) throws JSONException {
        allNoticeList.clear();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            int enabledInt = obj.optInt("enabled", 1);   // 默认 1（开启）
            boolean isEnabled = (enabledInt == 1);
            NoticeItem item = new NoticeItem(
                    obj.getInt("notice_id"),
                    obj.getInt("app_id"),
                    obj.optString("app_name", ""),
                    obj.optString("title", ""),
                    obj.optString("content", ""),
                    obj.optString("create_time", ""),
                    isEnabled
            );
            allNoticeList.add(item);
        }
    }

    private void refreshCurrentView() {
        List<NoticeItem> appFiltered = new ArrayList<>();
        if (currentAppId == 0) {
            // 所有应用：不过滤
            appFiltered.addAll(allNoticeList);
        } else {
            for (NoticeItem notice : allNoticeList) {
                if (notice.getAppId() == currentAppId) {
                    appFiltered.add(notice);
                }
            }
        }

        if (isSearchMode && !TextUtils.isEmpty(lastSearchKeyword)) {
            currentNoticeList.clear();
            for (NoticeItem notice : appFiltered) {
                if (notice.getTitle().toLowerCase().contains(lastSearchKeyword.toLowerCase())) {
                    currentNoticeList.add(notice);
                }
            }
        } else {
            currentNoticeList.clear();
            currentNoticeList.addAll(appFiltered);
        }

        noticeAdapter.notifyDataSetChanged();
        updateEmptyView();
    }

    private void updateEmptyView() {
        if (currentNoticeList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            if (isSearchMode) {
                tvEmpty.setText("未找到匹配的公告\n搜索关键词: " + lastSearchKeyword);
            } else {
                tvEmpty.setText("暂无公告，请先添加公告");
            }
            recyclerView.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    // ======================= 搜索功能 =======================

    private void showSearchDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("搜索公告");

        androidx.appcompat.widget.LinearLayoutCompat layout = new androidx.appcompat.widget.LinearLayoutCompat(this);
        layout.setOrientation(androidx.appcompat.widget.LinearLayoutCompat.VERTICAL);
        layout.setPadding(50, 30, 50, 30);

        TextInputEditText editText = new TextInputEditText(this);
        editText.setHint("请输入公告标题");
        editText.setSingleLine(true);
        editText.setBackgroundResource(R.drawable.edit_text_background);

        androidx.appcompat.widget.LinearLayoutCompat.LayoutParams params = new androidx.appcompat.widget.LinearLayoutCompat.LayoutParams(
                androidx.appcompat.widget.LinearLayoutCompat.LayoutParams.MATCH_PARENT,
                androidx.appcompat.widget.LinearLayoutCompat.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, 20);
        editText.setLayoutParams(params);
        layout.addView(editText);
        builder.setView(layout);

        builder.setPositiveButton("搜索", (dialog, which) -> {
            String keyword = editText.getText().toString().trim();
            if (!keyword.isEmpty()) {
                performSearch(keyword);
            } else {
                showToast("请输入搜索内容");
            }
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.dismiss());
        builder.setNeutralButton("显示全部", (dialog, which) -> exitSearchMode());

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setTextColor(getResources().getColor(R.color.colorPrimary));
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(getResources().getColor(android.R.color.darker_gray));
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setTextColor(getResources().getColor(R.color.colorAccent));
    }

    private void performSearch(String keyword) {
        isSearchMode = true;
        lastSearchKeyword = keyword;
        refreshCurrentView();
        if (currentNoticeList.isEmpty()) {
            tvEmpty.setText("未找到匹配的公告\n搜索关键词: " + keyword);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            showToast("找到 " + currentNoticeList.size() + " 个匹配的公告");
            scrollToFirstMatch();
        }
    }

    private void exitSearchMode() {
        isSearchMode = false;
        lastSearchKeyword = "";
        refreshCurrentView();
        showToast("已显示全部公告");
    }

    private void scrollToFirstMatch() {
        if (!currentNoticeList.isEmpty()) {
            recyclerView.smoothScrollToPosition(0);
        }
    }

    // ======================= 编辑/删除 =======================

    private void showEditNoticeDialog(NoticeItem notice) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("编辑公告");
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_edit_notice, null);
        builder.setView(dialogView);

        TextInputEditText etTitle = dialogView.findViewById(R.id.etAppName);
        TextInputEditText etContent = dialogView.findViewById(R.id.etAppDescription);
        TextView tvAppInfo = dialogView.findViewById(R.id.tvAppInfo);
        IosLikeSwitch switchEnable = dialogView.findViewById(R.id.switchEnable);
        Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
        Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);

        etTitle.setText(notice.getTitle());
        etContent.setText(notice.getContent());
        tvAppInfo.setText("应用: " + notice.getAppName() + " (ID: " + notice.getAppId() + ")");

        // 正确设置开关状态
        switchEnable.setOnCheckedChangeListener(null);
        switchEnable.setChecked(notice.isEnabled());
        switchEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showToast(isChecked ? "公告已开启" : "公告已关闭");
            updateNoticeEnabled(notice.getNoticeId(), isChecked);
            notice.setEnabled(isChecked);
        });

        AlertDialog dialog = builder.create();
        dialog.show();

        btnConfirm.setOnClickListener(v -> {
            String newTitle = etTitle.getText().toString().trim();
            String newContent = etContent.getText().toString().trim();
            boolean switchEnabled = switchEnable.isChecked();
            if (newTitle.isEmpty()) {
                showToast("标题不能为空");
                return;
            }
            if (newContent.isEmpty()) {
                showToast("内容不能为空");
                return;
            }
            if (!isNetworkAvailable()) {
                showToast("网络不可用");
                return;
            }
            if (!checkUserIdOrRedirect()) return;
            dialog.dismiss();
            updateNotice(notice.getNoticeId(), newTitle, newContent, switchEnabled);
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
    }

    private void updateNoticeEnabled(int noticeId, boolean isEnabled) {
        int userId = spManager.getUserId();
        if (userId <= 0) {
            showToast("用户信息无效，请重新登录");
            navigateToLogin();
            return;
        }

        Log.d("NOTICE_DEBUG", "updateNoticeEnabled: noticeId=" + noticeId + ", userId=" + userId + ", enabled=" + isEnabled);
        JSONObject json = new JSONObject();
        try {
            json.put("notice_id", noticeId);
            json.put("user_id", userId);
            json.put("enabled", isEnabled ? 1 : 0);
        } catch (JSONException e) {
            Log.e(TAG, "JSON构建失败", e);
            showToast("数据格式错误");
            return;
        }

        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, AppConfig.UPDATE_NOTICE_ENABLED_URL, json,
                response -> {
                    try {
                        if (response.getBoolean("success")) {
                            Log.d("NOTICE_DEBUG", "开关更新成功: enabled=" + response.optInt("enabled"));
                            // 同步本地内存
                            for (NoticeItem item : allNoticeList) {
                                if (item.getNoticeId() == noticeId) {
                                    item.setEnabled(isEnabled);
                                    break;
                                }
                            }
                            refreshCurrentView();
                        } else {
                            String msg = response.optString("message", "开关更新失败");
                            Log.w("NOTICE_DEBUG", "开关更新失败: " + msg);
                            loadAllNotices();
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "解析开关响应失败", e);
                        loadAllNotices();
                    }
                },
                error -> {
                    Log.e(TAG, "开关网络错误", error);
                    showToast("网络请求失败，请检查连接");
                    loadAllNotices();
                }) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                return headers;
            }
        };
        request.setRetryPolicy(new com.android.volley.DefaultRetryPolicy(8000,
                com.android.volley.DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                com.android.volley.DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        requestQueue.add(request);
    }

    private void updateNotice(int noticeId, String title, String content, boolean isEnabled) {
        int userId = spManager.getUserId();
        if (userId <= 0) {
            showToast("用户信息无效，请重新登录");
            navigateToLogin();
            return;
        }

        Log.d("NOTICE_DEBUG", "updateNotice: noticeId=" + noticeId + ", userId=" + userId + ", isEnabled=" + isEnabled);
        JSONObject json = new JSONObject();
        try {
            json.put("notice_id", noticeId);
            json.put("user_id", userId);
            json.put("title", title);
            json.put("content", content);
            json.put("enabled", isEnabled ? 1 : 0);
        } catch (JSONException e) {
            Log.e(TAG, "JSON构建失败", e);
            showToast("数据格式错误");
            return;
        }

        showToast("正在更新...");
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, AppConfig.UPDATE_NOTICE_URL, json,
                response -> {
                    try {
                        if (response.getBoolean("success")) {
                            showToast("更新成功");
                        autoRefreshAfterDataChanged();
                            loadAllNotices();
                        } else {
                            String msg = response.optString("message", "更新失败");
                            showToast("更新失败: " + msg);
                            if (msg.contains("用户ID") || msg.contains("登录") || msg.contains("session")) {
                                navigateToLogin();
                            }
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "解析更新响应失败", e);
                        showToast("响应解析失败");
                    }
                },
                error -> {
                    Log.e(TAG, "更新网络错误", error);
                    showToast("网络请求失败");
                }) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                return headers;
            }
        };
        request.setRetryPolicy(new com.android.volley.DefaultRetryPolicy(10000,
                com.android.volley.DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                com.android.volley.DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        requestQueue.add(request);
    }

    private void showDeleteConfirmationDialog(NoticeItem notice) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("确认删除")
                .setMessage("确定删除公告？\n标题: " + notice.getTitle())
                .setPositiveButton("删除", (dialog, which) -> {
                    if (!isNetworkAvailable()) {
                        showToast("网络不可用");
                        return;
                    }
                    if (!checkUserIdOrRedirect()) return;
                    deleteNotice(notice.getNoticeId());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteNotice(int noticeId) {
        int userId = spManager.getUserId();
        if (userId <= 0) {
            showToast("用户信息无效，请重新登录");
            navigateToLogin();
            return;
        }

        showToast("正在删除...");
        Log.d("NOTICE_DEBUG", "deleteNotice: noticeId=" + noticeId + ", userId=" + userId);
        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("notice_id", noticeId);
            jsonBody.put("user_id", userId);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, AppConfig.DELETE_NOTICE_URL, jsonBody,
                response -> {
                    try {
                        if (response.getBoolean("success")) {
                            showToast("删除成功");
                        autoRefreshAfterDataChanged();
                            loadAllNotices();
                        } else {
                            String msg = response.optString("message", "删除失败");
                            showToast("删除失败: " + msg);
                            if (msg.contains("参数错误") || msg.contains("用户ID")) {
                                navigateToLogin();
                            }
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "解析删除响应失败", e);
                        showToast("响应解析失败");
                    }
                },
                error -> {
                    Log.e(TAG, "删除网络错误", error);
                    showToast("网络请求失败");
                });
        request.setRetryPolicy(new com.android.volley.DefaultRetryPolicy(10000,
                com.android.volley.DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                com.android.volley.DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        requestQueue.add(request);
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ===================== 数据变更后自动刷新 =====================

    /**
     * 新增/修改/删除数据成功后调用：让下拉刷新头部平滑铺开并重新拉取列表，
     * 让界面上的数据立刻与服务器保持一致。
     */
    private void autoRefreshAfterDataChanged() {
        try {
            if (swipeRefreshLayout == null) {
                return;
            }
            // 已在刷新中就不重复触发
            if (swipeRefreshLayout.isRefreshing()) {
                return;
            }
            swipeRefreshLayout.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!swipeRefreshLayout.autoRefreshSmoothly()) {
                        // 兜底：动画未能触发时直接加载一次
                        loadAllNotices();
                    }
                }
            }, 220L);
        } catch (Exception e) {
            android.util.Log.e(TAG, "autoRefreshAfterDataChanged failed", e);
        }
    }
}