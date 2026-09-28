package com.cloud.dex;

import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件更新页面
 * 1. 应用下拉列表框（复刻卡密管理界面的「选择应用」下拉框）
 * 2. 更新标题 / 更新链接 / 版本号 / 更新内容 输入框
 *    - 版本号仅允许输入纯数字和「.」
 * 3. 开启插件更新 / 是否强制更新 两个滑动开关（默认关闭，灰色）
 *
 * 新增逻辑：
 * - 开启「插件更新」时要求所有编辑框内容不能为空
 * - 开启「是否强制更新」时要求必须先开启「插件更新」
 * - 编辑框内容有改动时自动保存
 * - 区分所有 appid（所有应用）
 */
public class PluginUpdateActivity extends AppCompatActivity {

    private static final String TAG = "PluginUpdateActivity";

    private SharedPreferencesManager spManager;
    private RequestQueue requestQueue;

    private OptionSelector spinnerApps;
    private IosLikeSwitch switchPluginUpdate;
    private IosLikeSwitch switchForceUpdate;
    private TextInputEditText etUpdateTitle;
    private TextInputEditText etUpdateUrl;
    private TextInputEditText etVersion;
    private TextInputEditText etUpdateContent;

    private final List<AppItem> appList = new ArrayList<>();
    private int selectedAppId = 0; // 0 表示所有应用

