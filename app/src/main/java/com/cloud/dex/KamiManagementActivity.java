package com.cloud.dex;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.Calendar;
import android.graphics.Rect;

import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import java.util.Calendar;



import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class KamiManagementActivity extends AppCompatActivity {

    private static final String TAG = "KamiManagementActivity";

    private SharedPreferencesManager spManager;
    private RequestQueue requestQueue;

    private OptionSelector spinnerApps;
    private RecyclerView recyclerView;
    private PtrFrameLayout swipeRefreshLayout;
    private TextView tvEmpty;
    private FloatingActionButton fabSearch;
    private ProgressBar progressBar;

    private List<AppItem> appList = new ArrayList<>();
    private int selectedAppId = 0; // 0 表示所有应用
    private Map<Integer, List<KamiItem>> appKamiList = new HashMap<>();
    private List<KamiItem> kamiList = new ArrayList<>();
    private List<KamiItem> allKamiList = new ArrayList<>(); // 保存所有卡密数据
    private KamiAdapter kamiAdapter;
    private boolean isSearchMode = false;

    // URL unified: AppConfig.GET_APPS_URL
    // URL unified: AppConfig.GET_KAMI_LIST_URL
    // URL unified: AppConfig.GET_KAMI_DETAIL_URL
    // URL unified: AppConfig.UPDATE_KAMI_URL
    // URL unified: AppConfig.UNBIND_DEVICE_URL
    // URL unified: AppConfig.DELETE_KAMI_URL

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kami_management);

        // 设置ActionBar
        setupActionBar();

        spManager = new SharedPreferencesManager(this);
        requestQueue = Volley.newRequestQueue(this);

        initViews();
        setupRecyclerView();
        setupSwipeRefresh(); // 添加这行
        loadUserApps();
    }



    private void setupActionBar() {
        try {
            if (getSupportActionBar() != null) {
                // 设置背景为纯白色
                getSupportActionBar().setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.card_bg)));

                // 创建标题
                SpannableString title = new SpannableString("卡密管理");
                title.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.text_black)), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                getSupportActionBar().setTitle(title);
                getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_arrow_back);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置ActionBar失败", e);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }





    private void setupSwipeRefresh() {
        // 统一颜色
        swipeRefreshLayout.setColorSchemeColors(
                Color.parseColor("#2196F3"),
                Color.parseColor("#4CAF50"),
                Color.parseColor("#FF9800")
        );

        swipeRefreshLayout.setOnRefreshListener(new PtrFrameLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                loadKamiList();
                swipeRefreshLayout.setRefreshing(false);
            }
        });
    }



    private void initViews() {
        spinnerApps = findViewById(R.id.spinnerApps);
        recyclerView = findViewById(R.id.recyclerView);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        tvEmpty = findViewById(R.id.tvEmpty);
        fabSearch = findViewById(R.id.fabSearch);
        progressBar = findViewById(R.id.progressBar);

        // 设置搜索按钮点击事件
        fabSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSearchDialog();
            }
        });
    }
