package com.cloud.dex;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.cloud.dex.widget.refresh.PtrFrameLayout;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppsFragment extends Fragment {

    private static final String TAG = "AppsFragment";

    private SharedPreferencesManager spManager;
    private RequestQueue requestQueue;

    private RecyclerView recyclerView;
    private AppListAdapter appListAdapter;
    private PtrFrameLayout swipeRefreshLayout;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private FloatingActionButton fabSearch;

    private List<AppItem> appList = new ArrayList<>();
    private List<AppItem> filteredAppList = new ArrayList<>();
    private boolean isSearchMode = false;


    private boolean isDestroyed = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_app_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        spManager = new SharedPreferencesManager(requireContext());
        requestQueue = Volley.newRequestQueue(requireContext());

        initViews(view);
        setupRecyclerView(view);
        setupSwipeRefresh(view);
        setupClickListeners(view);

        loadAppList();
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle("应用管理");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        isDestroyed = true;
    }

    private void initViews(View view) {
        recyclerView = view.findViewById(R.id.recyclerView);
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        progressBar = view.findViewById(R.id.progressBar);
        tvEmpty = view.findViewById(R.id.tvEmpty);
        fabSearch = view.findViewById(R.id.fabSearch);
    }

    private void setupRecyclerView(View view) {
        appListAdapter = new AppListAdapter(requireContext(), filteredAppList, new AppListAdapter.OnAppItemClickListener() {
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

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(appListAdapter);

        recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                super.getItemOffsets(outRect, view, parent, state);
                float density = getResources().getDisplayMetrics().density;
                int verticalSpacing = (int) (8 * density);
                outRect.top = verticalSpacing / 2;
                outRect.bottom = verticalSpacing / 2;
            }
        });

        setupEnhancedMomentumScrolling();
        applySmoothScrollingOptimizations();
    }

    private void applySmoothScrollingOptimizations() {
        try {
            recyclerView.setHasFixedSize(true);
            recyclerView.setItemViewCacheSize(20);
            recyclerView.setRecycledViewPool(new RecyclerView.RecycledViewPool());
            recyclerView.getRecycledViewPool().setMaxRecycledViews(0, 15);
        } catch (Exception e) {
            Log.e(TAG, "applySmoothScrollingOptimizations failed", e);
        }
    }

    private void setupEnhancedMomentumScrolling() {
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                switch (newState) {
                    case RecyclerView.SCROLL_STATE_DRAGGING:
                    case RecyclerView.SCROLL_STATE_SETTLING:
                        hideFab();
                        break;
                    case RecyclerView.SCROLL_STATE_IDLE:
                        showFab();
                        break;
                }
            }
        });
    }

    private void hideFab() {
        if (fabSearch != null && fabSearch.getVisibility() == View.VISIBLE) {
            fabSearch.animate()
                    .scaleX(0f).scaleY(0f).alpha(0f).setDuration(200)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> fabSearch.setVisibility(View.INVISIBLE))
                    .start();
        }
    }

    private void showFab() {
        if (fabSearch != null && fabSearch.getVisibility() != View.VISIBLE) {
            fabSearch.setVisibility(View.VISIBLE);
            fabSearch.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void setupSwipeRefresh(View view) {
        swipeRefreshLayout.setColorSchemeColors(
                android.graphics.Color.parseColor("#2196F3"),
                android.graphics.Color.parseColor("#4CAF50"),
                android.graphics.Color.parseColor("#FF9800")
        );
        swipeRefreshLayout.setOnRefreshListener(() -> loadAppList());
    }

    private void setupClickListeners(View view) {
        fabSearch.setOnClickListener(v -> showSearchDialog());
    }

    private void showSearchDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
        builder.setTitle("搜索应用");

        androidx.appcompat.widget.LinearLayoutCompat layout = new androidx.appcompat.widget.LinearLayoutCompat(requireContext());
        layout.setOrientation(androidx.appcompat.widget.LinearLayoutCompat.VERTICAL);
        layout.setPadding(50, 30, 50, 30);

        TextInputEditText editText = new TextInputEditText(requireContext());
        editText.setHint("请输入应用名称");
        editText.setSingleLine(true);
        editText.setBackgroundResource(R.drawable.edit_text_background);

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
        builder.setNegativeButton("取消", (dialog, which) -> dialog.dismiss());
        builder.setNeutralButton("显示全部", (dialog, which) -> showAllApps());

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(
                getResources().getColor(R.color.colorPrimary));
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(
                getResources().getColor(android.R.color.darker_gray));
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(
                getResources().getColor(R.color.colorAccent));
    }

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
            showToast("找到 " + filteredAppList.size() + " 个匹配的应用");
        }
    }

    private void showAllApps() {
        filteredAppList.clear();
        filteredAppList.addAll(appList);
        isSearchMode = false;
        appListAdapter.notifyDataSetChanged();
        tvEmpty.setVisibility(filteredAppList.isEmpty() ? View.VISIBLE : View.GONE);
        if (filteredAppList.isEmpty()) {
            tvEmpty.setText("暂无应用\n点击右下角按钮搜索应用");
        }
    }

    private void showEditAppDialog(AppItem appItem) {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
        builder.setTitle("编辑应用");

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_app1, null);
        builder.setView(dialogView);

        TextInputEditText etAppName = dialogView.findViewById(R.id.etAppName);
        TextInputEditText etAppDesc = dialogView.findViewById(R.id.etAppDesc);
        Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
        Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);

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

    private void updateApp(int appId, String appName, String appDesc) {
        if (!spManager.isUserDataValid()) {
            showToast("登录信息已过期");
            navigateToLogin();
            return;
        }

        int userId = spManager.getUserId();
        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("user_id", userId);
            jsonBody.put("app_id", appId);
            jsonBody.put("app_name", appName);
            jsonBody.put("app_desc", appDesc);
        } catch (JSONException e) {
            Log.e(TAG, "JSON创建失败", e);
            showToast("数据格式错误");
            return;
        }

        JsonObjectRequest updateRequest = new JsonObjectRequest(Request.Method.POST, TokenAuthHelper.appendTokenToUrl(AppConfig.UPDATE_APP_URL, requireContext()), jsonBody,
                response -> {
                    try {
                        if (response.getBoolean("success")) {
                            showToast("应用更新成功");
                        autoRefreshAfterDataChanged();
                            loadAppList();
                        } else {
                            showToast("更新失败: " + response.getString("message"));
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "parse update response failed", e);
                        showToast("响应解析失败");
                    }
                },
                error -> showToast("网络错误，更新失败"));
        requestQueue.add(updateRequest);
    }

    private void loadAppList() {
        if (!spManager.isUserDataValid()) {
            showToast("登录信息已过期，请重新登录");
            navigateToLogin();
            return;
        }

        int userId = spManager.getUserId();
        String url = TokenAuthHelper.appendTokenToUrl(AppConfig.GET_ALL_APPS_URL + "?user_id=" + userId, requireContext());

        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        StringRequest appsRequest = new StringRequest(Request.Method.GET, url,
                response -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    try {
                        JSONObject jsonResponse = new JSONObject(response);
                        if (jsonResponse.getBoolean("success")) {
                            JSONArray appsArray = jsonResponse.getJSONArray("data");
                            parseAppsData(appsArray);
                        } else {
                            showToast("加载失败: " + jsonResponse.getString("message"));
                            tvEmpty.setVisibility(View.VISIBLE);
                            tvEmpty.setText("加载失败");
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "parse apps list failed", e);
                        showToast("数据解析失败");
                        tvEmpty.setVisibility(View.VISIBLE);
                        tvEmpty.setText("数据解析失败");
                    }
                },
                error -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    showToast("网络连接错误");
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("网络连接失败\n请下拉刷新重试");
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
            filteredAppList.add(appItem);
        }
        appListAdapter.notifyDataSetChanged();
        tvEmpty.setVisibility(filteredAppList.isEmpty() ? View.VISIBLE : View.GONE);
        if (filteredAppList.isEmpty()) {
            tvEmpty.setText("暂无应用\n点击右下角按钮搜索应用");
        }
    }

    private void showAppDetail(AppItem appItem) {
        String detail = "应用名称: " + appItem.getAppName() + "\n" +
                "应用ID:    " + appItem.getAppId() + "\n" +
                "应用描述:    " + (appItem.getAppDesc().isEmpty() ? "暂无描述" : appItem.getAppDesc()) + "\n" +
                "创建时间:    " + appItem.getCreateTime();
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("应用详情")
                .setMessage(detail)
                .setPositiveButton("确定", null)
                .show();
    }

    private void showAppActions(AppItem appItem) {
        String[] actions = {"编辑应用", "删除应用", "查看详情"};
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("选择操作")
                .setItems(actions, (dialog, which) -> {
                    switch (which) {
                        case 0: showEditAppDialog(appItem); break;
                        case 1: showDeleteConfirmDialog(appItem); break;
                        case 2: showAppDetail(appItem); break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteConfirmDialog(AppItem appItem) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("确认删除？")
                .setMessage("确定要删除应用 \"" + appItem.getAppName() + "\" 吗？此操作不可恢复！")
                .setPositiveButton("删除", (dialog, which) -> deleteApp(appItem))
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
        Map<String, String> params = new HashMap<>();
        params.put("user_id", String.valueOf(userId));
        params.put("app_id", String.valueOf(appItem.getAppId()));

        StringRequest deleteRequest = new StringRequest(Request.Method.POST, TokenAuthHelper.appendTokenToUrl(AppConfig.DELETE_APP_URL, requireContext()),
                response -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(response);
                        if (jsonResponse.getBoolean("success")) {
                            showToast("删除成功");
                        autoRefreshAfterDataChanged();
                            loadAppList();
                        } else {
                            showToast("删除失败: " + jsonResponse.getString("message"));
                        }
                    } catch (JSONException e) {
                        showToast("响应解析失败");
                    }
                },
                error -> showToast("网络错误，删除失败")) {
            @Override
            protected Map<String, String> getParams() { return params; }
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
        Intent intent = new Intent(requireContext(), LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        requireContext().startActivity(intent);
        if (getActivity() != null) {
            getActivity().finish();
        }
    }

    private void showToast(String message) {
        if (getContext() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
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
