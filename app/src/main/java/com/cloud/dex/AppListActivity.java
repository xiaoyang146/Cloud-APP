package com.cloud.dex;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.cloud.dex.widget.refresh.PtrFrameLayout;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;


import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.android.volley.toolbox.JsonObjectRequest;
import org.json.JSONObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import androidx.annotation.NonNull;

public class AppListActivity extends AppCompatActivity {

    private static final String TAG = "AppListActivity";

    private SharedPreferencesManager spManager;
    private RequestQueue requestQueue;

    private RecyclerView recyclerView;
    private AppListAdapter appListAdapter;
    private PtrFrameLayout swipeRefreshLayout;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private FloatingActionButton fabSearch;

    private List<AppItem> appList = new ArrayList<>();
    private List<AppItem> filteredAppList = new ArrayList<>(); // 添加过滤后的列表
    private boolean isSearchMode = false; // 搜索模式标志

    // API地址
    // URL unified: now using AppConfig.GET_ALL_APPS_URL
    // URL unified: now using AppConfig.DELETE_APP_URL
    // URL unified: now using AppConfig.UPDATE_APP_URL

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_list);

        // 设置ActionBar
        setupActionBar();

        initViews();
        setupRecyclerView();
        setupSwipeRefresh();

        spManager = new SharedPreferencesManager(this);
        requestQueue = Volley.newRequestQueue(this);

        initViews();
        setupRecyclerView();
        setupSwipeRefresh();
        setupClickListeners();

        // 加载应用列表
        loadAppList();
    }

    private void setupActionBar() {
        try {
            if (getSupportActionBar() != null) {
                // 设置背景为主题色
                getSupportActionBar().setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.card_bg)));

                // 创建标题
                SpannableString title = new SpannableString("应用管理");
                title.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.text_black)), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                getSupportActionBar().setTitle(title);
                getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_arrow_back);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置ActionBar失败", e);
        }
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerView);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        progressBar = findViewById(R.id.progressBar);
        tvEmpty = findViewById(R.id.tvEmpty);
        fabSearch = findViewById(R.id.fabSearch); // 改为搜索按钮
    }

    private void setupRecyclerView() {
        appListAdapter = new AppListAdapter(this, filteredAppList, new AppListAdapter.OnAppItemClickListener() {
            @Override
            public void onAppClick(AppItem appItem) {
                showAppDetail(appItem);
            }

            @Override
            public void onAppLongClick(AppItem appItem) {
                showAppActions(appItem);
            }

            @Override
            public void onEditClick(AppItem appItem) {
                showEditAppDialog(appItem);
            }

            @Override
            public void onDeleteClick(AppItem appItem) {
                showDeleteConfirmDialog(appItem);
            }
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(appListAdapter);

        // 设置卡片间垂直间距（可灵活调整）
        recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                super.getItemOffsets(outRect, view, parent, state);

                // 垂直间距：8dp（上下各4dp，相邻卡片间距 = 8dp）
                float density = getResources().getDisplayMetrics().density;
                int verticalSpacing = (int) (8 * density);
                outRect.top = verticalSpacing / 2;
                outRect.bottom = verticalSpacing / 2;
            }
        });

        // 添加滚动监听器
        setupEnhancedMomentumScrolling();

        // 应用性能优化
        applySmoothScrollingOptimizations();
    }

    /**
     * 应用所有滑动优化
     */
    private void applySmoothScrollingOptimizations() {
        try {
            // 性能优化
            recyclerView.setHasFixedSize(true);
            recyclerView.setItemViewCacheSize(20);

            // 启用子视图重用优化
            recyclerView.setRecycledViewPool(new RecyclerView.RecycledViewPool());
            recyclerView.getRecycledViewPool().setMaxRecycledViews(0, 15);
        } catch (Exception e) {
            Log.e(TAG, "应用滑动优化失败", e);
        }
    }

    /**
     * 设置增强的惯性滑动效果
     */
    private void setupEnhancedMomentumScrolling() {
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);

                switch (newState) {
                    case RecyclerView.SCROLL_STATE_DRAGGING:
                    case RecyclerView.SCROLL_STATE_SETTLING:
                        // 滑动时隐藏搜索按钮（包括拖动和惯性滑动）
                        hideFab();
                        break;
                    case RecyclerView.SCROLL_STATE_IDLE:
                        // 停止滑动时显示搜索按钮
                        showFab();
                        break;
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                // 保留滑动时的淡入淡出效果
                applyScrollFadeEffects();
            }
        });
    }

    private void applyScrollFadeEffects() {
        // 为可见项添加滑动时的淡入淡出效果
        LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (layoutManager == null) return;

        int firstVisible = layoutManager.findFirstVisibleItemPosition();
        int lastVisible = layoutManager.findLastVisibleItemPosition();

        for (int i = firstVisible; i <= lastVisible; i++) {
            View view = layoutManager.findViewByPosition(i);
            if (view != null) {
                // 根据位置添加淡入效果
                applyItemFadeAnimation(view, i, firstVisible, lastVisible);
            }
        }
    }

    private void applyItemFadeAnimation(View view, int position, int firstVisible, int lastVisible) {
        // 为边缘项添加轻微的透明度变化
        float alpha = 1f;
        int visibleCount = lastVisible - firstVisible;

        if (position == firstVisible || position == lastVisible) {
            alpha = 0.95f; // 边缘项稍微透明
        }

        view.setAlpha(alpha);
    }

    private void hideFab() {
        if (fabSearch != null && fabSearch.getVisibility() == View.VISIBLE) {
            fabSearch.animate()
                    .scaleX(0f)
                    .scaleY(0f)
                    .alpha(0f)
                    .setDuration(200)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            fabSearch.setVisibility(View.INVISIBLE);
                        }
                    })
                    .start();
        }
    }

    private void showFab() {
        if (fabSearch != null && fabSearch.getVisibility() != View.VISIBLE) {
            fabSearch.setVisibility(View.VISIBLE);
            fabSearch.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void setupSwipeRefresh() {
        swipeRefreshLayout.setColorSchemeColors(
                Color.parseColor("#2196F3"),   // 蓝
                Color.parseColor("#4CAF50"),   // 绿
                Color.parseColor("#FF9800")    // 橙
        );

        swipeRefreshLayout.setOnRefreshListener(new PtrFrameLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                loadAppList();
            }
        });
    }

    private void setupClickListeners() {
        fabSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 显示搜索对话框
                showSearchDialog();
            }
        });
    }

    /**
     * 显示搜索对话框
     */
    private void showSearchDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("搜索应用");

        // 创建输入布局
        androidx.appcompat.widget.LinearLayoutCompat layout = new androidx.appcompat.widget.LinearLayoutCompat(this);
        layout.setOrientation(androidx.appcompat.widget.LinearLayoutCompat.VERTICAL);
        layout.setPadding(50, 30, 50, 30);

        TextInputEditText editText = new TextInputEditText(this);
        editText.setHint("请输入应用名称");
        editText.setSingleLine(true);
        editText.setBackgroundResource(R.drawable.edit_text_background); // 需要创建这个drawable

        androidx.appcompat.widget.LinearLayoutCompat.LayoutParams params = new androidx.appcompat.widget.LinearLayoutCompat.LayoutParams(
                androidx.appcompat.widget.LinearLayoutCompat.LayoutParams.MATCH_PARENT,
                androidx.appcompat.widget.LinearLayoutCompat.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, 20);
        editText.setLayoutParams(params);

        layout.addView(editText);

        builder.setView(layout);

        builder.setPositiveButton("搜索", (dialog, which) -> {
            String searchText = editText.getText().toString().trim();
            if (!searchText.isEmpty()) {
                searchApp(searchText);
            } else {
                showToast("请输入搜索内容");
            }
        });

        builder.setNegativeButton("取消", (dialog, which) -> {
            dialog.dismiss();
        });

        builder.setNeutralButton("显示全部", (dialog, which) -> {
            showAllApps();
        });

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();

        // 设置按钮颜色
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(getResources().getColor(R.color.colorPrimary));
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(getResources().getColor(android.R.color.darker_gray));
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(getResources().getColor(R.color.colorAccent));
    }

    /**
     * 搜索应用
     */
    private void searchApp(String searchText) {
        filteredAppList.clear();
        isSearchMode = true;

        for (AppItem app : appList) {
            if (app.getAppName().toLowerCase().contains(searchText.toLowerCase())) {
                filteredAppList.add(app);
            }
        }

        appListAdapter.notifyDataSetChanged();

        if (filteredAppList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("未找到匹配的应用\n搜索关键词: " + searchText);
        } else {
            tvEmpty.setVisibility(View.GONE);
            // 滚动到第一个匹配项
            scrollToFirstMatch();
            showToast("找到 " + filteredAppList.size() + " 个匹配的应用");
        }
    }

    /**
     * 显示所有应用
     */
    private void showAllApps() {
        filteredAppList.clear();
        filteredAppList.addAll(appList);
        isSearchMode = false;
        appListAdapter.notifyDataSetChanged();

        if (filteredAppList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("暂无应用\n点击右下角按钮搜索应用");
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    /**
     * 滚动到第一个匹配项
     */
    private void scrollToFirstMatch() {
        if (!filteredAppList.isEmpty()) {
            recyclerView.smoothScrollToPosition(0);
        }
    }

    /**
     * 显示编辑应用对话框
     */
    /**
     * 显示编辑应用对话框
     */
    private void showEditAppDialog(AppItem appItem) {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("编辑应用");

        // 加载编辑应用对话框布局
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_app1, null);
        builder.setView(dialogView);

        // 获取布局中的视图
        TextInputEditText etAppName = dialogView.findViewById(R.id.etAppName);
        TextInputEditText etAppDesc = dialogView.findViewById(R.id.etAppDesc);
        Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
        Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);

        // 设置当前应用信息
        etAppName.setText(appItem.getAppName());
        etAppDesc.setText(appItem.getAppDesc());

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();

        btnConfirm.setOnClickListener(v -> {
            String newAppName = etAppName.getText().toString().trim();
            String newAppDesc = etAppDesc.getText().toString().trim();

            if (TextUtils.isEmpty(newAppName)) {
                showToast("应用名称不能为空");
                return;
            }

            dialog.dismiss();
            updateApp(appItem.getAppId(), newAppName, newAppDesc);
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
    }

    /**
     * 更新应用信息
     */
    /**
     * 更新应用信息
     */
    private void updateApp(int appId, String appName, String appDesc) {
        if (!spManager.isUserDataValid()) {
            showToast("登录信息已过期");
            navigateToLogin();
            return;
        }

        int userId = spManager.getUserId();

        // 添加详细的日志
        Log.d(TAG, "更新应用参数 - user_id: ***, app_id: " + appId +
                ", app_name: " + appName + ", app_desc: " + InputValidator.sanitizeForLog(appDesc));

        // 创建JSON请求体
        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("user_id", userId);
            jsonBody.put("app_id", appId);
            jsonBody.put("app_name", appName);
            jsonBody.put("app_desc", appDesc);
        } catch (JSONException e) {
            Log.e(TAG, "创建JSON请求体失败", e);
            showToast("数据格式错误");
            return;
        }

        JsonObjectRequest updateRequest = new JsonObjectRequest(Request.Method.POST, TokenAuthHelper.appendTokenToUrl(AppConfig.UPDATE_APP_URL, AppListActivity.this), jsonBody,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        Log.d(TAG, "更新应用响应: " + InputValidator.sanitizeForLog(response.toString()));
                        try {
                            boolean success = response.getBoolean("success");
                            String message = response.getString("message");

                            if (success) {
                                showToast("应用更新成功");
                                autoRefreshAfterDataChanged();
                                // 重新加载列表
                                loadAppList();
                            } else {
                                showToast("更新失败: " + message);
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析更新响应失败", e);
                            showToast("响应解析失败");
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "更新应用网络错误", error);

                        // 更详细的错误信息
                        String errorMessage = "网络错误，更新失败";
                        if (error.networkResponse != null) {
                            int statusCode = error.networkResponse.statusCode;
                            String responseData = new String(error.networkResponse.data);
                            errorMessage = "网络错误，状态码: " + statusCode + ", 响应: " + responseData;
                            Log.e(TAG, "详细错误信息: " + errorMessage);
                        } else if (error.getMessage() != null) {
                            errorMessage = error.getMessage();
                        }

                        showToast(errorMessage);
                    }
                }) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                return headers;
            }
        };

        requestQueue.add(updateRequest);
    }

    private void loadAppList() {
        if (!spManager.isUserDataValid()) {
            showToast("登录信息已过期，请重新登录");
            navigateToLogin();
            return;
        }

        int userId = spManager.getUserId();
        String url = TokenAuthHelper.appendTokenToUrl(AppConfig.GET_ALL_APPS_URL + "?user_id=" + userId, AppListActivity.this);

        Log.d(TAG, "加载应用列表URL: " + url);

        // 显示加载状态
        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        StringRequest appsRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        progressBar.setVisibility(View.GONE);
                        swipeRefreshLayout.setRefreshing(false);

                        Log.d(TAG, "应用列表响应: " + InputValidator.sanitizeForLog(response));
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            boolean success = jsonResponse.getBoolean("success");

                            if (success) {
                                JSONArray appsArray = jsonResponse.getJSONArray("data");
                                parseAppsData(appsArray);
                            } else {
                                String message = jsonResponse.getString("message");
                                showToast("加载失败: " + message);
                                tvEmpty.setVisibility(View.VISIBLE);
                                tvEmpty.setText("加载失败: " + message);
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析应用列表响应失败", e);
                            showToast("数据解析失败");
                            tvEmpty.setVisibility(View.VISIBLE);
                            tvEmpty.setText("数据解析失败");
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        progressBar.setVisibility(View.GONE);
                        swipeRefreshLayout.setRefreshing(false);

                        Log.e(TAG, "加载应用列表网络错误", error);
                        String errorMessage = "网络连接错误";
                        if (error.networkResponse != null) {
                            errorMessage = "网络错误，状态码: " + error.networkResponse.statusCode;
                        }
                        showToast("加载失败: " + errorMessage);
                        tvEmpty.setVisibility(View.VISIBLE);
                        tvEmpty.setText("网络连接失败\n请下拉刷新重试");
                    }
                });

        requestQueue.add(appsRequest);
    }

    private void parseAppsData(JSONArray appsArray) throws JSONException {
        appList.clear();
        filteredAppList.clear();

        for (int i = 0; i < appsArray.length(); i++) {
            JSONObject appObject = appsArray.getJSONObject(i);
            int appId = appObject.getInt("app_id");
            String appName = appObject.getString("app_name");
            String appDesc = appObject.getString("app_desc");
            String createTime = appObject.getString("create_time");

            AppItem appItem = new AppItem(appId, appName, appDesc);
            appItem.setCreateTime(createTime);
            appList.add(appItem);
            filteredAppList.add(appItem); // 初始时显示所有应用
        }

        appListAdapter.notifyDataSetChanged();

        // 更新空状态显示
        if (filteredAppList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("暂无应用\n点击右下角按钮搜索应用");
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    private void showAppDetail(AppItem appItem) {
        // 简单的应用详情显示
        String detail = "应用名称: " + appItem.getAppName() + "\n" +
                "应用ID:    " + appItem.getAppId() + "\n" +
                "应用描述:    " + (appItem.getAppDesc().isEmpty() ? "暂无描述" : appItem.getAppDesc()) + "\n" +
                "创建时间:    " + appItem.getCreateTime();

        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("应用详情")
                .setMessage(detail)
                .setPositiveButton("确定", null)
                .show();
    }

    private void showAppActions(AppItem appItem) {
        // 简单的操作菜单
        String[] actions = {"编辑应用", "删除应用", "查看详情"};

        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("选择操作")
                .setItems(actions, (dialog, which) -> {
                    switch (which) {
                        case 0: // 编辑
                            editApp(appItem);
                            break;
                        case 1: // 删除
                            showDeleteConfirmDialog(appItem);
                            break;
                        case 2: // 查看详情
                            showAppDetail(appItem);
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editApp(AppItem appItem) {
        // 显示编辑应用对话框
        showEditAppDialog(appItem);
    }

    private void showDeleteConfirmDialog(AppItem appItem) {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("确认删除？")
                .setMessage("确定要删除应用 \"" + appItem.getAppName() + "\" 吗？此操作不可恢复！")
                .setPositiveButton("删除", (dialog, which) -> {
                    deleteApp(appItem);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteApp(AppItem appItem) {
        if (!spManager.isUserDataValid()) {
            showToast("登录信息已过期");
            navigateToLogin();
            return;
        }

        int userId = spManager.getUserId();

        // 创建请求参数
        Map<String, String> params = new HashMap<>();
        params.put("user_id", String.valueOf(userId));
        params.put("app_id", String.valueOf(appItem.getAppId()));

        StringRequest deleteRequest = new StringRequest(Request.Method.POST, TokenAuthHelper.appendTokenToUrl(AppConfig.DELETE_APP_URL, AppListActivity.this),
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.d(TAG, "删除应用响应: " + InputValidator.sanitizeForLog(response));
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            boolean success = jsonResponse.getBoolean("success");
                            String message = jsonResponse.getString("message");

                            if (success) {
                                showToast("删除成功");
                                autoRefreshAfterDataChanged();
                                // 重新加载列表
                                loadAppList();
                            } else {
                                showToast("删除失败: " + message);
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析删除响应失败", e);
                            showToast("响应解析失败");
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "删除应用网络错误", error);
                        showToast("网络错误，删除失败");
                    }
                }) {
            @Override
            protected Map<String, String> getParams() {
                return params;
            }

            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/x-www-form-urlencoded");
                return headers;
            }
        };

        requestQueue.add(deleteRequest);
    }

    private void navigateToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
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
                        loadAppList();
                    }
                }
            }, 220L);
        } catch (Exception e) {
            android.util.Log.e(TAG, "autoRefreshAfterDataChanged failed", e);
        }
    }
}