// 在KamiManagementActivity类中添加以下方法

    private void setupRecyclerView() {
        kamiAdapter = new KamiAdapter(kamiList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(kamiAdapter);

        // 设置卡片间垂直间距（8dp，与应用列表一致）
        recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                super.getItemOffsets(outRect, view, parent, state);

                float density = getResources().getDisplayMetrics().density;
                int verticalSpacing = (int) (8 * density);   // 8dp 总间距
                outRect.top = verticalSpacing / 2;            // 上方 4dp
                outRect.bottom = verticalSpacing / 2;         // 下方 4dp
            }
        });

        // 滚动监听（保持原有逻辑）
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    fabSearch.show();
                } else {
                    fabSearch.hide();
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
            }
        });
    }

    private void loadUserApps() {
        int userId = spManager.getUserId();
        if (userId <= 0) {
            showToast("用户信息无效");
            finish();
            return;
        }

        String url = TokenAuthHelper.appendTokenToUrl(AppConfig.GET_APPS_URL + "?user_id=" + userId, KamiManagementActivity.this);
        Log.d(TAG, "请求应用列表URL: " + url);

        StringRequest appsRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.d(TAG, "应用列表响应: " + InputValidator.sanitizeForLog(response));
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            boolean success = jsonResponse.getBoolean("success");

                            if (success) {
                                JSONArray appsArray = jsonResponse.getJSONArray("data");
                                setupAppsSpinner(appsArray);
                            } else {
                                String message = jsonResponse.getString("message");
                                showToast("加载应用列表失败: " + message);
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析应用列表响应失败", e);
                            showToast("解析应用列表失败");
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "获取应用列表网络错误", error);
                        showToast("加载应用列表失败");
                    }
                });

        requestQueue.add(appsRequest);
    }

    private void setupAppsSpinner(JSONArray appsArray) {
        try {
            appList.clear();
            for (int i = 0; i < appsArray.length(); i++) {
                JSONObject appObject = appsArray.getJSONObject(i);
                int appId = appObject.getInt("app_id");
                String appName = appObject.getString("app_name");
                String appDesc = appObject.getString("app_desc");
                appList.add(new AppItem(appId, appName, appDesc));
            }

            if (appList.isEmpty()) {
                showToast("暂无应用");
                return;
            }

            // 构建下拉列表显示项：第一项为"所有应用"
            List<String> spinnerItems = new ArrayList<>();
            spinnerItems.add("全部卡密");
            for (AppItem app : appList) {
                spinnerItems.add(app.getAppName() + " - (" + app.getAppId() + ")");
            }

            spinnerApps.setSheetTitle("选择应用");
            spinnerApps.setOptionList(spinnerItems);

            // 设置选择监听器
            spinnerApps.setOnItemSelectedListener(position -> {
                if (position == 0) {
                    selectedAppId = 0; // 所有应用
                } else {
                    selectedAppId = appList.get(position - 1).getAppId();
                }
                loadKamiList();
            });

            loadKamiList();

        } catch (JSONException e) {
            Log.e(TAG, "设置应用下拉框失败", e);
            showToast("设置应用列表失败");
        }
    }

    private void loadKamiList() {
        int userId = spManager.getUserId();
        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        swipeRefreshLayout.setRefreshing(false);

        if (selectedAppId == 0) {
            // 所有应用：遍历每个应用分别请求，合并结果
            loadAllAppsKamiList(userId);
        } else {
            loadSingleAppKamiList(userId, selectedAppId);
        }
    }

    private void loadAllAppsKamiList(int userId) {
        appKamiList.clear();
        allKamiList.clear();
        kamiList.clear();
        final int[] completedCount = {0};
        final int totalApps = appList.size();
        final boolean[] hasError = {false};

        if (totalApps == 0) {
            progressBar.setVisibility(View.GONE);
            kamiAdapter.notifyDataSetChanged();
            updateEmptyView();
            return;
        }

        for (AppItem app : appList) {
            String url = AppConfig.GET_KAMI_LIST_URL + "?user_id=" + userId + "&app_id=" + app.getAppId();
            StringRequest request = new StringRequest(Request.Method.GET, url,
                    response -> {
                        completedCount[0]++;
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            if (jsonResponse.getBoolean("success")) {
                                JSONArray kamiArray = jsonResponse.getJSONArray("data");
                                List<KamiItem> appKamis = new ArrayList<>();
                                for (int i = 0; i < kamiArray.length(); i++) {
                                    JSONObject obj = kamiArray.getJSONObject(i);
                                    KamiItem kami = new KamiItem();
                                    kami.setId(obj.getInt("id"));
                                    kami.setKamiCode(obj.getString("kami_code"));
                                    kami.setKamiType(obj.getString("kami_type"));
                                    kami.setStatus(obj.getInt("status"));
                                    kami.setCreateTime(obj.getString("create_time"));
                                    kami.setExpireTime(obj.getString("expire_time"));
                                    kami.setDeviceId(obj.optString("device_id", ""));
                                    kami.setRemainingText(calculateRemainingText(kami.getExpireTime(), kami.getStatus()));
                                    appKamis.add(kami);
                                }
                                synchronized (appKamiList) {
                                    appKamiList.put(app.getAppId(), appKamis);
                                    allKamiList.addAll(appKamis);
                                }
                            } else {
                                hasError[0] = true;
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析卡密列表失败: " + app.getAppId(), e);
                        }

                        if (completedCount[0] >= totalApps) {
                            runOnUiThread(() -> {
                                progressBar.setVisibility(View.GONE);
                                if (hasError[0]) {
                                    showToast("部分应用加载失败");
                                }
                                kamiList.clear();
                                kamiList.addAll(allKamiList);
                                kamiAdapter.notifyDataSetChanged();
                                updateEmptyView();
                                Log.d(TAG, "所有应用卡密加载完成，共 " + allKamiList.size() + " 条");
                            });
                        }
                    },
                    error -> {
                        completedCount[0]++;
                        hasError[0] = true;
                        if (completedCount[0] >= totalApps) {
                            runOnUiThread(() -> {
                                progressBar.setVisibility(View.GONE);
                                if (allKamiList.isEmpty()) {
                                    showToast("加载卡密列表失败");
                                } else {
                                    showToast("部分应用加载失败");
                                }
                                kamiList.clear();
                                kamiList.addAll(allKamiList);
                                kamiAdapter.notifyDataSetChanged();
                                updateEmptyView();
                            });
                        }
                    });
            requestQueue.add(request);
        }
    }

    private void loadSingleAppKamiList(int userId, int appId) {
        String url = AppConfig.GET_KAMI_LIST_URL + "?user_id=" + userId + "&app_id=" + appId;
        Log.d(TAG, "请求卡密列表URL: " + url);

        // 显示加载状态 - 只显示进度条，不显示刷新动画
        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);

        // 确保刷新动画被停止
        swipeRefreshLayout.setRefreshing(false);

        StringRequest kamiRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        // 停止所有加载指示器
                        swipeRefreshLayout.setRefreshing(false);
                        progressBar.setVisibility(View.GONE);
                        Log.d(TAG, "卡密列表响应: " + InputValidator.sanitizeForLog(response));
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            boolean success = jsonResponse.getBoolean("success");
                            String message = jsonResponse.getString("message");

                            if (success) {
                                JSONArray kamiArray = jsonResponse.getJSONArray("data");
                                Log.d(TAG, "成功获取卡密列表，数量: " + kamiArray.length());
                                updateKamiList(kamiArray);
                            } else {
                                Log.e(TAG, "加载卡密列表失败: " + message);
                                showToast("加载卡密列表失败: " + message);
                                kamiList.clear();
                                allKamiList.clear();
                                kamiAdapter.notifyDataSetChanged();
                                updateEmptyView();
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析卡密列表响应失败", e);
                            showToast("解析卡密列表失败");
                            kamiList.clear();
                            allKamiList.clear();
                            kamiAdapter.notifyDataSetChanged();
                            updateEmptyView();
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        // 停止所有加载指示器
                        swipeRefreshLayout.setRefreshing(false);
                        progressBar.setVisibility(View.GONE);
                        Log.e(TAG, "获取卡密列表网络错误", error);

                        String errorMessage = "网络连接错误";
                        if (error.networkResponse != null) {
                            int statusCode = error.networkResponse.statusCode;
                            String responseData = new String(error.networkResponse.data);
                            errorMessage = "网络错误，状态码: " + statusCode + ", 响应: " + responseData;
                            Log.e(TAG, "详细错误信息: " + errorMessage);
                        } else if (error.getMessage() != null) {
                            errorMessage = error.getMessage();
                        }

                        showToast("加载卡密列表失败: " + errorMessage);
                        kamiList.clear();
                        allKamiList.clear();
                        kamiAdapter.notifyDataSetChanged();
                        updateEmptyView();
                    }
                });

        requestQueue.add(kamiRequest);
    }

    private void updateKamiList(JSONArray kamiArray) {
        try {
            allKamiList.clear(); // 清空所有数据
            kamiList.clear();

            for (int i = 0; i < kamiArray.length(); i++) {
                JSONObject kamiObject = kamiArray.getJSONObject(i);
                KamiItem kami = new KamiItem();
                kami.setId(kamiObject.getInt("id"));
                kami.setKamiCode(kamiObject.getString("kami_code"));
                kami.setKamiType(kamiObject.getString("kami_type"));
                kami.setStatus(kamiObject.getInt("status"));
                kami.setCreateTime(kamiObject.getString("create_time"));
                kami.setExpireTime(kamiObject.getString("expire_time"));
                kami.setDeviceId(kamiObject.optString("device_id", ""));

                allKamiList.add(kami); // 添加到所有数据列表
            }

            // 计算所有卡密的剩余时间（新增）
            for (KamiItem kami : allKamiList) {
                kami.setRemainingText(calculateRemainingText(kami.getExpireTime(), kami.getStatus()));
            }

            // 如果不是搜索模式，显示所有数据
            if (!isSearchMode) {
                kamiList.addAll(allKamiList);
            }

            kamiAdapter.notifyDataSetChanged();
            updateEmptyView();

        } catch (JSONException e) {
            Log.e(TAG, "更新卡密列表失败", e);
            showToast("更新卡密列表失败");
        }
    }

    /**
     * 计算剩余时间文本（新增方法）
     */
    private String calculateRemainingText(String expireTime, int status) {
        // 未使用的卡密
        if (status == 0) {
            return "未使用";
        }
        // 已到期或状态为2，但可能是永久卡
        if (status == 2 || (TextUtils.isEmpty(expireTime) && status == 1)) {
            if (status == 1 && TextUtils.isEmpty(expireTime)) {
                return "永久有效";
            }
            return "已到期";
        }

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date expireDate = sdf.parse(expireTime);
            long diff = expireDate.getTime() - System.currentTimeMillis();

            if (diff <= 0) {
                return "已到期";
            }

            long days = diff / (1000 * 60 * 60 * 24);
            long hours = (diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60);
            long minutes = (diff % (1000 * 60 * 60)) / (1000 * 60);

            StringBuilder sb = new StringBuilder();
            if (days > 0) {
                sb.append(days).append("天");
                if (hours > 0) sb.append(hours).append("小时");
            } else if (hours > 0) {
                sb.append(hours).append("小时");
                if (minutes > 0) sb.append(minutes).append("分钟");
            } else {
                sb.append(minutes).append("分钟");
            }
            return sb.toString();
        } catch (Exception e) {
            return "未知";
        }
    }

    private void updateEmptyView() {
        if (kamiList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            if (isSearchMode) {
                tvEmpty.setText("未找到匹配的卡密");
            } else {
                tvEmpty.setText("暂无卡密\n点击右下角按钮搜索卡密");
            }
            recyclerView.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 显示搜索对话框
     */
    private void showSearchDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("搜索卡密");

        // 创建输入布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);

        TextInputEditText editText = new TextInputEditText(this);
        editText.setHint("请输入卡密代码或设备ID");
        editText.setSingleLine(true);
        editText.setBackgroundResource(R.drawable.edit_text_background);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, 20);
        editText.setLayoutParams(params);

        layout.addView(editText);

        builder.setView(layout);

        builder.setPositiveButton("搜索", (dialog, which) -> {
            String searchText = editText.getText().toString().trim();
            if (!searchText.isEmpty()) {
                searchKami(searchText);
            } else {
                showToast("请输入搜索内容");
            }
        });

        builder.setNegativeButton("取消", (dialog, which) -> {
            dialog.dismiss();
        });

        builder.setNeutralButton("显示全部", (dialog, which) -> {
            showAllKami();
        });

        AlertDialog dialog = builder.create();
        dialog.show();

        // 设置按钮颜色
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getResources().getColor(R.color.colorPrimary));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getResources().getColor(android.R.color.darker_gray));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(getResources().getColor(R.color.colorAccent));
    }

    /**
     * 搜索卡密
     */
    private void searchKami(String searchText) {
        List<KamiItem> filteredList = new ArrayList<>();
        isSearchMode = true;

        for (KamiItem kami : allKamiList) {
            if (kami.getKamiCode().toLowerCase().contains(searchText.toLowerCase()) ||
                    (kami.getDeviceId() != null && kami.getDeviceId().toLowerCase().contains(searchText.toLowerCase()))) {
                filteredList.add(kami);
            }
        }

        kamiList.clear();
        kamiList.addAll(filteredList);
        kamiAdapter.notifyDataSetChanged();

        if (kamiList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("未找到匹配的卡密\n搜索关键词: " + searchText);
        } else {
            tvEmpty.setVisibility(View.GONE);
            showToast("找到 " + kamiList.size() + " 个匹配的卡密");
        }
    }

    /**
     * 显示所有卡密
     */
    private void showAllKami() {
        kamiList.clear();
        kamiList.addAll(allKamiList);
        isSearchMode = false;
        kamiAdapter.notifyDataSetChanged();

        if (kamiList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("暂无卡密\n点击右下角按钮搜索卡密");
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    private void showKamiDetailDialog(KamiItem kami) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("卡密详情");

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_kami_detail, null);
        builder.setView(dialogView);

        TextView tvKamiCode = dialogView.findViewById(R.id.tvKamiCode);
        TextView tvExpireTime = dialogView.findViewById(R.id.tvExpireTime);
        TextView tvStatus = dialogView.findViewById(R.id.tvStatus);
        TextView tvRemainingDays = dialogView.findViewById(R.id.tvRemainingDays);
        TextView tvDeviceId = dialogView.findViewById(R.id.tvDeviceId);
        Button btnClose = dialogView.findViewById(R.id.btn_close);

        tvKamiCode.setText("卡密代码: " + kami.getKamiCode());
        tvExpireTime.setText("到期时间: " + kami.getExpireTime());

        String statusText = "未知";
        switch (kami.getStatus()) {
            case 0: statusText = "未使用"; break;
            case 1: statusText = "已使用"; break;
            case 2: statusText = "已到期"; break;
        }
        tvStatus.setText("卡密状态: " + statusText);

        // 剩余时间改为显示精确文本
        tvRemainingDays.setText("剩余时间: " + (kami.getRemainingText() != null ? kami.getRemainingText() : "未知"));

        if (!TextUtils.isEmpty(kami.getDeviceId())) {
            tvDeviceId.setText("设备ID: " + kami.getDeviceId());
            tvDeviceId.setVisibility(View.VISIBLE);
        } else {
            tvDeviceId.setVisibility(View.GONE);
        }

        AlertDialog dialog = builder.create();
        dialog.show();

        btnClose.setOnClickListener(v -> dialog.dismiss());
    }

    private int calculateRemainingDays(String expireTime) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date expireDate = sdf.parse(expireTime);
            Date currentDate = new Date();

            long diff = expireDate.getTime() - currentDate.getTime();
            return (int) (diff / (1000 * 60 * 60 * 24));
        } catch (Exception e) {
            return -1;
        }
    }

    private void showOperationDialog(final KamiItem kami) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择操作");

        String[] operations = {"编辑卡密", "解绑设备", "删除卡密"};
        builder.setItems(operations, (dialog, which) -> {
            switch (which) {
                case 0: // 编辑卡密
                    // 检查设备ID是否为空
                    if (TextUtils.isEmpty(kami.getDeviceId())) {
                        showToast("只有已绑定设备的卡密才能修改到期时间");
                    } else {
                        showEditKamiDialog(kami);
                    }
                    break;
                case 1: // 解绑设备
                    showUnbindConfirmDialog(kami);
                    break;
                case 2: // 删除卡密
                    showDeleteConfirmDialog(kami);
                    break;
            }
        });

        builder.show();
    }

    private void showEditKamiDialog(final KamiItem kami) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("编辑卡密");

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_kami, null);
        builder.setView(dialogView);

        final TextInputEditText etExpireTime = dialogView.findViewById(R.id.etExpireTime);
        Button btnAdd1Day = dialogView.findViewById(R.id.btnAdd1Day);
        Button btnAdd1Week = dialogView.findViewById(R.id.btnAdd1Week);
        Button btnAdd1Month = dialogView.findViewById(R.id.btnAdd1Month);
        Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
        Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);

        // 设置初始时间
        String currentTime = kami.getExpireTime();
        if (TextUtils.isEmpty(currentTime) || "未设置".equals(currentTime)) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            currentTime = sdf.format(new Date());
        }
        etExpireTime.setText(currentTime);

        // 设置快捷时间按钮点击事件
        btnAdd1Day.setOnClickListener(v -> addTimeToExpireTime(etExpireTime, 1, Calendar.DAY_OF_YEAR));
        btnAdd1Week.setOnClickListener(v -> addTimeToExpireTime(etExpireTime, 1, Calendar.WEEK_OF_YEAR));
        btnAdd1Month.setOnClickListener(v -> addTimeToExpireTime(etExpireTime, 1, Calendar.MONTH));

        AlertDialog dialog = builder.create();
        dialog.show();

        btnConfirm.setOnClickListener(v -> {
            String newExpireTime = etExpireTime.getText().toString().trim();
            if (TextUtils.isEmpty(newExpireTime)) {
                showToast("到期时间不能为空");
                return;
            }
            dialog.dismiss();
            updateKamiExpireTime(kami.getId(), newExpireTime);
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
    }

    /**
     * 为到期时间添加指定时间单位
     */
    private void addTimeToExpireTime(TextInputEditText etExpireTime, int amount, int timeUnit) {
        try {
            String currentTime = etExpireTime.getText().toString().trim();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

            Date date;
            if (TextUtils.isEmpty(currentTime) || "未设置".equals(currentTime)) {
                date = new Date(); // 使用当前时间
            } else {
                date = sdf.parse(currentTime);
            }

            Calendar calendar = Calendar.getInstance();
            calendar.setTime(date);
            calendar.add(timeUnit, amount);

            String newTime = sdf.format(calendar.getTime());
            etExpireTime.setText(newTime);

        } catch (Exception e) {
            Log.e(TAG, "添加时间失败", e);
            showToast("时间格式错误，请手动输入");

            // 如果解析失败，设置一个默认的未来时间
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Calendar calendar = Calendar.getInstance();
            calendar.add(timeUnit, amount);
            String defaultTime = sdf.format(calendar.getTime());
            etExpireTime.setText(defaultTime);
        }
    }

    private void updateKamiExpireTime(int kamiId, String expireTime) {
        int userId = spManager.getUserId();

        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("user_id", userId);
            jsonBody.put("kami_id", kamiId);
            jsonBody.put("expire_time", expireTime);
        } catch (JSONException e) {
            Log.e(TAG, "创建更新卡密JSON请求体失败", e);
            showToast("数据格式错误");
            return;
        }

        JsonObjectRequest updateRequest = new JsonObjectRequest(Request.Method.POST, AppConfig.UPDATE_KAMI_URL, jsonBody,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        Log.d(TAG, "更新卡密响应: " + InputValidator.sanitizeForLog(response.toString()));
                        try {
                            boolean success = response.getBoolean("success");
                            String message = response.getString("message");

                            if (success) {
                                showToast("卡密更新成功");
                                autoRefreshAfterDataChanged();
                                loadKamiList();
                            } else {
                                showToast("更新失败: " + message);
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析更新卡密响应失败", e);
                            showToast("响应解析失败");
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "更新卡密网络错误", error);
                        showToast("更新失败");
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

    private void showUnbindConfirmDialog(final KamiItem kami) {
        new AlertDialog.Builder(this)
                .setTitle("确认解绑")
                .setMessage("确定要解绑卡密 " + kami.getKamiCode() + " 绑定的设备吗？\n设备ID: " + kami.getDeviceId())
                .setPositiveButton("确认解绑", (dialog, which) -> unbindDevice(kami))
                .setNegativeButton("取消", null)
                .show();
    }

    private void unbindDevice(KamiItem kami) {
        if (kami.getStatus() != 1) {
            showToast("只有已使用的卡密才能解绑设备");
            return;
        }

        int userId = spManager.getUserId();

        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("user_id", userId);
            jsonBody.put("kami_id", kami.getId());
        } catch (JSONException e) {
            Log.e(TAG, "创建解绑设备JSON请求体失败", e);
            showToast("数据格式错误");
            return;
        }

        JsonObjectRequest unbindRequest = new JsonObjectRequest(Request.Method.POST, AppConfig.UNBIND_DEVICE_URL, jsonBody,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        Log.d(TAG, "解绑设备响应: " + InputValidator.sanitizeForLog(response.toString()));
                        try {
                            boolean success = response.getBoolean("success");
                            String message = response.getString("message");

                            if (success) {
                                showToast("设备解绑成功");
                                autoRefreshAfterDataChanged();
                                loadKamiList();
                            } else {
                                showToast("解绑失败: " + message);
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析解绑设备响应失败", e);
                            showToast("响应解析失败");
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "解绑设备网络错误", error);
                        showToast("解绑失败");
                    }
                }) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                return headers;
            }
        };

        requestQueue.add(unbindRequest);
    }

    private void showDeleteConfirmDialog(final KamiItem kami) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("确认删除");
        builder.setMessage("确定要删除卡密 " + kami.getKamiCode() + " 吗？此操作不可恢复！");

        builder.setPositiveButton("删除", (dialog, which) -> {
            deleteKami(kami);
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void deleteKami(KamiItem kami) {
        int userId = spManager.getUserId();

        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("user_id", userId);
            jsonBody.put("kami_id", kami.getId());
        } catch (JSONException e) {
            Log.e(TAG, "创建删除卡密JSON请求体失败", e);
            showToast("数据格式错误");
            return;
        }

        JsonObjectRequest deleteRequest = new JsonObjectRequest(Request.Method.POST, AppConfig.DELETE_KAMI_URL, jsonBody,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        Log.d(TAG, "删除卡密响应: " + InputValidator.sanitizeForLog(response.toString()));
                        try {
                            boolean success = response.getBoolean("success");
                            String message = response.getString("message");

                            if (success) {
                                showToast("卡密删除成功");
                                loadKamiList();
                            } else {
                                showToast("删除失败: " + message);
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析删除卡密响应失败", e);
                            showToast("响应解析失败");
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "删除卡密网络错误", error);
                        showToast("删除失败");
                    }
                }) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                return headers;
            }
        };

        requestQueue.add(deleteRequest);
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("卡密代码", text);
        clipboard.setPrimaryClip(clip);
        showToast("卡密代码已复制");
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // 卡密数据类（新增了 remainingText 字段）
    public static class KamiItem {
        private int id;
        private String kamiCode;
        private String kamiType;
        private int status;
        private String createTime;
        private String expireTime;
        private String deviceId;
        private String remainingText;   // 新增

        // getters and setters
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getKamiCode() { return kamiCode; }
        public void setKamiCode(String kamiCode) { this.kamiCode = kamiCode; }
        public String getKamiType() { return kamiType; }
        public void setKamiType(String kamiType) { this.kamiType = kamiType; }
        public int getStatus() { return status; }
        public void setStatus(int status) { this.status = status; }
        public String getCreateTime() { return createTime; }
        public void setCreateTime(String createTime) { this.createTime = createTime; }
        public String getExpireTime() { return expireTime; }
        public void setExpireTime(String expireTime) { this.expireTime = expireTime; }
        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
        public String getRemainingText() { return remainingText; }
        public void setRemainingText(String remainingText) { this.remainingText = remainingText; }
    }

    // 卡密适配器（修改了 bind 方法中到期时间的显示，追加剩余时间）
    private class KamiAdapter extends RecyclerView.Adapter<KamiAdapter.ViewHolder> {

        private List<KamiItem> kamiList;

        public KamiAdapter(List<KamiItem> kamiList) {
            this.kamiList = kamiList;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_kami, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            KamiItem kami = kamiList.get(position);
            holder.bind(kami);
        }

        @Override
        public int getItemCount() {
            return kamiList.size();
        }

        // 当 ViewHolder 被回收时，停止定时更新
        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            super.onViewRecycled(holder);
            holder.stopCountDown();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {
            private CardView cardKami;
            private TextView tvKamiCode;
            private TextView tvKamiStatus;
            private TextView tvKamiType;
            private TextView tvCreateTime;
            private TextView tvDeviceId;

            private Handler countDownHandler;
            private Runnable countDownRunnable;
            private KamiItem currentKami;

            public ViewHolder(View itemView) {
                super(itemView);
                cardKami = itemView.findViewById(R.id.cardKami);
                tvKamiCode = itemView.findViewById(R.id.tvKamiCode);
                tvKamiStatus = itemView.findViewById(R.id.tvKamiStatus);
                tvKamiType = itemView.findViewById(R.id.tvKamiType);
                tvCreateTime = itemView.findViewById(R.id.tvCreateTime);
                tvDeviceId = itemView.findViewById(R.id.tvDeviceId);

                countDownHandler = new Handler(Looper.getMainLooper());

                tvKamiCode.setOnClickListener(v -> {
                    KamiItem kami = kamiList.get(getAdapterPosition());
                    copyToClipboard(kami.getKamiCode());
                });

                itemView.setOnClickListener(v -> {
                    KamiItem kami = kamiList.get(getAdapterPosition());
                    showOperationDialog(kami);
                });

                itemView.setOnLongClickListener(v -> {
                    KamiItem kami = kamiList.get(getAdapterPosition());
                    showKamiDetailDialog(kami);
                    return true;
                });
            }

            public void stopCountDown() {
                if (countDownRunnable != null) {
                    countDownHandler.removeCallbacks(countDownRunnable);
                    countDownRunnable = null;
                }
            }

            public void bind(KamiItem kami) {
                stopCountDown();
                this.currentKami = kami;

                // 卡密代码
                tvKamiCode.setText("卡密：" + kami.getKamiCode());
                // 卡密类型
                String typeText = "卡密类型：" + getKamiTypeText(kami.getKamiType());
                tvKamiType.setText(typeText);
                // 状态标签
                setStatusUI(kami.getStatus());

                // 设备ID
                if (!TextUtils.isEmpty(kami.getDeviceId())) {
                    tvDeviceId.setText("设备ID：" + kami.getDeviceId());
                    tvDeviceId.setVisibility(View.VISIBLE);
                } else {
                    tvDeviceId.setVisibility(View.GONE);
                }

                String expireStr = TextUtils.isEmpty(kami.getExpireTime()) || "未设置".equals(kami.getExpireTime())
                        ? "未设置" : kami.getExpireTime();
                boolean started = false;

                // 未到期且状态不是2，启动倒计时
                if (kami.getStatus() != 2 && !TextUtils.isEmpty(kami.getExpireTime()) && !"未设置".equals(kami.getExpireTime())) {
                    try {
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                        Date expireDate = sdf.parse(kami.getExpireTime());
                        if (expireDate.getTime() > System.currentTimeMillis()) {
                            startCountDown(kami);
                            started = true;
                            // 立即刷新一次剩余时间
                            String remain = calculateRemainingTextDynamic(kami.getExpireTime());
                            updateExpireDisplayWithRemain(kami, remain);
                        } else {
                            // 已过期但状态未更新
                            checkAndUpdateExpiredStatus(kami);
                        }
                    } catch (Exception e) {
                        // 忽略解析异常
                    }
                }

                if (!started) {
                    String remain = kami.getRemainingText();
                    String display = "到期时间：" + expireStr;
                    if (!TextUtils.isEmpty(remain)) {
                        display += " (" + remain + ")";
                    }
                    tvCreateTime.setText(display);
                }
            }

            /** 统一设置状态标签样式 */
            private void setStatusUI(int status) {
                String statusText = "";
                int statusColor = android.R.color.darker_gray;
                int backgroundResId = R.drawable.status_unused_background;
                switch (status) {
                    case 0:
                        statusText = "未使用";
                        statusColor = R.color.colorPrimary;
                        backgroundResId = R.drawable.status_unused_background;
                        break;
                    case 1:
                        statusText = "已使用";
                        statusColor = R.color.colorAccent;
                        backgroundResId = R.drawable.status_used_background;
                        break;
                    case 2:
                        statusText = "已到期";
                        statusColor = R.color.colorRed;
                        backgroundResId = R.drawable.status_expired_background;
                        break;
                }
                tvKamiStatus.setText(statusText);
                tvKamiStatus.setTextColor(getResources().getColor(statusColor));
                tvKamiStatus.setBackgroundResource(backgroundResId);
            }

            /** 检查并更新过期状态（当时间已过但状态仍为0/1时） */
            private void checkAndUpdateExpiredStatus(KamiItem kami) {
                if (kami.getStatus() == 2) return;
                String expireTime = kami.getExpireTime();
                if (TextUtils.isEmpty(expireTime) || "未设置".equals(expireTime)) return;
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    Date expireDate = sdf.parse(expireTime);
                    if (expireDate.getTime() <= System.currentTimeMillis()) {
                        kami.setStatus(2);
                        setStatusUI(2);
                        tvCreateTime.setText("到期时间：" + expireTime + " (已到期)");
                        stopCountDown();
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            /** 启动每秒倒计时更新 */
            private void startCountDown(final KamiItem kami) {
                countDownRunnable = new Runnable() {
                    @Override
                    public void run() {
                        if (kami.getStatus() == 2) {
                            stopCountDown();
                            return;
                        }
                        String remain = calculateRemainingTextDynamic(kami.getExpireTime());
                        if ("已到期".equals(remain)) {
                            kami.setStatus(2);
                            setStatusUI(2);
                            updateExpireDisplayWithRemain(kami, remain);
                            stopCountDown();
                        } else {
                            updateExpireDisplayWithRemain(kami, remain);
                            countDownHandler.postDelayed(this, 1000);
                        }
                    }
                };
                countDownHandler.post(countDownRunnable);
            }

            /** 更新到期时间显示，附加剩余时间文本 */
            private void updateExpireDisplayWithRemain(KamiItem kami, String remain) {
                String expireStr = TextUtils.isEmpty(kami.getExpireTime()) || "未设置".equals(kami.getExpireTime())
                        ? "未设置" : kami.getExpireTime();
                String display = "到期时间：" + expireStr;
                if (!TextUtils.isEmpty(remain)) {
                    display += " (" + remain + ")";
                }
                tvCreateTime.setText(display);
            }

            /** 动态计算剩余时间文本（用于每秒刷新） */
            private String calculateRemainingTextDynamic(String expireTime) {
                if (TextUtils.isEmpty(expireTime)) return "永久有效";
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    Date expireDate = sdf.parse(expireTime);
                    long diff = expireDate.getTime() - System.currentTimeMillis();
                    if (diff <= 0) {
                        return "已到期";
                    }
                    long days = diff / (1000 * 60 * 60 * 24);
                    long hours = (diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60);
                    long minutes = (diff % (1000 * 60 * 60)) / (1000 * 60);
                    long seconds = (diff % (1000 * 60)) / 1000;

                    StringBuilder sb = new StringBuilder();
                    if (days > 0) {
                        sb.append(days).append("天");
                        if (hours > 0) sb.append(hours).append("小时");
                    } else if (hours > 0) {
                        sb.append(hours).append("小时");
                        if (minutes > 0) sb.append(minutes).append("分钟");
                    } else if (minutes > 0) {
                        sb.append(minutes).append("分钟");
                        if (seconds > 0) sb.append(seconds).append("秒");
                    } else {
                        sb.append(seconds).append("秒");
                    }
                    return sb.toString();
                } catch (Exception e) {
                    return "未知";
                }
            }

            /** 获取卡密类型中文描述 */
            private String getKamiTypeText(String type) {
                switch (type) {
                    case "tyk": return "体验卡(30分钟)";
                    case "hour": return "小时卡(1小时)";
                    case "day": return "天卡(1天)";
                    case "week": return "周卡(1周)";
                    case "month": return "月卡(1月)";
                    case "year": return "年卡(1年)";
                    case "permanent": return "永久卡";
                    case "custom": return "自定义";
                    default: return type;
                }
            }
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
                        loadKamiList();
                    }
                }
            }, 220L);
        } catch (Exception e) {
            android.util.Log.e(TAG, "autoRefreshAfterDataChanged failed", e);
        }
    }
}