    // 标记是否正在从服务器加载数据（避免加载时触发自动保存）
    private boolean isLoadingData = false;
    // 标记用户是否修改了内容（有改动才自动保存）
    private boolean hasUserChanges = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_plugin_update);

        spManager = new SharedPreferencesManager(this);
        requestQueue = Volley.newRequestQueue(this);

        setupActionBar();
        initViews();
        setupTextWatchers();
        loadUserApps();
    }

    /**
     * 与 KamiManagementActivity 保持一致：
     * 纯白背景 + 黑色标题，不显示返回按钮
     */
    private void setupActionBar() {
        try {
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                // 不显示返回按钮（保持本页原有特性）
                actionBar.setDisplayHomeAsUpEnabled(false);
                actionBar.setDisplayShowHomeEnabled(false);

                // 设置背景为纯白色（与卡密管理界面保持一致）
                actionBar.setBackgroundDrawable(
                        new ColorDrawable(getResources().getColor(R.color.card_bg)));

                // 创建标题
                SpannableString title = new SpannableString("插件更新");
                title.setSpan(
                        new ForegroundColorSpan(getResources().getColor(R.color.text_black)),
                        0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                actionBar.setTitle(title);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置ActionBar失败", e);
        }
    }

    private void initViews() {
        spinnerApps = findViewById(R.id.spinnerApps);
        switchPluginUpdate = findViewById(R.id.switchPluginUpdate);
        switchForceUpdate = findViewById(R.id.switchForceUpdate);
        etUpdateTitle = findViewById(R.id.etUpdateTitle);
        etUpdateUrl = findViewById(R.id.etUpdateUrl);
        etVersion = findViewById(R.id.etVersion);
        etUpdateContent = findViewById(R.id.etUpdateContent);

        // 版本号：软键盘层由 android:digits 限制，这里再拦一道
        // 防止粘贴、物理键盘等途径输入非法字符（只允许 0-9 和 .）
        etVersion.setFilters(new InputFilter[]{
                new InputFilter() {
                    @Override
                    public CharSequence filter(CharSequence source, int start, int end,
                                               Spanned dest, int dstart, int dend) {
                        for (int i = start; i < end; i++) {
                            char c = source.charAt(i);
                            if (c != '.' && (c < '0' || c > '9')) {
                                return "";
                            }
                        }
                        return null; // 全部合法，保留
                    }
                }
        });

        // 两个开关默认关闭（灰色）
        switchPluginUpdate.setChecked(false);
        switchForceUpdate.setChecked(false);

        // ── "开启插件更新" 开关逻辑 ──
        switchPluginUpdate.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isLoadingData) return;

            if (isChecked) {
                // 开启时：检查所有编辑框是否为空
                if (areAllInputsValid()) {
                    // 自动保存
                    autoSave();
                    showToast("已开启插件更新");
                } else {
                    // 编辑框有空内容，不允许开启
                    switchPluginUpdate.setChecked(false);
                    showToast("开启插件更新时，所有编辑框内容不能为空");
                }
            } else {
                // 关闭插件更新时，同时关闭强制更新
                if (switchForceUpdate.isChecked()) {
                    switchForceUpdate.setChecked(false);
                }
                autoSave();
                showToast("已关闭插件更新");
            }
        });

        // ── "是否强制更新" 开关逻辑 ──
        switchForceUpdate.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isLoadingData) return;

            if (isChecked) {
                // 必须先开启"插件更新"
                if (!switchPluginUpdate.isChecked()) {
                    switchForceUpdate.setChecked(false);
                    showToast("开启强制更新前，请先开启插件更新");
                } else {
                    autoSave();
                    showToast("已开启强制更新");
                }
            } else {
                autoSave();
                showToast("已关闭强制更新");
            }
        });

        // 应用切换时重新加载该应用的配置
        spinnerApps.setOnItemSelectedListener(position -> {
            if (position == 0) {
                selectedAppId = 0; // 所有应用
            } else {
                selectedAppId = appList.get(position - 1).getAppId();
            }
            Log.d(TAG, "已选择应用: " + selectedAppId);
            // 切换应用时加载对应配置
            loadPluginUpdateConfig();
        });
    }

    /**
     * 为所有编辑框设置 TextWatcher，内容变化时标记并自动保存
     */
    private void setupTextWatchers() {
        TextWatcher autoSaveWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!isLoadingData) {
                    hasUserChanges = true;
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!isLoadingData && hasUserChanges) {
                    autoSave();
                    hasUserChanges = false;
                }
            }
        };

        etUpdateTitle.addTextChangedListener(autoSaveWatcher);
        etUpdateUrl.addTextChangedListener(autoSaveWatcher);
        etVersion.addTextChangedListener(autoSaveWatcher);
        etUpdateContent.addTextChangedListener(autoSaveWatcher);
    }

    /**
     * 检查所有编辑框是否都非空
     */
    private boolean areAllInputsValid() {
        String title = etUpdateTitle.getText() != null ? etUpdateTitle.getText().toString().trim() : "";
        String url = etUpdateUrl.getText() != null ? etUpdateUrl.getText().toString().trim() : "";
        String version = etVersion.getText() != null ? etVersion.getText().toString().trim() : "";
        String content = etUpdateContent.getText() != null ? etUpdateContent.getText().toString().trim() : "";

        return !TextUtils.isEmpty(title)
                && !TextUtils.isEmpty(url)
                && !TextUtils.isEmpty(version)
                && !TextUtils.isEmpty(content);
    }

    /**
     * 自动保存当前配置到服务器
     * 区分 appid：selectedAppId=0 表示所有应用
     */
    private void autoSave() {
        String title = etUpdateTitle.getText() != null ? etUpdateTitle.getText().toString().trim() : "";
        String url = etUpdateUrl.getText() != null ? etUpdateUrl.getText().toString().trim() : "";
        String version = etVersion.getText() != null ? etVersion.getText().toString().trim() : "";
        String content = etUpdateContent.getText() != null ? etUpdateContent.getText().toString().trim() : "";
        boolean isEnabled = switchPluginUpdate.isChecked();
        boolean isForced = switchForceUpdate.isChecked();

        // 如果开启了插件更新但内容有空，在自动保存时静默处理（开关处已拦截）
        if (isEnabled && !areAllInputsValid()) {
            return;
        }

        try {
            JSONObject postData = new JSONObject();
            postData.put("app_name", getAppNameForCurrentSelection());
            postData.put("package_name", getPackageNameForCurrentSelection());
            postData.put("app_id", selectedAppId);
            postData.put("update_title", title);
            postData.put("update_url", url);
            postData.put("version", version);
            postData.put("update_content", content);
            postData.put("is_enabled", isEnabled ? 1 : 0);
            postData.put("is_forced", isForced ? 1 : 0);

            String serverUrl = TokenAuthHelper.appendTokenToUrl(
                    AppConfig.PLUGIN_UPDATE_URL, PluginUpdateActivity.this);

            Log.d(TAG, "自动保存: app_id=" + selectedAppId + ", is_enabled=" + isEnabled);

            StringRequest saveRequest = new StringRequest(Request.Method.POST, serverUrl,
                    response -> {
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            if (!jsonResponse.getBoolean("success")) {
                                String msg = jsonResponse.optString("message", "保存失败");
                                showToast(msg);
                                Log.e(TAG, "保存失败: " + msg);
                            } else {
                                Log.d(TAG, "自动保存成功");
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析保存响应失败", e);
                        }
                    },
                    error -> {
                        Log.e(TAG, "自动保存网络错误", error);
                    }) {
                @Override
                public byte[] getBody() {
                    return postData.toString().getBytes();
                }

                @Override
                public String getBodyContentType() {
                    return "application/json; charset=utf-8";
                }
            };

            requestQueue.add(saveRequest);

        } catch (JSONException e) {
            Log.e(TAG, "构建保存数据失败", e);
        }
    }

    /**
     * 根据当前选中的 app 获取 app_name
     * 如果是"所有应用"(selectedAppId=0)，使用特殊标识
     */
    private String getAppNameForCurrentSelection() {
        if (selectedAppId == 0) {
            return "__ALL_APPS__";
        }
        for (AppItem app : appList) {
            if (app.getAppId() == selectedAppId) {
                return app.getAppName();
            }
        }
        return "__ALL_APPS__";
    }

    /**
     * 获取包名（当前从 AppItem 取不到时回退）
     */
    private String getPackageNameForCurrentSelection() {
        if (selectedAppId == 0) {
            return "__ALL_APPS__";
        }
        // 尝试从 appList 获取更多信息
        for (AppItem app : appList) {
            if (app.getAppId() == selectedAppId) {
                return app.getAppName(); // AppItem 用 appName 作为标识
            }
        }
        return "__ALL_APPS__";
    }

    /**
     * 加载当前选中应用的插件更新配置
     */
    private void loadPluginUpdateConfig() {
        String appName = getAppNameForCurrentSelection();
        String packageName = getPackageNameForCurrentSelection();

        if (TextUtils.isEmpty(appName) || TextUtils.isEmpty(packageName)) {
            return;
        }

        isLoadingData = true;

        String url = TokenAuthHelper.appendTokenToUrl(
                AppConfig.PLUGIN_UPDATE_URL + "?app_name=" + appName + "&package_name=" + packageName,
                PluginUpdateActivity.this);

        Log.d(TAG, "加载插件更新配置: " + url);

        StringRequest configRequest = new StringRequest(Request.Method.GET, url,
                response -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(response);
                        if (jsonResponse.getBoolean("success") && !jsonResponse.isNull("data")) {
                            JSONObject data = jsonResponse.getJSONObject("data");
                            applyConfig(data);
                        } else {
                            // 没有配置，清空界面
                            clearConfig();
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "解析配置响应失败", e);
                        clearConfig();
                    }
                    isLoadingData = false;
                    hasUserChanges = false;
                },
                error -> {
                    Log.e(TAG, "加载配置网络错误", error);
                    isLoadingData = false;
                    hasUserChanges = false;
                });

        requestQueue.add(configRequest);
    }

    /**
     * 应用服务器返回的配置到界面
     */
    private void applyConfig(JSONObject data) {
        try {
            etUpdateTitle.setText(data.optString("update_title", ""));
            etUpdateUrl.setText(data.optString("update_url", ""));
            etVersion.setText(data.optString("version", ""));
            etUpdateContent.setText(data.optString("update_content", ""));
            switchPluginUpdate.setChecked(data.optInt("is_enabled", 0) == 1);
            switchForceUpdate.setChecked(data.optInt("is_forced", 0) == 1);
            Log.d(TAG, "已加载配置");
        } catch (Exception e) {
            Log.e(TAG, "应用配置失败", e);
        }
    }

    /**
     * 清空界面配置
     */
    private void clearConfig() {
        etUpdateTitle.setText("");
        etUpdateUrl.setText("");
        etVersion.setText("");
        etUpdateContent.setText("");
        switchPluginUpdate.setChecked(false);
        switchForceUpdate.setChecked(false);
    }

    /** 加载当前用户的应用列表（与卡密管理界面同源同接口） */
    private void loadUserApps() {
        int userId = spManager.getUserId();
        if (userId <= 0) {
            showToast("用户信息无效");
            return;
        }

        String url = TokenAuthHelper.appendTokenToUrl(
                AppConfig.GET_APPS_URL + "?user_id=" + userId, PluginUpdateActivity.this);
        Log.d(TAG, "请求应用列表URL: " + url);

        StringRequest appsRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.d(TAG, "应用列表响应: " + InputValidator.sanitizeForLog(response));
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            if (jsonResponse.getBoolean("success")) {
                                setupAppsSpinner(jsonResponse.getJSONArray("data"));
                            } else {
                                showToast("加载应用列表失败: " + jsonResponse.optString("message"));
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

    /** 构建下拉列表显示项：第一项为「所有应用」 */
    private void setupAppsSpinner(JSONArray appsArray) {
        try {
            appList.clear();
            for (int i = 0; i < appsArray.length(); i++) {
                JSONObject appObject = appsArray.getJSONObject(i);
                appList.add(new AppItem(
                        appObject.getInt("app_id"),
                        appObject.getString("app_name"),
                        appObject.getString("app_desc")));
            }

            List<String> spinnerItems = new ArrayList<>();
            spinnerItems.add("所有应用");
            for (AppItem app : appList) {
                spinnerItems.add(app.getAppName() + " - (" + app.getAppId() + ")");
            }

            spinnerApps.setSheetTitle("选择应用");
            spinnerApps.setOptionList(spinnerItems);
            // 默认选中第一个（所有应用），加载配置
            selectedAppId = 0;
            loadPluginUpdateConfig();
        } catch (JSONException e) {
            Log.e(TAG, "设置应用下拉框失败", e);
            showToast("设置应用列表失败");
        }
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}