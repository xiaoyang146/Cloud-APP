package com.cloud.dex;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.view.GravityCompat;
import android.content.IntentFilter;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import androidx.fragment.app.Fragment;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.widget.ImageView;
import android.widget.FrameLayout;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.content.BroadcastReceiver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // 标记是否为主题切换导致的Activity重建（避免触发大量API请求导致429）
    private static boolean sIsThemeSwitching = false;
    // 主题切换防抖：防止频繁切换触发429限流
    private static long sLastThemeToggleTime = 0;

    private SharedPreferencesManager spManager;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;

    private BottomNavigationView bottomNavigationView;

    // 侧边栏滑动手势跟踪
    private float drawerTouchStartX = 0;
    private float drawerTouchStartY = 0;
    private boolean drawerGestureConsumed = false;

    private static final int REQUEST_STORAGE_PERMISSION = 1001;
    private int pendingExportAppId;
    private String pendingExportKamiType;
    private String pendingExportFormat;
    private androidx.appcompat.app.AlertDialog pendingExportDialog;

    // Home page views moved to HomeFragment


    private RequestQueue requestQueue;
    private boolean isDestroyed = false;

    /** 版本更新检测器：进入主页后自动检查一次（支持后台强制更新） */
    private AppUpdateManager appUpdateManager;
    /** 防止 onResume 反复触发更新检查 */
    private boolean updateCheckedInThisSession = false;

    // Home page views/methods moved to HomeFragment

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: 开始初始化");
        setContentView(R.layout.activity_main);

        spManager = new SharedPreferencesManager(this);

        setupActionBar();

        spManager.diagnose();

        if (!spManager.isUserDataValid()) {
            Log.e(TAG, "用户数据不完整或未登录，跳转到登录页面");
            showToast("登录信息已过期，请重新登录");
            navigateToLoginActivity();
            return;
        }

        Log.d(TAG, "用户数据验证通过，继续初始化");

        requestQueue = Volley.newRequestQueue(this);

        initViews();
        setupNavigationView();
        setupBottomNavigation();

        // 默认加载首页Fragment
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, new HomeFragment(), "HOME")
                    .commit();
        }

        // 判断是否为主题切换重建
        if (sIsThemeSwitching) {
            Log.d(TAG, "主题切换重建，跳过所有网络请求以降低服务器负载");

            requestQueue = Volley.newRequestQueue(this);
            initViews();
            setupNavigationView();
            setupBottomNavigation();

            Log.d(TAG, "onCreate: 主题切换初始化完成");
            return;
        }


        Log.d(TAG, "onCreate: 初始化完成");

        // 每次打开应用都自动检测一次更新（后台开启强制更新时会阻止继续使用）
        checkAppUpdateOnLaunch();
    }

    /**
     * 打开应用时自动检测版本更新。
     * 与 checkUpdateBadge 只更新小红点不同，这里会真正弹出更新对话框；
     * 若后台把该版本标记为强制更新，弹窗不可取消，用户必须更新后才能继续使用。
     */
    private void checkAppUpdateOnLaunch() {
        try {
            if (appUpdateManager == null) {
                appUpdateManager = new AppUpdateManager(this);
            }
            // silent=true：没有新版本时保持安静；有新版本（尤其强制更新）时必定弹窗
            appUpdateManager.checkUpdate(true, true);
            updateCheckedInThisSession = true;
            Log.d(TAG, "启动时已自动检查版本更新");
        } catch (Exception e) {
            Log.e(TAG, "启动时检查版本更新失败", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        try {
            // 主题切换重建时跳过头像同步(避免触发 429),但让 Fragment 刷新数据
            if (sIsThemeSwitching) {
                sIsThemeSwitching = false;
                // 只做本地的 avatar 加载(不请求网络)
                loadAvatarForHome();
                // 发送广播让 Fragment 自行决定是否刷新
                LocalBroadcastManager.getInstance(this).sendBroadcast(
                        new Intent("com.cloud.dex.ACTION_REFRESH_STATISTICS"));
                return;
            }

            refreshAvatar();
            syncAvatarFromServer();
        } catch (Exception e) {
            Log.e(TAG, "onResume处理失败", e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }


    private void setupActionBar() {
        try {
            Toolbar toolbar = findViewById(R.id.toolbar);
            setSupportActionBar(toolbar);

            if (getSupportActionBar() != null) {
                // 标题设为空，由布局内容决定
                getSupportActionBar().setTitle("");
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                // 初始用圆形默认头像占位
                getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_default_avatar);
                // 不需要右侧自定义头像
                getSupportActionBar().setDisplayShowCustomEnabled(false);
            }

            // 异步加载真实头像替换 home 按钮图标
            loadAvatarForHome();

        } catch (Exception e) {
            Log.e(TAG, "设置ActionBar失败", e);
        }
    }

    /**
     * 将用户头像加载为 ActionBar 的 Home 按钮（用于展开侧边栏）
     */
    private void loadAvatarForHome() {
        try {
            if (getSupportActionBar() == null) return;
            if (spManager == null) return;

            String avatarUrl = spManager.getAvatar();
            int sizePx = dpToPx(36);

            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                Glide.with(this)
                        .asBitmap()
                        .load(avatarUrl)
                        .circleCrop()
                        .override(sizePx, sizePx)
                        .into(new CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(Bitmap bitmap, Transition<? super Bitmap> transition) {
                                setHomeIndicator(bitmap);
                            }

                            @Override
                            public void onLoadCleared(Drawable placeholder) {
                                setDefaultHomeIndicator(sizePx);
                            }

                            @Override
                            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                                setDefaultHomeIndicator(sizePx);
                            }
                        });
            } else {
                setDefaultHomeIndicator(sizePx);
            }
        } catch (Exception e) {
            Log.e(TAG, "加载头像到Home按钮失败", e);
        }
    }

    private void setHomeIndicator(Bitmap bitmap) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setHomeAsUpIndicator(new BitmapDrawable(getResources(), bitmap));
        }
    }

    private void setDefaultHomeIndicator(int sizePx) {
        try {
            Drawable vector = AppCompatResources.getDrawable(this, R.drawable.ic_default_avatar);
            if (vector == null) return;
            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            vector.setBounds(0, 0, sizePx, sizePx);
            vector.draw(canvas);
            setHomeIndicator(bitmap);
        } catch (Exception e) {
            Log.e(TAG, "设置默认头像失败", e);
        }
    }

    private void refreshAvatar() {
        loadAvatarForHome();
    }

    private void syncAvatarFromServer() {
        try {
            if (spManager == null || requestQueue == null) return;
            int userId = spManager.getUserId();
            if (userId <= 0) return;

            String url = TokenAuthHelper.appendTokenToUrl(AppConfig.USER_INFO_URL + "?user_id=" + userId, MainActivity.this);
            JsonObjectRequest request = new JsonObjectRequest(Request.Method.GET, url, null,
                    response -> {
                        try {
                            if (response.getBoolean("success")) {
                                JSONObject data = response.getJSONObject("data");
                                String latestAvatar = data.optString("avatar_url", null);
                                if (latestAvatar != null && !latestAvatar.isEmpty()) {
                                    String savedAvatar = spManager.getAvatar();
                                    if (!latestAvatar.equals(savedAvatar)) {
                                        spManager.setAvatar(latestAvatar);
                                        refreshAvatar();
                                    }
                                }
                                String latestEmail = data.optString("email", null);
                                if (latestEmail != null && !latestEmail.isEmpty()) {
                                    spManager.setEmail(latestEmail);
                                    updateNavigationHeader();
                                }
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析用户信息失败", e);
                        }
                    },
                    error -> Log.e(TAG, "获取用户信息失败", error));
            requestQueue.add(request);
        } catch (Exception e) {
            Log.e(TAG, "同步头像失败", e);
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }


    private void initViews() {
        try {
            drawerLayout = findViewById(R.id.drawer_layout);
            navigationView = findViewById(R.id.nav_view);
            bottomNavigationView = findViewById(R.id.bottom_navigation);

            Log.d(TAG, "视图初始化完成");
        } catch (Exception e) {
            Log.e(TAG, "视图初始化失败", e);
            showToast("界面初始化失败");
            navigateToLoginActivity();
        }
    }






    // Home page methods moved to HomeFragment








    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                drawerTouchStartX = event.getX();
                drawerTouchStartY = event.getY();
                drawerGestureConsumed = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!drawerGestureConsumed && drawerLayout != null
                        && !drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    float dx = event.getX() - drawerTouchStartX;
                    float dy = Math.abs(event.getY() - drawerTouchStartY);
                    // 从左向右滑动超过60dp，且水平距离明显大于垂直距离
                    if (dx > dpToPx(60) && dx > dy * 2) {
                        drawerGestureConsumed = true;
                        drawerLayout.openDrawer(GravityCompat.START);
                        // 发送取消事件，终止当前触摸序列在子View中的传递
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                        return true;
                    }
                }
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    private void setupNavigationView() {
        try {
            // 设置头部信息
            View headerView = navigationView.getHeaderView(0);
            ImageView ivNavAvatar = headerView.findViewById(R.id.ivNavAvatar);
            TextView tvNavUsername = headerView.findViewById(R.id.tvNavUsername);
            TextView tvNavEmail = headerView.findViewById(R.id.tvNavEmail);
            TextView tvNavMembership = headerView.findViewById(R.id.tvNavMembership);

            // 填充用户信息
            populateNavHeader(ivNavAvatar, tvNavUsername, tvNavEmail, tvNavMembership);

            // 设置导航项点击监听器
            navigationView.setNavigationItemSelectedListener(new NavigationView.OnNavigationItemSelectedListener() {
                @Override
                public boolean onNavigationItemSelected(MenuItem item) {
                    int id = item.getItemId();

                    // 获取菜单项视图，如果为null则创建一个临时视图
                    final View menuItemView = navigationView.getMenu().findItem(id).getActionView();
                    final View animationView;

                    if (menuItemView == null) {
                        // 如果没有自定义ActionView，创建一个临时视图来执行动画
                        animationView = new View(MainActivity.this);
                    } else {
                        animationView = menuItemView;
                    }

                    // 执行缩放动画
                    animationView.animate()
                            .scaleX(0.9f)
                            .scaleY(0.9f)
                            .setDuration(100)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    animationView.animate()
                                            .scaleX(1f)
                                            .scaleY(1f)
                                            .setDuration(100)
                                            .start();
                                }
                            })
                            .start();

                    // 使用Handler延迟执行导航逻辑，让动画有足够时间显示
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            handleNavigationItemClick(id);
                            drawerLayout.closeDrawer(GravityCompat.START);
                        }
                    }, 150);

                    return true;
                }
            });

            // 检测更新并设置小红点
            checkUpdateBadge();

            Log.d(TAG, "导航视图设置完成");

            // 自动获取会员类型信息
            autoFetchMembershipType();

            // 设置白天/夜间模式开关
            setupDayModeSwitch();

        } catch (Exception e) {
            Log.e(TAG, "导航视图设置失败", e);
        }
    }

    /**
     * 设置白天/夜间模式开关
     */
    private void setupDayModeSwitch() {
        try {
            final MenuItem menuItem = navigationView.getMenu().findItem(R.id.nav_day_mode);
            if (menuItem == null) return;

            View actionView = menuItem.getActionView();
            if (actionView == null) return;

            final IosLikeSwitch switchDayMode = actionView.findViewById(R.id.switchDayMode);
            if (switchDayMode == null) return;

            // 从保存的设置恢复开关状态
            final boolean isNight = spManager.isNightMode();
            switchDayMode.setChecked(!isNight); // ON=白天, OFF=夜间
            menuItem.setTitle(!isNight ? "白天模式" : "夜间模式");

            switchDayMode.setOnCheckedChangeListener(new IosLikeSwitch.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(IosLikeSwitch buttonView, boolean isChecked) {
                    // 防抖：1.5秒内不允许重复切换，避免频繁触发导致429
                    long now = System.currentTimeMillis();
                    if (now - sLastThemeToggleTime < 1500) {
                        // 恢复开关状态到切换前的值
                        switchDayMode.setChecked(!isChecked);
                        return;
                    }
                    sLastThemeToggleTime = now;

                    menuItem.setTitle(isChecked ? "白天模式" : "夜间模式");
                    boolean nightMode = !isChecked;
                    spManager.setNightMode(nightMode);

                    if (nightMode) {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                    } else {
                        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                    }

                    // 标记为主题切换，避免recreate后触发大量API请求导致429
                    sIsThemeSwitching = true;

                    // 先关闭侧边栏，再执行涟漪动画
                    drawerLayout.closeDrawer(GravityCompat.START);
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            // 涟漪扩散动画（API 21+）
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                try {
                                    final ViewGroup decorView = (ViewGroup) getWindow().getDecorView();
                                    final View revealView = new View(MainActivity.this);
                                    revealView.setBackgroundColor(nightMode ? 0xFF1E1E1E : Color.WHITE);
                                    decorView.addView(revealView, new ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT));

                                    int cx = decorView.getWidth() / 2;
                                    int cy = decorView.getHeight() / 2;
                                    float finalRadius = (float) Math.hypot(cx, cy);

                                    Animator animator = ViewAnimationUtils.createCircularReveal(
                                            revealView, cx, cy, 0, finalRadius);
                                    animator.setDuration(400);
                                    animator.addListener(new AnimatorListenerAdapter() {
                                        @Override
                                        public void onAnimationEnd(Animator animation) {
                                            decorView.removeView(revealView);
                                            recreate();
                                        }

                                        @Override
                                        public void onAnimationCancel(Animator animation) {
                                            decorView.removeView(revealView);
                                            recreate();
                                        }
                                    });
                                    animator.start();
                                    return;
                                } catch (Exception e) {
                                    Log.e(TAG, "涟漪动画失败", e);
                                }
                            }
                            recreate();
                        }
                    }, 300);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "设置白天模式开关失败", e);
        }
    }

    /**
     * 填充导航头部：头像、用户名、邮箱、会员状态、会员等级图标
     */
    private void populateNavHeader(ImageView ivAvatar, TextView tvUsername, TextView tvEmail, TextView tvMembership) {
        try {
            // 用户名
            String username = spManager.getUsername();
            tvUsername.setText(TextUtils.isEmpty(username) ? "用户" : username);

            // 邮箱
            String email = spManager.getEmail();
            if (!TextUtils.isEmpty(email)) {
                tvEmail.setText(email);
            } else {
                tvEmail.setText("未设置邮箱");
            }

            // 会员状态 + 等级图标（作为 compound drawable 直接显示在 TextView 内）
            String membershipType = spManager.getMembershipType();
            if (!TextUtils.isEmpty(membershipType)) {
                tvMembership.setText(membershipType);
                tvMembership.setVisibility(View.VISIBLE);
                // 根据会员类型设置右侧图标
                int iconResId = getMemberLevelIcon(membershipType);
                tvMembership.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, iconResId, 0);
            } else {
                tvMembership.setText("加载中...");
                tvMembership.setVisibility(View.VISIBLE);
                tvMembership.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
            }

            // 头像
            String avatarUrl = spManager.getAvatar();
            int sizePx = dpToPx(72);
            if (!TextUtils.isEmpty(avatarUrl)) {
                Glide.with(this)
                        .asBitmap()
                        .load(avatarUrl)
                        .circleCrop()
                        .override(sizePx, sizePx)
                        .into(new CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(Bitmap bitmap, Transition<? super Bitmap> transition) {
                                ivAvatar.setImageBitmap(bitmap);
                            }

                            @Override
                            public void onLoadCleared(Drawable placeholder) {
                                ivAvatar.setImageResource(R.drawable.ic_default_avatar);
                            }

                            @Override
                            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                                ivAvatar.setImageResource(R.drawable.ic_default_avatar);
                            }
                        });
            } else {
                ivAvatar.setImageResource(R.drawable.ic_default_avatar);
            }
        } catch (Exception e) {
            Log.e(TAG, "填充导航头部失败", e);
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


    /**
     * 自动获取会员类型
     */
    private void autoFetchMembershipType() {
        // 主题切换重建时不请求网络，避免429
        if (sIsThemeSwitching) return;
        int userId = spManager.getUserId();
        if (userId <= 0) {
            return;
        }

        // 延迟1秒执行，避免影响主线程
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                fetchMembershipTypeFromServer(userId);
            }
        }, 1000);
    }

    /**
     * 从服务器获取会员类型
     */
    private void fetchMembershipTypeFromServer(int userId) {
        if (isDestroyed) return;

        String MEMBERSHIP_TYPE_URL = TokenAuthHelper.appendTokenToUrl(AppConfig.MEMBERSHIP_TYPE_URL + "?user_id=" + userId, MainActivity.this);

        StringRequest request = new StringRequest(Request.Method.GET, MEMBERSHIP_TYPE_URL,
                response -> {
                    try {
                        JSONObject json = new JSONObject(response);
                        if (json.getBoolean("success")) {
                            String type = json.getString("membership_type");
                            spManager.setMembershipType(type);
                        }
                        updateNavigationHeader();
                    } catch (JSONException e) {
                        updateNavigationHeader();
                    }
                },
                error -> {
                    updateNavigationHeader();
                });
        requestQueue.add(request);
    }


    /**
     * 更新导航头部显示
     */
    private void updateNavigationHeader() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (isDestroyed || isFinishing()) {
                        return;
                    }

                    View headerView = navigationView.getHeaderView(0);
                    ImageView ivNavAvatar = headerView.findViewById(R.id.ivNavAvatar);
                    TextView tvNavUsername = headerView.findViewById(R.id.tvNavUsername);
                    TextView tvNavEmail = headerView.findViewById(R.id.tvNavEmail);
                    TextView tvNavMembership = headerView.findViewById(R.id.tvNavMembership);

                    populateNavHeader(ivNavAvatar, tvNavUsername, tvNavEmail, tvNavMembership);

                    Log.d(TAG, "导航头部已更新");

                } catch (Exception e) {
                    Log.e(TAG, "更新导航头部失败", e);
                }
            }
        });
    }





    private void setupBottomNavigation() {
        try {
            if (bottomNavigationView == null) {
                return;
            }

            bottomNavigationView.setOnItemSelectedListener(item -> {
                int itemId = item.getItemId();
                if (itemId == R.id.nav_bottom_home) {
                    switchFragment(new HomeFragment(), "HOME");
                    return true;
                } else if (itemId == R.id.nav_bottom_apps) {
                    switchFragment(new AppsFragment(), "APPS");
                    return true;
                } else if (itemId == R.id.nav_bottom_membership) {
                    switchFragment(new MembershipFragment(), "MEMBERSHIP");
                    return true;
                } else if (itemId == R.id.nav_bottom_profile) {
                    switchFragment(new ProfileFragment(), "PROFILE");
                    return true;
                }
                return false;
            });

            Log.d(TAG, "底部导航栏设置完成");
        } catch (Exception e) {
            Log.e(TAG, "底部导航栏设置失败", e);
        }
    }

    /**
     * 切换底部导航页面。
     *
     * 与原来「每次 new 一个 Fragment 再 replace」不同，这里改为 Fragment 复用：
     * - 首次进入才 add，之后一律 show/hide；
     * - 复用后不会重新走 onCreateView / onResume，
     *   因此点击底部导航只切换界面，绝不会自动重新拉取数据（不自动刷新）。
     * 需要更新数据时，由用户手动下拉刷新，或由数据变更后的自动刷新触发。
     */
    private void switchFragment(Fragment fragment, String tag) {
        try {
            final androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
            final Fragment target = (tag != null) ? fm.findFragmentByTag(tag) : null;

            // 已经在当前页面：什么都不做，避免无谓的事务与刷新
            if (target != null && !target.isHidden()) {
                return;
            }

            final androidx.fragment.app.FragmentTransaction tx = fm.beginTransaction();
            final java.util.List<Fragment> fragments = fm.getFragments();
            for (int i = 0; i < fragments.size(); i++) {
                final Fragment f = fragments.get(i);
                if (f == null || f == target) {
                    continue;
                }
                if (!f.isHidden()) {
                    tx.hide(f);
                }
            }
            if (target == null) {
                tx.add(R.id.fragment_container, fragment, tag);
            } else {
                tx.show(target);
            }
            tx.commitAllowingStateLoss();
        } catch (Exception e) {
            Log.e(TAG, "switchFragment failed", e);
        }
    }

    private void handleNavigationItemClick(int itemId) {
        try {
            switch (itemId) {
                case R.id.nav_add_app:
                    showAddAppDialog(); // 显示添加应用对话框
                    break;
                case R.id.nav_edit_app:
                    showEditAppDialog(); // 显示编辑应用对话框
                    break;
                case R.id.nav_plugin_update:
                    startActivity(new Intent(MainActivity.this, PluginUpdateActivity.class)); // 跳转到插件更新界面
                    break;
                case R.id.nav_app_list:
                    navigateToAppList();// 跳转到应用列表界面
                    break;
                case R.id.nav_add_announcement:
                    showAddNoticeDialog();//显示添加公告对话框
                    break;
                case R.id.nav_manage_announcement:
                    navigateToNoticeManagement(); // 跳转到公告管理界面
                    break;
                case R.id.nav_add_card:
                    showAddKamiDialog(); // 显示添加卡密对话框
                    break;
                case R.id.nav_manage_card:
                    navigateToKamiManagement(); // 跳转到卡密管理界面
                    break;
                case R.id.nav_export_card:
                    showExportKamiDialog(); // 显示导出卡密对话框
                    break;
                case R.id.nav_update_log:
                    showUpdateLogDialog(); // 显示更新日志对话框
                    break;
                // 在 handleNavigationItemClick 方法中添加
                case R.id.nav_membership:
                    openMembershipPage(0); // 0表示全局会员
                    break;
                default:
                    break;
                case R.id.nav_development_doc:
                    startActivity(new Intent(MainActivity.this, DevelopmentDocActivity.class));
                    break;
                case R.id.nav_about_cloud:
                    startActivity(new Intent(MainActivity.this, AboutCloudActivity.class));
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "处理导航项点击时发生异常", e);
        }
    }









    /**
     * 显示更新日志对话框 - 添加检查新版本功能
     */
    /**
     * 显示更新日志对话框 - 添加自动检查新版本功能
     */
    private void showUpdateLogDialog() {
        try {
            androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
            builder.setTitle("更新日志");

            // 创建对话框布局
            LayoutInflater inflater = LayoutInflater.from(this);
            View dialogView = inflater.inflate(R.layout.dialog_update_log, null);
            builder.setView(dialogView);

            // 获取视图引用
            TextView tvUpdateLog = dialogView.findViewById(R.id.tvUpdateLog);
            TextView tvCurrentVersion = dialogView.findViewById(R.id.tvCurrentVersion);
            TextView tvLatestVersion = dialogView.findViewById(R.id.tvLatestVersion);
            View svUpdateLogContent = dialogView.findViewById(R.id.svUpdateLogContent);
            View llEmptyState = dialogView.findViewById(R.id.llEmptyState);
            LinearLayout llUpdateLogContainer = dialogView.findViewById(R.id.llUpdateLogContainer);

            // 获取当前应用信息
            String currentAppName = getApplicationName();
            String currentPackageName = getPackageName();
            String currentVersionName = getVersionName();

            // 显示当前版本信息
            tvCurrentVersion.setText("当前版本: " + currentVersionName);
            tvLatestVersion.setText("最新版本：检查中...");

            // 为最新版本TextView添加点击事件
            tvLatestVersion.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 添加点击反馈动画
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start();
                                    // 执行检查更新
                                    checkForUpdate(currentAppName, currentPackageName, currentVersionName,
                                            tvUpdateLog, tvLatestVersion);
                                }
                            }).start();
                }
            });

            // 加载更新日志
            loadUpdateLog(tvUpdateLog, llUpdateLogContainer, svUpdateLogContent, llEmptyState);

            // 打开更新日志后清除小红点
            updateUpdateLogBadge(false);

            // 自动检查最新版本（不显示对话框，只更新版本号）
            autoCheckLatestVersion(currentAppName, currentPackageName, currentVersionName, tvLatestVersion);

            androidx.appcompat.app.AlertDialog dialog = builder.create();
            dialog.show();

            // 获取布局中的关闭按钮
            Button btnClose = dialogView.findViewById(R.id.btn_close);
            btnClose.setOnClickListener(v -> dialog.dismiss());

        } catch (Exception e) {
            Log.e(TAG, "显示更新日志对话框时发生异常", e);
            showToast("打开更新日志失败");
        }
    }

    /**
     * 自动检查最新版本（只获取版本号，不显示更新对话框）
     */
    private void autoCheckLatestVersion(String appName, String packageName, String versionName,
                                        TextView latestVersionText) {
        if (isDestroyed) return;

        String CHECK_UPDATE_URL = AppConfig.CHECK_VERSION_URL;

        StringRequest request = new StringRequest(Request.Method.POST, CHECK_UPDATE_URL,
                response -> {
                    Log.d(TAG, "自动检查版本响应: " + InputValidator.sanitizeForLog(response));
                    try {
                        JSONObject json = new JSONObject(response);
                        if (json.getBoolean("success")) {
                            boolean needsUpdate = json.getBoolean("needs_update");
                            if (needsUpdate) {
                                JSONObject latest = json.getJSONObject("latest_version");
                                String latestName = latest.getString("version_name");
                                latestVersionText.setText("最新版本：" + latestName + " (点击更新)");
                                latestVersionText.setTextColor(Color.RED);
                            } else {
                                latestVersionText.setText("最新版本：" + versionName + " (已是最新)");
                                latestVersionText.setTextColor(Color.parseColor("#4CAF50"));
                            }
                        } else {
                            latestVersionText.setText("最新版本：检查失败");
                            latestVersionText.setTextColor(Color.GRAY);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "解析自动检查版本响应失败", e);
                        latestVersionText.setText("最新版本：解析失败");
                        latestVersionText.setTextColor(Color.GRAY);
                    }
                },
                error -> {
                    Log.e(TAG, "自动检查版本网络错误", error);
                    latestVersionText.setText("最新版本：网络错误");
                    latestVersionText.setTextColor(Color.GRAY);
                }) {
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                params.put("app_name", appName);
                params.put("package_name", packageName);
                params.put("current_version_code", String.valueOf(BuildConfig.VERSION_CODE)); // 注意这里用整数
                return params;
            }

            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/x-www-form-urlencoded");
                return headers;
            }
        };

        requestQueue.add(request);
    }

    /**
     * 手动检查新版本（用户点击“最新版本”调用）
     */
    private void checkForUpdate(String appName, String packageName, String versionName,
                                TextView logTextView, TextView latestVersionText) {
        if (isDestroyed) return;

        latestVersionText.setText("最新版本：检查中...");
        latestVersionText.setTextColor(Color.GRAY);
        latestVersionText.setClickable(false);

        String CHECK_UPDATE_URL = AppConfig.CHECK_VERSION_URL;

        StringRequest request = new StringRequest(Request.Method.POST, CHECK_UPDATE_URL,
                response -> {
                    latestVersionText.setClickable(true);
                    Log.d(TAG, "手动检查版本响应: " + InputValidator.sanitizeForLog(response));
                    try {
                        JSONObject json = new JSONObject(response);
                        boolean needsUpdate = json.getBoolean("needs_update");

                        if (needsUpdate) {
                            JSONObject latest = json.getJSONObject("latest_version");
                            String latestName = latest.getString("version_name");
                            String downloadUrl = latest.getString("download_url");
                            String updateLog = latest.getString("update_log");
                            boolean force = latest.getBoolean("force_update");

                            latestVersionText.setText("最新版本：" + latestName + " (点击更新)");
                            latestVersionText.setTextColor(Color.RED);
                            showUpdateAvailableDialog(latestName, updateLog, downloadUrl, force);
                        } else {
                            latestVersionText.setText("最新版本：" + versionName + " (已是最新)");
                            latestVersionText.setTextColor(Color.parseColor("#4CAF50"));
                            Toast.makeText(MainActivity.this, "已是最新版本", Toast.LENGTH_SHORT).show();
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "解析手动检查响应失败", e);
                        latestVersionText.setText("最新版本：解析失败");
                        latestVersionText.setTextColor(Color.GRAY);
                    }
                },
                error -> {
                    latestVersionText.setClickable(true);
                    Log.e(TAG, "手动检查网络错误", error);
                    latestVersionText.setText("最新版本：网络错误");
                    latestVersionText.setTextColor(Color.GRAY);
                }) {
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                params.put("app_name", appName);
                params.put("package_name", packageName);
                params.put("current_version_code", String.valueOf(BuildConfig.VERSION_CODE));
                return params;
            }

            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/x-www-form-urlencoded");
                return headers;
            }
        };

        requestQueue.add(request);
    }

    /**
     * 设置/取消"更新日志"的小红点
     */
    private void updateUpdateLogBadge(boolean visible) {
        try {
            View actionView = navigationView.getMenu().findItem(R.id.nav_update_log).getActionView();
            if (actionView != null) {
                View badgeDot = actionView.findViewById(R.id.badgeDot);
                if (badgeDot != null) {
                    badgeDot.setVisibility(visible ? View.VISIBLE : View.GONE);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "更新小红点失败", e);
        }
    }

    /**
     * 检测版本更新并更新小红点
     */
    private void checkUpdateBadge() {
        if (isDestroyed) return;

        String appName = getApplicationName();
        String packageName = getPackageName();
        int versionCode = BuildConfig.VERSION_CODE;

        String CHECK_UPDATE_URL = AppConfig.CHECK_VERSION_URL;

        StringRequest request = new StringRequest(Request.Method.POST, CHECK_UPDATE_URL,
                response -> {
                    try {
                        JSONObject json = new JSONObject(response);
                        boolean needsUpdate = json.optBoolean("needs_update", false);
                        updateUpdateLogBadge(needsUpdate);
                    } catch (JSONException e) {
                        Log.e(TAG, "解析版本检查响应失败", e);
                    }
                },
                error -> Log.e(TAG, "版本检查网络错误", error)) {
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                params.put("app_name", appName);
                params.put("package_name", packageName);
                params.put("current_version_code", String.valueOf(versionCode));
                return params;
            }

            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/x-www-form-urlencoded");
                return headers;
            }
        };

        requestQueue.add(request);
    }

    /**
     * 刷新时检测是否有强制更新
     */
    private void checkForceUpdateOnRefresh() {
        if (isDestroyed) return;

        String appName = getApplicationName();
        String packageName = getPackageName();
        int versionCode = BuildConfig.VERSION_CODE;

        String CHECK_UPDATE_URL = AppConfig.CHECK_VERSION_URL;

        StringRequest request = new StringRequest(Request.Method.POST, CHECK_UPDATE_URL,
                response -> {
                    try {
                        JSONObject json = new JSONObject(response);
                        boolean needsUpdate = json.optBoolean("needs_update", false);

                        // 同步更新小红点
                        updateUpdateLogBadge(needsUpdate);

                        if (needsUpdate) {
                            JSONObject latest = json.getJSONObject("latest_version");
                            boolean forceUpdate = latest.optBoolean("force_update", false);

                            if (forceUpdate) {
                                String latestName = latest.getString("version_name");
                                String downloadUrl = latest.getString("download_url");
                                String updateLog = latest.optString("update_log", "暂无更新日志");

                                showUpdateAvailableDialog(latestName, updateLog, downloadUrl, true);
                            }
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "解析强制更新检查响应失败", e);
                    }
                },
                error -> Log.e(TAG, "强制更新检查网络错误", error)) {
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                params.put("app_name", appName);
                params.put("package_name", packageName);
                params.put("current_version_code", String.valueOf(versionCode));
                return params;
            }

            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/x-www-form-urlencoded");
                return headers;
            }
        };

        requestQueue.add(request);
    }

    /**
     * 获取应用名称
     */
    private String getApplicationName() {
        try {
            PackageManager packageManager = getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(getPackageName(), 0);
            CharSequence appName = packageManager.getApplicationLabel(applicationInfo);
            return appName != null ? appName.toString() : "My Application";
        } catch (Exception e) {
            Log.e(TAG, "获取应用名称失败", e);
            return "My Application";
        }
    }

    /**
     * 获取版本名称
     */
    private String getVersionName() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName != null ? packageInfo.versionName : "1.0.0";
        } catch (Exception e) {
            Log.e(TAG, "获取版本名称失败", e);
            return "1.0.0";
        }
    }


    /**
     * 显示有更新可用的对话框
     */
    private void showUpdateAvailableDialog(String latestVersion, String updateLog,
                                           String downloadUrl, boolean forceUpdate) {
        try {
            androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
            builder.setTitle("发现新版本 " + latestVersion);

            String message = "更新内容：\n" + updateLog +
                    "\n\n是否立即下载更新？";

            builder.setMessage(message);

            if (!forceUpdate) {
                builder.setNegativeButton("稍后", null);
            }

            builder.setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // 打开下载链接
                    openDownloadUrl(downloadUrl);
                }
            });

            androidx.appcompat.app.AlertDialog dialog = builder.create();

            // 如果是强制更新，设置不可取消
            if (forceUpdate) {
                dialog.setCancelable(false);
                dialog.setCanceledOnTouchOutside(false);
            }

            dialog.show();

        } catch (Exception e) {
            Log.e(TAG, "显示更新对话框时发生异常", e);
            showToast("显示更新信息失败");
        }
    }

    /**
     * 打开下载链接
     */
    private void openDownloadUrl(String downloadUrl) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "打开下载链接失败", e);
            showToast("无法打开下载链接");
        }
    }

    /**
     * 加载更新日志 - 获取所有版本
     */
    /**
     * 加载更新日志 - 获取所有版本（结构化渲染为版本卡片）
     *
     * @param textView  纯文本/状态提示视图，结构化渲染成功时自动隐藏
     * @param container 版本卡片容器
     */
    private void loadUpdateLog(TextView textView, LinearLayout container,
                               View contentView, View emptyView) {
        // 动态获取当前应用名称
        String currentAppName = getApplicationName();
        String currentPackageName = getPackageName();
        final String currentVersionName = getVersionName();
        String encodedAppName;
        try {
            encodedAppName = URLEncoder.encode(currentAppName, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            encodedAppName = "My%20Application";
            Log.e(TAG, "URL编码应用名称失败", e);
        }

        String UPDATE_LOG_URL = AppConfig.UPDATE_LOG_URL + "?app_name=" + encodedAppName
                + "&package_name=" + currentPackageName + "&get_all=1";

        StringRequest updateLogRequest = new StringRequest(Request.Method.GET, UPDATE_LOG_URL,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            boolean success = jsonResponse.optBoolean("success", false);
                            Log.d(TAG, "更新日志响应: " + InputValidator.sanitizeForLog(response));

                            if (!success) {
                                if (container != null) container.removeAllViews();
                                textView.setVisibility(View.VISIBLE);
                                textView.setText("暂无更新日志");
                                showEmptyState(contentView, emptyView);
                                return;
                            }

                            if (container != null) container.removeAllViews();

                            // ① 优先使用服务端结构化 versions 数组（含版本号与发布日期）
                            JSONArray versions = jsonResponse.optJSONArray("versions");
                            if (versions != null && versions.length() > 0) {
                                for (int i = 0; i < versions.length(); i++) {
                                    JSONObject item = versions.optJSONObject(i);
                                    if (item == null) continue;
                                    String versionName = item.optString("version_name", "");
                                    addVersionCard(container, versionName,
                                            item.optString("created_at", ""),
                                            item.optString("update_log", ""),
                                            isSameVersion(versionName, currentVersionName));
                                }
                                textView.setVisibility(View.GONE);
                                showUpdateLogContent(contentView, emptyView);
                                return;
                            }

                            // ② 兼容服务端旧格式：v1.0.8:\n内容\n\nv1.0.7:\n内容
                            String updateLog = jsonResponse.optString("update_log", "");
                            if (!TextUtils.isEmpty(updateLog)) {
                                String trimmedLog = updateLog.trim();
                                if (!"null".equalsIgnoreCase(trimmedLog)
                                        && !"暂无更新日志".equals(trimmedLog)) {
                                    int count = renderPlainUpdateLog(container, trimmedLog, currentVersionName);
                                    if (count > 0) {
                                        textView.setVisibility(View.GONE);
                                        showUpdateLogContent(contentView, emptyView);
                                        return;
                                    }
                                }
                            }

                            textView.setVisibility(View.VISIBLE);
                            textView.setText("暂无更新日志");
                            showEmptyState(contentView, emptyView);
                        } catch (JSONException e) {
                            Log.e(TAG, "解析更新日志失败", e);
                            if (container != null) container.removeAllViews();
                            textView.setVisibility(View.VISIBLE);
                            textView.setText("加载更新日志失败");
                            showUpdateLogContent(contentView, emptyView);
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "获取更新日志网络错误", error);
                        if (container != null) container.removeAllViews();
                        textView.setVisibility(View.VISIBLE);
                        textView.setText("网络错误，无法加载更新日志");
                        showUpdateLogContent(contentView, emptyView);
                    }
                });

        if (requestQueue == null) {
            requestQueue = Volley.newRequestQueue(this);
        }
        requestQueue.add(updateLogRequest);
    }

    /**
     * 渲染单个版本卡片：版本号徽标 + 当前版本标记 + 日期 + 更新要点
     *
     * @param isCurrent 是否为当前已安装版本
     */
    private void addVersionCard(LinearLayout container, String versionName, String date,
                                String logContent, boolean isCurrent) {
        if (container == null) return;
        try {
            View card = LayoutInflater.from(this)
                    .inflate(R.layout.item_update_log_version, container, false);
            TextView badge = card.findViewById(R.id.tvVersionBadge);
            TextView tag = card.findViewById(R.id.tvVersionTag);
            TextView dateView = card.findViewById(R.id.tvVersionDate);
            LinearLayout points = card.findViewById(R.id.llVersionPoints);

            String displayVersion = TextUtils.isEmpty(versionName) ? "未知版本" : versionName.trim();
            if (!displayVersion.startsWith("v") && !displayVersion.startsWith("V")) {
                displayVersion = "v" + displayVersion;
            }
            badge.setText(displayVersion);

            if (isCurrent) {
                badge.setBackgroundResource(R.drawable.bg_version_badge_current);
                tag.setVisibility(View.VISIBLE);
            } else {
                badge.setBackgroundResource(R.drawable.bg_version_badge);
                tag.setVisibility(View.GONE);
            }

            String dateText = formatUpdateLogDate(date);
            if (TextUtils.isEmpty(dateText)) {
                dateView.setVisibility(View.GONE);
            } else {
                dateView.setVisibility(View.VISIBLE);
                dateView.setText(dateText);
            }

            points.removeAllViews();
            String[] lines = splitLogLines(logContent);
            for (String line : lines) {
                points.addView(buildLogPointView(this, line));
            }
            if (points.getChildCount() == 0) {
                points.addView(buildLogPointView(this, "暂无更新说明"));
            }

            container.addView(card);
        } catch (Exception e) {
            Log.e(TAG, "渲染版本卡片失败", e);
        }
    }

    /**
     * 兼容旧接口返回的纯文本日志：v1.0.8:\n内容\n\nv1.0.7:\n内容
     *
     * @return 成功渲染的版本数量
     */
    private int renderPlainUpdateLog(LinearLayout container, String plainLog, String currentVersionName) {
        int count = 0;
        try {
            String[] blocks = plainLog.split("\\n\\s*\\n");
            for (String block : blocks) {
                if (block == null) continue;
                String content = block.trim();
                if (content.length() == 0) continue;

                String versionName = "";
                int newlineIndex = content.indexOf('\n');
                String firstLine = (newlineIndex >= 0 ? content.substring(0, newlineIndex) : content).trim();
                if (firstLine.matches("^[vV]?\\d+(\\.\\d+)*[:：]?$")) {
                    versionName = firstLine.replaceAll("[:：]$", "");
                    content = newlineIndex >= 0 ? content.substring(newlineIndex + 1).trim() : "";
                }
                addVersionCard(container, versionName, "", content,
                        isSameVersion(versionName, currentVersionName));
                count++;
            }
        } catch (Exception e) {
            Log.e(TAG, "解析纯文本更新日志失败", e);
        }
        return count;
    }

    /**
     * 生成一条更新要点视图
     */
    private static TextView buildLogPointView(Context context, String line) {
        TextView tv = new TextView(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = (int) (4 * context.getResources().getDisplayMetrics().density);
        tv.setLayoutParams(params);
        String text = line == null ? "" : line.trim();
        tv.setText("· " + text);
        tv.setTextSize(13f);
        tv.setTextColor(Color.parseColor("#444444"));
        tv.setLineSpacing(4f, 1f);
        return tv;
    }

    /**
     * 拆分更新日志文本为若干要点，去掉行首的 · / - / 1. 等符号
     */
    private static String[] splitLogLines(String logContent) {
        if (TextUtils.isEmpty(logContent)) {
            return new String[0];
        }
        String normalized = logContent.replace("\r\n", "\n").replace('\r', '\n');
        if ("null".equalsIgnoreCase(normalized.trim())) {
            return new String[0];
        }
        List<String> result = new ArrayList<>();
        for (String line : normalized.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.length() == 0) continue;
            trimmed = trimmed.replaceFirst("^[·•\\-\\*]+\\s*", "");
            trimmed = trimmed.replaceFirst("^\\d+[、.．)）]\\s*", "");
            if (trimmed.length() == 0) continue;
            result.add(trimmed);
        }
        return result.toArray(new String[0]);
    }

    /**
     * 判断日志中的版本号是否与当前安装版本一致
     */
    private static boolean isSameVersion(String versionName, String currentVersionName) {
        if (TextUtils.isEmpty(versionName) || TextUtils.isEmpty(currentVersionName)) {
            return false;
        }
        String a = versionName.trim();
        String b = currentVersionName.trim();
        if (a.startsWith("v") || a.startsWith("V")) a = a.substring(1);
        if (b.startsWith("v") || b.startsWith("V")) b = b.substring(1);
        return a.equalsIgnoreCase(b);
    }

    /**
     * 格式化服务端日期：2026-05-31 19:05:50 -> 2026-05-31
     */
    private static String formatUpdateLogDate(String date) {
        if (TextUtils.isEmpty(date)) return "";
        String value = date.trim();
        if ("null".equalsIgnoreCase(value)) return "";
        if (value.length() >= 10 && value.charAt(4) == '-' && value.charAt(7) == '-') {
            return value.substring(0, 10);
        }
        return value;
    }

    /**
     * 显示更新日志内容，隐藏空状态
     */
    private void showUpdateLogContent(View contentView, View emptyView) {
        contentView.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
    }

    /**
     * 显示空状态，隐藏更新日志内容
     */
    private void showEmptyState(View contentView, View emptyView) {
        contentView.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);
    }












    private void navigateToKamiManagement() {
        try {
            Intent intent = new Intent(MainActivity.this, KamiManagementActivity.class);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "跳转到卡密管理失败", e);
            showToast("跳转失败");
        }
    }

    /**
     * 显示导出卡密对话框
     */
    private void showExportKamiDialog() {
        try {
            androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
            builder.setTitle("导出卡密");

            LayoutInflater inflater = LayoutInflater.from(this);
            View dialogView = inflater.inflate(R.layout.dialog_export_kami, null);
            builder.setView(dialogView);

            // 获取对话框中的视图引用
            OptionSelector spinnerApps = dialogView.findViewById(R.id.spinnerApps);
            OptionSelector spinnerKamiType = dialogView.findViewById(R.id.spinnerKamiType);
            OptionSelector spinnerExportFormat = dialogView.findViewById(R.id.spinnerExportFormat);

            // 获取布局中的按钮
            Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
            Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);

            // 设置初始状态
            setExportDialogLoadingState(spinnerApps, spinnerKamiType, spinnerExportFormat, true);
            btnConfirm.setEnabled(false);

            androidx.appcompat.app.AlertDialog dialog = builder.create();
            dialog.show();

            btnConfirm.setOnClickListener(v -> handleExportKamiSubmit(spinnerApps, spinnerKamiType, spinnerExportFormat, dialog));
            btnCancel.setOnClickListener(v -> dialog.dismiss());

            // 设置卡密类型下拉框
            setupExportKamiTypeSpinner(spinnerKamiType);
            // 设置导出格式下拉框
            setupExportFormatSpinner(spinnerExportFormat);
            // 设置应用下拉框占位符
            setupSpinnerWithPlaceholder(spinnerApps);

            // 加载用户应用数据
            loadUserAppsForExportDialog(spinnerApps, spinnerKamiType, spinnerExportFormat, btnConfirm, dialog);

        } catch (Exception e) {
            Log.e(TAG, "显示导出卡密对话框时发生异常", e);
            showToast("打开导出卡密对话框失败");
        }
    }

    /**
     * 设置导出对话框的加载状态
     */
    private void setExportDialogLoadingState(OptionSelector spinnerApps, OptionSelector spinnerKamiType,
                                             OptionSelector spinnerExportFormat, boolean isLoading) {
        spinnerApps.setEnabled(!isLoading);
        spinnerKamiType.setEnabled(!isLoading);
        spinnerExportFormat.setEnabled(!isLoading);
    }

    /**
     * 设置导出卡密类型下拉框
     */
    private void setupExportKamiTypeSpinner(OptionSelector spinner) {
        String[] kamiTypes = {"全部类型", "体验卡(30分钟)", "小时卡(1小时)", "天卡(1天)", "周卡(1周)", "月卡(1月)", "年卡(1年)", "永久卡"};
        String[] typeValues = {"all", "tyk", "hour", "day", "week", "month", "year", "permanent"};

        spinner.setOptionList(Arrays.asList(kamiTypes));
        spinner.setTag(typeValues); // 存储对应的值
    }

    /**
     * 设置导出格式下拉框
     */
    private void setupExportFormatSpinner(OptionSelector spinner) {
        String[] exportFormats = {"TXT格式", "XLS格式"};
        String[] formatValues = {"txt", "xls"};

        spinner.setOptionList(Arrays.asList(exportFormats));
        spinner.setTag(formatValues); // 存储对应的值
    }

    /**
     * 为导出对话框加载用户应用数据
     */
    private void loadUserAppsForExportDialog(OptionSelector spinnerApps, OptionSelector spinnerKamiType,
                                             OptionSelector spinnerExportFormat, Button positiveButton,
                                             androidx.appcompat.app.AlertDialog dialog) {
        int userId = spManager.getUserId();
        if (userId <= 0) {
            showToast("用户信息无效，请重新登录");
            dialog.dismiss();
            directLogout();
            return;
        }

        fetchUserAppsForExportDialog(userId, spinnerApps, spinnerKamiType, spinnerExportFormat, positiveButton, dialog);
    }

    /**
     * 为导出对话框获取用户应用列表
     */
    private void fetchUserAppsForExportDialog(int userId, final OptionSelector spinnerApps, final OptionSelector spinnerKamiType,
                                              final OptionSelector spinnerExportFormat, final Button positiveButton,
                                              final androidx.appcompat.app.AlertDialog dialog) {
        if (isDestroyed) {
            return;
        }

        String url = TokenAuthHelper.appendTokenToUrl(AppConfig.GET_APPS_URL + "?user_id=" + userId, MainActivity.this);
        Log.d(TAG, "请求导出对话框应用列表URL: " + url);

        StringRequest appsRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        if (isDestroyed || !dialog.isShowing()) {
                            return;
                        }

                        Log.d(TAG, "导出对话框应用列表响应: " + InputValidator.sanitizeForLog(response));
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            boolean success = jsonResponse.getBoolean("success");

                            if (success) {
                                JSONArray appsArray = jsonResponse.getJSONArray("data");
                                setupExportDialogWithAppsData(appsArray, spinnerApps, spinnerKamiType,
                                        spinnerExportFormat, positiveButton);
                            } else {
                                String message = jsonResponse.getString("message");
                                showToast("加载应用列表失败: " + message);
                                dialog.dismiss();
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析导出对话框应用列表响应失败", e);
                            showToast("解析应用列表失败");
                            dialog.dismiss();
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (isDestroyed || !dialog.isShowing()) {
                            return;
                        }

                        Log.e(TAG, "获取导出对话框应用列表网络错误", error);
                        String errorMessage = "网络连接错误";
                        if (error.networkResponse != null) {
                            errorMessage = "网络错误，状态码: " + error.networkResponse.statusCode;
                        }
                        showToast("加载应用列表失败: " + errorMessage);
                        dialog.dismiss();
                    }
                });

        if (requestQueue == null) {
            requestQueue = Volley.newRequestQueue(this);
        }
        requestQueue.add(appsRequest);
    }

    /**
     * 在导出对话框中设置应用数据
     */
    private void setupExportDialogWithAppsData(JSONArray appsArray, OptionSelector spinnerApps, OptionSelector spinnerKamiType,
                                               OptionSelector spinnerExportFormat, Button positiveButton) {
        try {
            // 启用下拉框
            setExportDialogLoadingState(spinnerApps, spinnerKamiType, spinnerExportFormat, false);

            // 启用确认按钮并设置颜色
            positiveButton.setEnabled(true);
            // Button color is set via XML layout

            // 解析应用数据并设置到OptionSelector
            List<AppItem> appList = parseAppsData(appsArray);
            if (appList.isEmpty()) {
                showToast("暂无应用，请先添加应用");
                return;
            }

            // 设置OptionSelector适配器
            List<String> appLabels = new ArrayList<>();
            for (AppItem a : appList) appLabels.add(a.getDisplayName());
            spinnerApps.setOptionList(appLabels, appList);

            // 默认选择第一个（最小的APPID）
            spinnerApps.setSelection(0);

        } catch (Exception e) {
            Log.e(TAG, "设置导出对话框应用数据时发生异常", e);
            showToast("设置应用数据失败");
        }
    }

    /**
     * 处理导出卡密提交
     */
    private void handleExportKamiSubmit(OptionSelector spinnerApps, OptionSelector spinnerKamiType,
                                        OptionSelector spinnerExportFormat, androidx.appcompat.app.AlertDialog dialog) {
        AppItem selectedApp = (AppItem) spinnerApps.getSelectedItem();
        if (selectedApp == null) {
            showToast("请选择应用");
            return;
        }

        // 获取卡密类型值
        String[] typeValues = (String[]) spinnerKamiType.getTag();
        int selectedTypePosition = spinnerKamiType.getSelectedItemPosition();
        String kamiType = typeValues[selectedTypePosition];

        // 获取导出格式值
        String[] formatValues = (String[]) spinnerExportFormat.getTag();
        int selectedFormatPosition = spinnerExportFormat.getSelectedItemPosition();
        String exportFormat = formatValues[selectedFormatPosition];

        // 检查权限
        checkStoragePermissionAndExport(selectedApp.getAppId(), kamiType, exportFormat, dialog);
    }

    /**
     * 检查存储权限并执行导出（只检查卡密类型）
     */
    private void checkStoragePermissionAndExport(int appId, String kamiType, String exportFormat,
                                                 androidx.appcompat.app.AlertDialog dialog) {
        // 检查存储权限
        if (checkStoragePermission()) {
            // 已有权限，执行导出（只检查卡密类型）
            performExportKami(appId, kamiType, exportFormat, dialog);
        } else {
            // 请求权限
            requestStoragePermission(appId, kamiType, exportFormat, dialog);
        }
    }

    /**
     * 检查存储权限
     */
    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要READ_MEDIA_IMAGES权限
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12 需要MANAGE_EXTERNAL_STORAGE权限
            return Environment.isExternalStorageManager();
        } else {
            // Android 6-10 需要WRITE_EXTERNAL_STORAGE权限
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * 请求存储权限（带参数）
     */
    private void requestStoragePermission(int appId, String kamiType, String exportFormat,
                                          androidx.appcompat.app.AlertDialog dialog) {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            permissions = new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12
            showToast("请在设置中授予所有文件访问权限");
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.addCategory("android.intent.category.DEFAULT");
                intent.setData(android.net.Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                startActivityForResult(intent, REQUEST_STORAGE_PERMISSION);
            } catch (Exception e) {
                Intent intent = new Intent();
                intent.setAction(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(intent, REQUEST_STORAGE_PERMISSION);
            }
            return;
        } else {
            // Android 6-10
            permissions = new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }

        ActivityCompat.requestPermissions(this, permissions, REQUEST_STORAGE_PERMISSION);

        // 保存参数以便权限授予后使用
        this.pendingExportAppId = appId;
        this.pendingExportKamiType = kamiType;
        this.pendingExportFormat = exportFormat;
        this.pendingExportDialog = dialog;
    }

    /**
     * 处理权限请求结果
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showToast("存储权限已授予");
                // 权限已授予，执行待处理的导出（只检查卡密类型）
                if (pendingExportDialog != null && pendingExportDialog.isShowing()) {
                    performExportKami(pendingExportAppId, pendingExportKamiType, pendingExportFormat, pendingExportDialog);
                }
            } else {
                showToast("存储权限被拒绝，无法导出文件");
            }

            // 清除待处理的状态
            pendingExportAppId = 0;
            pendingExportKamiType = null;
            pendingExportFormat = null;
            pendingExportDialog = null;
        }
    }

    /**
     * 执行导出卡密（移除登录验证，只检查卡密类型）
     */
    private void performExportKami(int appId, String kamiType, String exportFormat,
                                   androidx.appcompat.app.AlertDialog dialog) {
        try {
            // 先检查该应用和卡密类型下是否有数据
            checkKamiTypeExists(appId, kamiType, new KamiTypeCheckCallback() {
                @Override
                public void onExists() {
                    // 有对应卡密数据，继续执行导出
                    performDirectExport(appId, kamiType, exportFormat, dialog);
                }

                @Override
                public void onNotExists() {
                    // 没有对应卡密数据
                    showToast("没有找到" + getKamiTypeDisplayName(kamiType) + "的卡密数据");
                }

                @Override
                public void onError(String errorMessage) {
                    // 检查过程中发生错误
                    showToast("检查卡密数据失败: " + errorMessage);
                    if (dialog != null && dialog.isShowing()) {
                        dialog.dismiss();
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "导出卡密时发生异常", e);
            showToast("导出卡密失败");
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
        }
    }

    /**
     * 检查指定应用和卡密类型下是否有数据
     */
    private void checkKamiTypeExists(int appId, String kamiType, KamiTypeCheckCallback callback) {
        if (isDestroyed) {
            callback.onError("Activity已销毁");
            return;
        }

        String CHECK_KAMI_TYPES_URL = AppConfig.CHECK_KAMI_TYPES_URL;
        String url = CHECK_KAMI_TYPES_URL + "?app_id=" + appId + "&type=" + kamiType;

        Log.d(TAG, "检查卡密类型URL: " + url);

        StringRequest checkRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.d(TAG, "卡密类型检查响应: " + InputValidator.sanitizeForLog(response));
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            boolean exists = jsonResponse.getBoolean("exists");

                            if (exists) {
                                callback.onExists();
                            } else {
                                callback.onNotExists();
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析卡密类型检查响应失败", e);
                            callback.onError("响应解析失败");
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "检查卡密类型网络错误", error);
                        String errorMessage = "网络连接错误";
                        if (error.networkResponse != null) {
                            errorMessage = "网络错误，状态码: " + error.networkResponse.statusCode;
                        }
                        callback.onError(errorMessage);
                    }
                });

        if (requestQueue == null) {
            requestQueue = Volley.newRequestQueue(this);
        }
        requestQueue.add(checkRequest);
    }

    /**
     * 获取卡密类型的显示名称
     */
    private String getKamiTypeDisplayName(String kamiType) {
        switch (kamiType) {
            case "all": return "全部类型";
            case "tyk": return "体验卡";
            case "hour": return "小时卡";
            case "day": return "天卡";
            case "week": return "周卡";
            case "month": return "月卡";
            case "year": return "年卡";
            case "permanent": return "永久卡";
            default: return kamiType;
        }
    }

    /**
     * 卡密类型检查回调接口
     */
    interface KamiTypeCheckCallback {
        void onExists();
        void onNotExists();
        void onError(String errorMessage);
    }

    /**
     * 直接执行导出操作（移除登录验证）
     */
    private void performDirectExport(int appId, String kamiType, String exportFormat,
                                     androidx.appcompat.app.AlertDialog dialog) {
        try {
            int userId = spManager.getUserId();
            if (userId <= 0) {
                showToast("用户信息无效");
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                }
                return;
            }

            showToast("正在导出卡密...");

            JSONObject jsonBody = new JSONObject();
            try {
                jsonBody.put("user_id", userId);
                jsonBody.put("app_id", appId);
                jsonBody.put("kami_type", kamiType);
                jsonBody.put("export_format", exportFormat);
            } catch (JSONException e) {
                Log.e(TAG, "创建导出卡密JSON请求体失败", e);
                showToast("数据格式错误");
                return;
            }

            String EXPORT_KAMI_URL = AppConfig.EXPORT_KAMI_URL;

            JsonObjectRequest exportRequest = new JsonObjectRequest(Request.Method.POST, EXPORT_KAMI_URL, jsonBody,
                    new Response.Listener<JSONObject>() {
                        @Override
                        public void onResponse(JSONObject response) {
                            Log.d(TAG, "导出卡密响应: " + InputValidator.sanitizeForLog(response.toString()));

                            try {
                                boolean success = response.getBoolean("success");
                                String message = response.getString("message");

                                if (success) {
                                    // 导出成功，保存文件到本地
                                    String fileContent = response.getString("file_content");
                                    String fileName = response.getString("file_name");
                                    int kamiCount = response.getInt("kami_count");

                                    saveFileToDevice(fileContent, fileName);
                                    showToast("成功导出 " + kamiCount + " 条卡密数据");
                                    if (dialog != null && dialog.isShowing()) {
                                        dialog.dismiss();
                                    }
                                } else {
                                    // 只显示错误消息，不进行登录跳转
                                    showToast("导出失败: " + message);
                                }

                            } catch (JSONException e) {
                                Log.e(TAG, "解析导出卡密响应失败", e);
                                showToast("响应解析失败");
                            }
                        }
                    },
                    new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            Log.e(TAG, "导出卡密网络错误", error);
                            String errorMessage = "网络请求失败";
                            if (error.networkResponse != null) {
                                int statusCode = error.networkResponse.statusCode;
                                errorMessage = "网络错误，状态码: " + statusCode;
                            }
                            showToast("导出失败: " + errorMessage);
                        }
                    }) {
                @Override
                public Map<String, String> getHeaders() {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/json");
                    headers.put("Accept", "application/json");
                    return headers;
                }
            };

            if (requestQueue == null) {
                requestQueue = Volley.newRequestQueue(this);
            }
            requestQueue.add(exportRequest);

        } catch (Exception e) {
            Log.e(TAG, "导出卡密时发生异常", e);
            showToast("导出卡密失败");
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
        }
    }

    /**
     * 导出前的用户凭证验证
     */
    private void verifyUserCredentialsBeforeExport(ExportCallback callback) {
        final String username = spManager.getUsername();
        final String password = spManager.getPassword();

        // 检查本地凭证是否完整
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            callback.onFailure("登录信息不完整，请重新登录");
            return;
        }

        Log.d(TAG, "导出前验证用户凭证，用户名: ***");

        StringRequest verifyRequest = new StringRequest(Request.Method.POST, AppConfig.LOGIN_URL,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.d(TAG, "导出验证响应: " + InputValidator.sanitizeForLog(response));
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            boolean success = jsonResponse.getBoolean("success");

                            if (!success) {
                                String message = jsonResponse.optString("message", "登录已过期");
                                callback.onFailure("登录已过期，请重新登录");
                            } else {
                                callback.onSuccess();
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "导出验证响应解析失败", e);
                            callback.onFailure("验证响应解析失败");
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "导出验证网络错误", error);
                        callback.onFailure("网络验证失败");
                    }
                }) {
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                params.put("username", username);
                params.put("password", password);
                return params;
            }

            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/x-www-form-urlencoded");
                return headers;
            }
        };

        if (requestQueue == null) {
            requestQueue = Volley.newRequestQueue(this);
        }
        requestQueue.add(verifyRequest);
    }

    /**
     * 验证成功后的实际导出操作
     */
    private void performExportAfterVerification(int appId, String kamiType, String exportFormat,
                                                androidx.appcompat.app.AlertDialog dialog) {
        try {
            int userId = spManager.getUserId();
            if (userId <= 0) {
                showToast("用户信息无效，请重新登录");
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                }
                directLogout();
                return;
            }

            showToast("正在导出卡密...");

            JSONObject jsonBody = new JSONObject();
            try {
                jsonBody.put("user_id", userId);
                jsonBody.put("app_id", appId);
                jsonBody.put("kami_type", kamiType);
                jsonBody.put("export_format", exportFormat);
            } catch (JSONException e) {
                Log.e(TAG, "创建导出卡密JSON请求体失败", e);
                showToast("数据格式错误");
                return;
            }

            String EXPORT_KAMI_URL = AppConfig.EXPORT_KAMI_URL;

            JsonObjectRequest exportRequest = new JsonObjectRequest(Request.Method.POST, EXPORT_KAMI_URL, jsonBody,
                    new Response.Listener<JSONObject>() {
                        @Override
                        public void onResponse(JSONObject response) {
                            Log.d(TAG, "导出卡密响应: " + InputValidator.sanitizeForLog(response.toString()));

                            try {
                                boolean success = response.getBoolean("success");
                                String message = response.getString("message");

                                if (success) {
                                    // 导出成功，保存文件到本地
                                    String fileContent = response.getString("file_content");
                                    String fileName = response.getString("file_name");
                                    int kamiCount = response.getInt("kami_count");

                                    saveFileToDevice(fileContent, fileName);
                                    showToast("成功导出 " + kamiCount + " 条卡密数据");
                                    if (dialog != null && dialog.isShowing()) {
                                        dialog.dismiss();
                                    }
                                } else {
                                    // 处理特定的错误消息
                                    if (message.contains("未登录") || message.contains("登录") || message.contains("session")) {
                                        showToast("登录已过期，请重新登录");
                                        directLogout();
                                    } else {
                                        showToast("导出失败: " + message);
                                    }
                                }

                            } catch (JSONException e) {
                                Log.e(TAG, "解析导出卡密响应失败", e);
                                showToast("响应解析失败");
                            }
                        }
                    },
                    new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            Log.e(TAG, "导出卡密网络错误", error);
                            String errorMessage = "网络请求失败";
                            if (error.networkResponse != null) {
                                int statusCode = error.networkResponse.statusCode;
                                errorMessage = "网络错误，状态码: " + statusCode;

                                // 处理认证错误
                                if (statusCode == 401 || statusCode == 403) {
                                    showToast("登录已过期，请重新登录");
                                    directLogout();
                                    return;
                                }
                            }
                            showToast("导出失败: " + errorMessage);
                        }
                    }) {
                @Override
                public Map<String, String> getHeaders() {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/json");
                    headers.put("Accept", "application/json");
                    return headers;
                }
            };

            if (requestQueue == null) {
                requestQueue = Volley.newRequestQueue(this);
            }
            requestQueue.add(exportRequest);

        } catch (Exception e) {
            Log.e(TAG, "导出卡密时发生异常", e);
            showToast("导出卡密失败");
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
        }
    }

    /**
     * 导出回调接口
     */
    interface ExportCallback {
        void onSuccess();
        void onFailure(String errorMessage);
    }

    /**
     * 保存文件到设备（适配Android 10+和Android 15）
     */
    private void saveFileToDevice(String base64Content, String fileName) {
        try {
            // 解码Base64内容
            byte[] fileBytes = Base64.decode(base64Content, Base64.DEFAULT);

            // 获取MIME类型
            String mimeType = getMimeType(fileName);

            // Android 10+ 使用MediaStore API保存到公共Downloads目录
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                String savedPath = saveFileToPublicDownloads(fileBytes, fileName, mimeType);
                if (savedPath != null) {
                    // 创建虚拟File对象用于分享功能
                    File virtualFile = new File(Environment.DIRECTORY_DOWNLOADS, fileName);
                    showFileSavedDialog(virtualFile, savedPath);
                }
            } else {
                // Android 9及以下使用传统方法
                File savedFile = saveFileUsingLegacyMethod(fileBytes, fileName);
                if (savedFile != null) {
                    showFileSavedDialog(savedFile, null);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "保存文件失败", e);
            showToast("保存文件失败: " + e.getMessage());
        }
    }

    /**
     * Android 10+ 使用 MediaStore API 保存文件到公共 Downloads 目录 - 返回虚拟文件路径用于显示
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private String saveFileToPublicDownloads(byte[] fileBytes, String fileName, String mimeType) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        // Android 10+ 需要标记为新文件
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Downloads.IS_PENDING, 1);
        }

        ContentResolver resolver = getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

        Uri fileUri = null;
        try {
            fileUri = resolver.insert(collection, values);
            if (fileUri == null) {
                Log.e(TAG, "无法创建文件URI");
                showToast("保存文件失败：无法创建文件");
                return null;
            }

            // 写入文件数据
            try (OutputStream out = resolver.openOutputStream(fileUri)) {
                if (out == null) {
                    throw new IOException("无法打开输出流");
                }
                out.write(fileBytes);
                out.flush();
            }

            // 更新文件状态为已完成
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(fileUri, values, null, null);
            }

            // 返回文件路径用于显示
            String savedPath = Environment.DIRECTORY_DOWNLOADS + File.separator + fileName;
            Log.d(TAG, "文件保存成功: " + savedPath);
            showToast("文件已保存到下载目录");

            return savedPath;

        } catch (Exception e) {
            Log.e(TAG, "使用MediaStore保存文件失败", e);
            showToast("保存文件失败: " + e.getMessage());

            // 如果MediaStore失败，回退到应用专属目录
            Log.w(TAG, "回退到应用专属目录保存");
            return saveFileUsingAppSpecificDirFallback(fileBytes, fileName);
        }
    }

    /**
     * 备用方案：保存到应用专属目录
     */
    private String saveFileUsingAppSpecificDirFallback(byte[] fileBytes, String fileName) {
        FileOutputStream fos = null;
        try {
            File downloadsDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "");

            if (!downloadsDir.exists()) {
                if (!downloadsDir.mkdirs()) {
                    Log.e(TAG, "无法创建下载目录");
                    showToast("无法创建下载目录");
                    return null;
                }
            }

            File file = new File(downloadsDir, fileName);
            fos = new FileOutputStream(file);
            fos.write(fileBytes);
            fos.flush();

            Log.d(TAG, "文件保存成功（应用专属目录）: " + file.getAbsolutePath());

            // 通知媒体扫描器，以便文件在媒体库中可见
            MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, null, null);

            showToast("文件已保存（应用专属目录）");
            return file.getAbsolutePath();

        } catch (Exception e) {
            Log.e(TAG, "保存文件失败", e);
            showToast("保存文件失败: " + e.getMessage());
            return null;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭文件流失败", e);
                }
            }
        }
    }

    /**
     * Android 9及以下使用传统方法保存文件 - 修改为返回File对象
     */
    private File saveFileUsingLegacyMethod(byte[] fileBytes, String fileName) {
        FileOutputStream fos = null;
        try {
            // 检查存储权限
            if (!checkStoragePermission()) {
                showToast("没有存储权限，无法保存文件");
                return null;
            }

            // 获取公共Downloads目录
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

            // 如果目录不存在，则创建
            if (!downloadsDir.exists()) {
                boolean created = downloadsDir.mkdirs();
                if (!created) {
                    Log.e(TAG, "无法创建下载目录");
                    showToast("无法创建下载目录");
                    return null;
                }
            }

            // 创建文件
            File file = new File(downloadsDir, fileName);

            // 写入文件
            fos = new FileOutputStream(file);
            fos.write(fileBytes);
            fos.flush();

            Log.d(TAG, "文件保存成功: " + file.getAbsolutePath());

            // 通知媒体扫描器
            MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, null, null);

            return file;

        } catch (Exception e) {
            Log.e(TAG, "使用传统方法保存文件失败", e);
            showToast("保存文件失败: " + e.getMessage());
            return null;
        } finally {
            // 确保关闭文件流
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭文件流失败", e);
                }
            }
        }
    }

    /**
     * 显示文件保存成功对话框（带虚拟路径支持）
     */
    private void showFileSavedDialog(File virtualFile, String actualPath) {
        try {
            androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
            builder.setTitle("保存成功");

            // 创建对话框内容
            String displayPath = (actualPath != null) ? actualPath : virtualFile.getAbsolutePath();
            String message = "文件已保存到：\n" + displayPath + "\n\n您可以在下载文件夹中找到此文件";

            builder.setMessage(message);

            // 添加打开下载文件夹按钮
            builder.setPositiveButton("打开下载文件夹", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    openDownloadsFolder();
                }
            });

            // 添加通过系统分享按钮（可选）
            builder.setNeutralButton("分享文件", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    shareExportedFile(virtualFile, actualPath);
                }
            });

            // 添加确定按钮
            builder.setNegativeButton("确定", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            });

            androidx.appcompat.app.AlertDialog dialog = builder.create();
            dialog.show();

            Button positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            Button neutralButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL);
            Button negativeButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE);

            if (positiveButton != null) {
                // Button color is set via XML layout
            }
            if (neutralButton != null) {
                neutralButton.setTextColor(Color.parseColor("#4CAF50"));
            }
            if (negativeButton != null) {
                negativeButton.setTextColor(Color.GRAY);
            }

        } catch (Exception e) {
            Log.e(TAG, "显示保存成功对话框时发生异常", e);
            showToast("文件已保存到下载目录");
        }
    }

    /**
     * 打开系统Downloads目录
     */
    private void openDownloadsFolder() {
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用MediaStore打开Downloads集合
                intent = new Intent(Intent.ACTION_VIEW);
                Uri downloadsUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                intent.setDataAndType(downloadsUri, "*/*");
            } else {
                // Android 9及以下使用传统方法
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) {
                    showToast("下载目录不存在");
                    return;
                }
                intent = new Intent(Intent.ACTION_VIEW);
                Uri uri = Uri.parse(downloadsDir.getAbsolutePath());
                intent.setDataAndType(uri, "*/*");
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                // 如果无法打开目录，尝试使用文件管理器
                openWithFileManager();
            }

        } catch (Exception e) {
            Log.e(TAG, "打开下载目录失败", e);
            showToast("无法打开下载目录");
        }
    }

    /**
     * 备用方案：使用文件管理器打开
     */
    private void openWithFileManager() {
        try {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadsDir.exists()) {
                showToast("下载目录不存在");
                return;
            }

            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            Uri uri = Uri.fromFile(downloadsDir);
            intent.setDataAndType(uri, "*/*");
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                showToast("未找到可用的文件管理器");
            }
        } catch (Exception e) {
            Log.e(TAG, "打开文件管理器失败", e);
            showToast("无法打开文件管理器");
        }
    }

    /**
     * 分享导出的文件
     */
    private void shareExportedFile(File virtualFile, String actualPath) {
        try {
            String fileName = virtualFile.getName();
            String mimeType = getMimeType(fileName);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType(mimeType);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "导出文件: " + fileName);
            shareIntent.putExtra(Intent.EXTRA_TEXT, "这是导出的卡密文件: " + fileName);

            // Android 10+ 需要从MediaStore获取URI
            Uri fileUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 通过文件名查询MediaStore中的文件URI
                String selection = MediaStore.Downloads.DISPLAY_NAME + "=?";
                String[] selectionArgs = new String[]{fileName};

                Cursor cursor = getContentResolver().query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Downloads._ID},
                        selection,
                        selectionArgs,
                        null
                );

                if (cursor != null && cursor.moveToFirst()) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID);
                    long id = cursor.getLong(idColumn);
                    fileUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                    cursor.close();
                } else {
                    // 如果找不到，使用FileProvider
                    shareIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
                    fileUri = FileProvider.getUriForFile(this,
                            getApplicationContext().getPackageName() + ".fileprovider", file);
                }
            } else {
                File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName);
                fileUri = Uri.fromFile(file);
            }

            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, "分享文件"));
        } catch (Exception e) {
            Log.e(TAG, "分享文件失败", e);
            showToast("分享失败: " + e.getMessage());
        }
    }

    /**
     * 旧方法（已替换为 openDownloadsFolder），保留以防意外调用
     */
    @Deprecated
    private void openFileLocation(File file) {
        // 改为打开下载文件夹
        openDownloadsFolder();
    }

    /**
     * 复制文本到剪贴板
     */
    private void copyToClipboard(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("文件路径", text);
            clipboard.setPrimaryClip(clip);
        } catch (Exception e) {
            Log.e(TAG, "复制到剪贴板失败", e);
        }
    }

    /**
     * 获取文件的MIME类型
     */
    private String getMimeType(String fileName) {
        try {
            String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
            switch (extension) {
                case "txt":
                    return "text/plain";
                case "xls":
                    return "application/vnd.ms-excel";
                case "xlsx":
                    return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                default:
                    return "*/*";
            }
        } catch (Exception e) {
            return "*/*";
        }
    }

    /**
     * 跳转到应用列表界面
     */
    private void navigateToAppList() {
        try {
            Intent intent = new Intent(MainActivity.this, AppListActivity.class);
            startActivity(intent);
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        } catch (Exception e) {
            Log.e(TAG, "跳转到应用列表失败", e);
            showToast("跳转失败");
        }
    }

    /**
     * 跳转到公告管理界面
     */
    private void navigateToNoticeManagement() {
        try {
            Intent intent = new Intent(MainActivity.this, NoticeManagementActivity.class);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "跳转到公告管理失败", e);
            showToast("跳转失败");
        }
    }

    /**
     * 显示添加卡密对话框
     */
    private void showAddKamiDialog() {
        try {
            androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
            builder.setTitle("生成卡密");

            LayoutInflater inflater = LayoutInflater.from(this);
            View dialogView = inflater.inflate(R.layout.dialog_add_kami, null);
            builder.setView(dialogView);

            // 获取对话框中的视图引用
            TextInputLayout tilKamiQuantity = dialogView.findViewById(R.id.tilKamiQuantity);
            TextInputLayout tilKamiLength = dialogView.findViewById(R.id.tilKamiLength);
            TextInputEditText etKamiQuantity = dialogView.findViewById(R.id.etKamiQuantity);
            TextInputEditText etKamiLength = dialogView.findViewById(R.id.etKamiLength);
            OptionSelector spinnerApps = dialogView.findViewById(R.id.spinnerApps);
            OptionSelector spinnerKamiType = dialogView.findViewById(R.id.spinnerKamiType);

            // 获取布局中的按钮
            Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
            Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);

            // 设置初始状态
            setKamiDialogLoadingState(tilKamiQuantity, tilKamiLength, etKamiQuantity, etKamiLength, spinnerApps, spinnerKamiType, true);
            btnConfirm.setEnabled(false);

            androidx.appcompat.app.AlertDialog dialog = builder.create();
            dialog.show();

            btnConfirm.setOnClickListener(v -> handleAddKamiSubmit(spinnerApps, spinnerKamiType, etKamiQuantity, etKamiLength, dialog));
            btnCancel.setOnClickListener(v -> dialog.dismiss());

            // 设置卡密类型下拉框
            setupKamiTypeSpinner(spinnerKamiType);
            // 设置应用下拉框占位符
            setupSpinnerWithPlaceholder(spinnerApps);

            // 加载用户应用数据
            loadUserAppsForKamiDialog(spinnerApps, spinnerKamiType, etKamiQuantity, etKamiLength, btnConfirm,
                    tilKamiQuantity, tilKamiLength, dialog);

        } catch (Exception e) {
            Log.e(TAG, "显示添加卡密对话框时发生异常", e);
            showToast("打开添加卡密对话框失败");
        }
    }

    /**
     * 设置卡密类型下拉框
     */
    private void setupKamiTypeSpinner(OptionSelector spinner) {
        String[] kamiTypes = {"体验卡(30分钟)", "小时卡(1小时)", "天卡(1天)", "周卡(1周)", "月卡(1月)", "年卡(1年)", "永久卡"};
        String[] typeValues = {"tyk", "hour", "day", "week", "month", "year", "permanent"};

        spinner.setOptionList(Arrays.asList(kamiTypes));
        spinner.setTag(typeValues); // 存储对应的值
    }

    /**
     * 设置卡密对话框的加载状态
     */
    private void setKamiDialogLoadingState(TextInputLayout tilQuantity, TextInputLayout tilLength,
                                           TextInputEditText etQuantity, TextInputEditText etLength,
                                           OptionSelector spinnerApps, OptionSelector spinnerType, boolean isLoading) {
        tilQuantity.setEnabled(!isLoading);
        tilLength.setEnabled(!isLoading);
        etQuantity.setEnabled(!isLoading);
        etLength.setEnabled(!isLoading);
        spinnerApps.setEnabled(!isLoading);
        spinnerType.setEnabled(!isLoading);

        if (isLoading) {
            tilQuantity.setHint("正在加载应用数据...");
            tilLength.setHint("请稍候...");
            etQuantity.setText("");
            etLength.setText("");
            tilQuantity.setBoxStrokeColor(Color.GRAY);
            tilLength.setBoxStrokeColor(Color.GRAY);
        } else {
            tilQuantity.setHint("生成数量");
            tilLength.setHint("卡密长度");
            tilQuantity.setBoxStrokeColor(Color.parseColor("#808080"));
            tilLength.setBoxStrokeColor(Color.parseColor("#808080"));
        }
    }

    /**
     * 为卡密对话框加载用户应用数据
     */
    private void loadUserAppsForKamiDialog(OptionSelector spinnerApps, OptionSelector spinnerType,
                                           TextInputEditText etQuantity, TextInputEditText etLength,
                                           Button positiveButton, TextInputLayout tilQuantity,
                                           TextInputLayout tilLength, androidx.appcompat.app.AlertDialog dialog) {
        int userId = spManager.getUserId();
        if (userId <= 0) {
            showToast("用户信息无效，请重新登录");
            dialog.dismiss();
            directLogout();
            return;
        }

        fetchUserAppsForKamiDialog(userId, spinnerApps, spinnerType, etQuantity, etLength,
                positiveButton, tilQuantity, tilLength, dialog);
    }

    /**
     * 为卡密对话框获取用户应用列表
     */
    private void fetchUserAppsForKamiDialog(int userId, final OptionSelector spinnerApps, final OptionSelector spinnerType,
                                            final TextInputEditText etQuantity, final TextInputEditText etLength,
                                            final Button positiveButton, final TextInputLayout tilQuantity,
                                            final TextInputLayout tilLength,
                                            final androidx.appcompat.app.AlertDialog dialog) {
        if (isDestroyed) {
            return;
        }

        String url = TokenAuthHelper.appendTokenToUrl(AppConfig.GET_APPS_URL + "?user_id=" + userId, MainActivity.this);
        Log.d(TAG, "请求卡密对话框应用列表URL: " + url);

        StringRequest appsRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        if (isDestroyed || !dialog.isShowing()) {
                            return;
                        }

                        Log.d(TAG, "卡密对话框应用列表响应: " + InputValidator.sanitizeForLog(response));
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            boolean success = jsonResponse.getBoolean("success");

                            if (success) {
                                JSONArray appsArray = jsonResponse.getJSONArray("data");
                                setupKamiDialogWithAppsData(appsArray, spinnerApps, spinnerType, etQuantity,
                                        etLength, positiveButton, tilQuantity, tilLength);
                            } else {
                                String message = jsonResponse.getString("message");
                                showToast("加载应用列表失败: " + message);
                                dialog.dismiss();
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析卡密对话框应用列表响应失败", e);
                            showToast("解析应用列表失败");
                            dialog.dismiss();
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (isDestroyed || !dialog.isShowing()) {
                            return;
                        }

                        Log.e(TAG, "获取卡密对话框应用列表网络错误", error);
                        String errorMessage = "网络连接错误";
                        if (error.networkResponse != null) {
                            errorMessage = "网络错误，状态码: " + error.networkResponse.statusCode;
                        }
                        showToast("加载应用列表失败: " + errorMessage);
                        dialog.dismiss();
                    }
                });

        if (requestQueue == null) {
            requestQueue = Volley.newRequestQueue(this);
        }
        requestQueue.add(appsRequest);
    }

    /**
     * 在卡密对话框中设置应用数据
     */
    private void setupKamiDialogWithAppsData(JSONArray appsArray, OptionSelector spinnerApps, OptionSelector spinnerType,
                                             TextInputEditText etQuantity, TextInputEditText etLength,
                                             Button positiveButton, TextInputLayout tilQuantity,
                                             TextInputLayout tilLength) {
        try {
            // 启用输入框和按钮
            setKamiDialogLoadingState(tilQuantity, tilLength, etQuantity, etLength, spinnerApps, spinnerType, false);

            // 启用确认按钮并设置颜色
            positiveButton.setEnabled(true);
            // Button color is set via XML layout

            // 解析应用数据并设置到OptionSelector
            List<AppItem> appList = parseAppsData(appsArray);
            if (appList.isEmpty()) {
                showToast("暂无应用，请先添加应用");
                return;
            }

            // 设置OptionSelector适配器
            List<String> appLabels = new ArrayList<>();
            for (AppItem a : appList) appLabels.add(a.getDisplayName());
            spinnerApps.setOptionList(appLabels, appList);
            spinnerApps.setSelection(0);

        } catch (Exception e) {
            Log.e(TAG, "设置卡密对话框应用数据时发生异常", e);
            showToast("设置应用数据失败");
        }
    }

    /**
     * 处理添加卡密提交
     */
    private void handleAddKamiSubmit(OptionSelector spinnerApps, OptionSelector spinnerType,
                                     TextInputEditText etQuantity, TextInputEditText etLength,
                                     androidx.appcompat.app.AlertDialog dialog) {
        AppItem selectedApp = (AppItem) spinnerApps.getSelectedItem();
        if (selectedApp == null) {
            showToast("请选择应用");
            return;
        }

        String quantityStr = etQuantity.getText().toString().trim();
        String lengthStr = etLength.getText().toString().trim();

        if (TextUtils.isEmpty(quantityStr)) {
            showToast("生成数量不能为空");
            return;
        }

        if (TextUtils.isEmpty(lengthStr)) {
            showToast("卡密长度不能为空");
            return;
        }

        int quantity = Integer.parseInt(quantityStr);
        int length = Integer.parseInt(lengthStr);

        if (quantity <= 0 || quantity > 100) {
            showToast("生成数量必须在1-100之间");
            return;
        }

        if (length < 10 || length > 20) {
            showToast("卡密长度必须在10-20位之间");
            return;
        }

        // 获取卡密类型值
        String[] typeValues = (String[]) spinnerType.getTag();
        int selectedPosition = spinnerType.getSelectedItemPosition();
        String type = typeValues[selectedPosition];

        // 提交生成卡密
        generateKami(selectedApp.getAppId(), quantity, type, length, dialog);
    }

    /**
     * 生成卡密 - 添加会员检查
     */
    private void generateKami(int appId, int quantity, String type, int length, androidx.appcompat.app.AlertDialog dialog) {
        try {
            int userId = spManager.getUserId();
            if (userId <= 0) {
                showToast("用户信息无效，请重新登录");
                directLogout();
                return;
            }

            // 检查会员状态和卡密数量限制
            checkMembershipAndGenerateKami(userId, appId, quantity, type, length, dialog);

        } catch (Exception e) {
            Log.e(TAG, "生成卡密时发生异常", e);
            showToast("生成卡密失败");
        }
    }

    /**
     * 检查会员状态和卡密数量限制
     */
    private void checkMembershipAndGenerateKami(int userId, int appId, int quantity, String type,
                                                int length, androidx.appcompat.app.AlertDialog dialog) {
        if (isDestroyed) {
            return;
        }

        String CHECK_MEMBERSHIP_URL = AppConfig.CHECK_MEMBERSHIP_URL;
        String url = TokenAuthHelper.appendTokenToUrl(CHECK_MEMBERSHIP_URL + "?user_id=" + userId + "&app_id=" + appId, MainActivity.this);

        Log.d(TAG, "检查会员状态URL: " + url);

        StringRequest checkRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        Log.d(TAG, "会员检查响应: " + InputValidator.sanitizeForLog(response));
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            boolean success = jsonResponse.getBoolean("success");

                            if (success) {
                                boolean canGenerate = jsonResponse.getBoolean("can_generate");
                                int currentCount = jsonResponse.getInt("current_count");
                                int maxAllowed = jsonResponse.getInt("max_allowed");
                                boolean isMember = jsonResponse.getBoolean("is_member");

                                if (canGenerate) {
                                    // 可以生成卡密，继续执行
                                    performGenerateKami(userId, appId, quantity, type, length, dialog);
                                } else {
                                    // 超过限制，显示会员开通提示
                                    showMembershipRequiredDialog(currentCount, maxAllowed, isMember, appId);
                                    if (dialog != null && dialog.isShowing()) {
                                        dialog.dismiss();
                                    }
                                }
                            } else {
                                String message = jsonResponse.getString("message");
                                showToast("检查会员状态失败: " + message);
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析会员检查响应失败", e);
                            showToast("会员检查响应解析失败");
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "检查会员状态网络错误", error);
                        // 网络错误时，为了用户体验，允许继续生成（服务器端会再次检查）
                        performGenerateKami(userId, appId, quantity, type, length, dialog);
                    }
                });

        if (requestQueue == null) {
            requestQueue = Volley.newRequestQueue(this);
        }
        requestQueue.add(checkRequest);
    }

    /**
     * 显示会员开通提示对话框
     */
    private void showMembershipRequiredDialog(int currentCount, int maxAllowed, boolean isMember, int appId) {
        try {
            androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
            builder.setTitle("会员开通提示");

            String message;
            if (isMember) {
                message = "您已经是会员，但当前应用下的卡密数量 (" + currentCount + ") 已超过会员限制 (" + maxAllowed + ")。\n\n" +
                        "请升级更高级别的会员套餐或联系管理员。";
            } else {
                message = "当前应用下的卡密数量 (" + currentCount + ") 已超过免费限制 (" + maxAllowed + ")。\n\n" +
                        "开通会员后可享受：\n" +
                        "• 更高的卡密生成数量限制\n" +
                        "• 更多高级功能\n" +
                        "• 优先技术支持\n\n" +
                        "是否立即开通会员？";
            }

            builder.setMessage(message);

            if (!isMember) {
                // 非会员显示开通按钮
                builder.setPositiveButton("开通会员", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openMembershipPage(appId);
                    }
                });
            }

            builder.setNegativeButton("知道了", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            });

            androidx.appcompat.app.AlertDialog dialog = builder.create();
            dialog.show();

            // 自定义按钮颜色
            if (!isMember) {
                Button positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
                if (positiveButton != null) {
                    // Button color is set via XML layout
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "显示会员开通提示对话框时发生异常", e);
            showToast("卡密数量已超过限制，请开通会员");
        }
    }

    /**
     * 打开会员开通页面 - 跳转到会员界面Activity
     */
    private void openMembershipPage(int appId) {
        try {
            Intent intent = new Intent(MainActivity.this, MembershipActivity.class);
            intent.putExtra("user_id", spManager.getUserId());
            intent.putExtra("app_id", appId);
            intent.putExtra("username", spManager.getUsername());
            startActivity(intent);

            // 可选：添加跳转动画
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);

        } catch (Exception e) {
            Log.e(TAG, "跳转到会员页面失败", e);
            showToast("无法打开会员页面，请稍后重试");

            // 备用方案：如果Activity不存在，回退到网页方式
            String membershipUrl = AppConfig.MEMBERSHIP_PAY_URL + "?app_id=" + appId + "&user_id=" + spManager.getUserId();
            try {
                Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(membershipUrl));
                startActivity(webIntent);
            } catch (Exception ex) {
                Log.e(TAG, "打开网页会员页面也失败", ex);
                showToast("会员功能暂时不可用");
            }
        }
    }

    /**
     * 实际执行卡密生成（修改后的方法，添加 generated_by_user_id）
     */
    private void performGenerateKami(int userId, int appId, int quantity, String type, int length,
                                     androidx.appcompat.app.AlertDialog dialog) {
        try {
            showToast("正在生成卡密...");

            JSONObject jsonBody = new JSONObject();
            try {
                jsonBody.put("user_id", userId);
                jsonBody.put("app_id", appId);
                jsonBody.put("quantity", quantity);
                jsonBody.put("type", type);
                jsonBody.put("length", length);
                jsonBody.put("generated_by_user_id", userId); // 添加生成者用户ID
            } catch (JSONException e) {
                Log.e(TAG, "创建生成卡密JSON请求体失败", e);
                showToast("数据格式错误");
                return;
            }

            String GENERATE_KAMI_URL = AppConfig.ADD_KAMI_URL;

            JsonObjectRequest generateKamiRequest = new JsonObjectRequest(Request.Method.POST, GENERATE_KAMI_URL, jsonBody,
                    new Response.Listener<JSONObject>() {
                        @Override
                        public void onResponse(JSONObject response) {
                            Log.d(TAG, "生成卡密响应: " + InputValidator.sanitizeForLog(response.toString()));

                            try {
                                boolean success = response.getBoolean("success");
                                String message = response.getString("message");

                                if (success) {
                                    showToast("卡密生成成功");
                                    if (dialog != null && dialog.isShowing()) {
                                        dialog.dismiss();
                                    }

                                    // 可选：显示生成的卡密列表
                                    if (response.has("kami_codes")) {
                                        JSONArray kamiCodes = response.getJSONArray("kami_codes");
                                        showGeneratedKamiList(kamiCodes);
                                    }

                                    // 刷新统计数据
                                    new Handler().postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            fetchStatisticsWithVolley();
                                        }
                                    }, 1000);

                                } else {
                                    // 处理服务器端的会员限制错误
                                    if (message.contains("会员") || message.contains("限制")) {
                                        showToast(message);
                                    } else {
                                        showToast("生成失败: " + message);
                                    }
                                }

                            } catch (JSONException e) {
                                Log.e(TAG, "解析生成卡密响应失败", e);
                                showToast("响应解析失败");
                            }
                        }
                    },
                    new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            Log.e(TAG, "生成卡密网络错误", error);
                            String errorMessage = "网络请求失败";
                            if (error.networkResponse != null) {
                                errorMessage = "网络错误，状态码: " + error.networkResponse.statusCode;
                            }
                            showToast("生成失败: " + errorMessage);
                        }
                    }) {
                @Override
                public Map<String, String> getHeaders() {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/json");
                    headers.put("Accept", "application/json");
                    return headers;
                }
            };

            if (requestQueue == null) {
                requestQueue = Volley.newRequestQueue(this);
            }
            requestQueue.add(generateKamiRequest);

        } catch (Exception e) {
            Log.e(TAG, "生成卡密时发生异常", e);
            showToast("生成卡密失败");
        }
    }

    /**
     * 显示生成的卡密列表
     */
    private void showGeneratedKamiList(JSONArray kamiCodes) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("生成的卡密：\n\n");

            for (int i = 0; i < kamiCodes.length(); i++) {
                sb.append(kamiCodes.getString(i));
                if (i < kamiCodes.length() - 1) {
                    sb.append("\n");
                }
            }

            androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
            builder.setTitle("卡密生成成功");
            builder.setMessage(sb.toString());
            builder.setPositiveButton("确定", null);
            builder.show();

        } catch (Exception e) {
            Log.e(TAG, "显示卡密列表时发生异常", e);
        }
    }

    /**
     * 显示添加公告对话框
     */
    private void showAddNoticeDialog() {
        try {
            // 创建对话框构建器
            androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
            builder.setTitle("添加公告");

            // 使用布局填充器加载对话框布局
            LayoutInflater inflater = LayoutInflater.from(this);
            View dialogView = inflater.inflate(R.layout.dialog_add_notice, null);
            builder.setView(dialogView);

            // 获取对话框中的视图引用
            TextInputLayout tilNoticeTitle = dialogView.findViewById(R.id.tilNoticeTitle);
            TextInputLayout tilNoticeContent = dialogView.findViewById(R.id.tilNoticeContent);
            TextInputEditText etNoticeTitle = dialogView.findViewById(R.id.etNoticeTitle);
            TextInputEditText etNoticeContent = dialogView.findViewById(R.id.etNoticeContent);
            OptionSelector spinnerApps = dialogView.findViewById(R.id.spinnerApps);

            // 获取布局中的按钮
            Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
            Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);

            // 设置初始状态：禁用输入框，显示加载状态
            setNoticeDialogLoadingState(tilNoticeTitle, tilNoticeContent, etNoticeTitle, etNoticeContent, spinnerApps, true);
            btnConfirm.setEnabled(false);

            // 创建并显示对话框
            androidx.appcompat.app.AlertDialog dialog = builder.create();
            dialog.show();

            btnConfirm.setOnClickListener(v -> handleAddNoticeSubmit(spinnerApps, etNoticeTitle, etNoticeContent, dialog));
            btnCancel.setOnClickListener(v -> dialog.dismiss());

            // 设置下拉框初始提示文本
            setupSpinnerWithPlaceholder(spinnerApps);

            // 显示对话框后，开始获取用户应用数据
            loadUserAppsForNoticeDialog(spinnerApps, etNoticeTitle, etNoticeContent, btnConfirm,
                    tilNoticeTitle, tilNoticeContent, dialog);

        } catch (Exception e) {
            Log.e(TAG, "显示添加公告对话框时发生异常", e);
            showToast("打开添加公告对话框失败");
        }
    }

    /**
     * 设置公告对话框的加载状态
     */
    private void setNoticeDialogLoadingState(TextInputLayout tilNoticeTitle, TextInputLayout tilNoticeContent,
                                             TextInputEditText etNoticeTitle, TextInputEditText etNoticeContent,
                                             OptionSelector spinnerApps, boolean isLoading) {
        tilNoticeTitle.setEnabled(!isLoading);
        tilNoticeContent.setEnabled(!isLoading);
        etNoticeTitle.setEnabled(!isLoading);
        etNoticeContent.setEnabled(!isLoading);
        spinnerApps.setEnabled(!isLoading);

        if (isLoading) {
            // 设置加载中的提示文本
            tilNoticeTitle.setHint("正在加载应用数据...");
            tilNoticeContent.setHint("请稍候...");
            etNoticeTitle.setText("");
            etNoticeContent.setText("");

            // 设置加载中的样式
            tilNoticeTitle.setBoxStrokeColor(Color.GRAY);
            tilNoticeContent.setBoxStrokeColor(Color.GRAY);
        } else {
            tilNoticeTitle.setHint("公告标题");
            tilNoticeContent.setHint("公告内容");

            // 恢复正常样式
            tilNoticeTitle.setBoxStrokeColor(Color.parseColor("#808080"));
            tilNoticeContent.setBoxStrokeColor(Color.parseColor("#808080"));
        }
    }

    /**
     * 为公告对话框加载用户应用数据
     */
    private void loadUserAppsForNoticeDialog(OptionSelector spinnerApps, TextInputEditText etNoticeTitle,
                                             TextInputEditText etNoticeContent, Button positiveButton,
                                             TextInputLayout tilNoticeTitle, TextInputLayout tilNoticeContent,
                                             androidx.appcompat.app.AlertDialog dialog) {
        // 获取用户ID
        int userId = spManager.getUserId();
        if (userId <= 0) {
            showToast("用户信息无效，请重新登录");
            dialog.dismiss();
            directLogout();
            return;
        }

        // 获取用户应用列表
        fetchUserAppsForNoticeDialog(userId, spinnerApps, etNoticeTitle, etNoticeContent,
                positiveButton, tilNoticeTitle, tilNoticeContent, dialog);
    }

    /**
     * 为公告对话框获取用户应用列表
     */
    private void fetchUserAppsForNoticeDialog(int userId, final OptionSelector spinnerApps,
                                              final TextInputEditText etNoticeTitle, final TextInputEditText etNoticeContent,
                                              final Button positiveButton, final TextInputLayout tilNoticeTitle,
                                              final TextInputLayout tilNoticeContent,
                                              final androidx.appcompat.app.AlertDialog dialog) {
        if (isDestroyed) {
            return;
        }

        String url = TokenAuthHelper.appendTokenToUrl(AppConfig.GET_APPS_URL + "?user_id=" + userId, MainActivity.this);
        Log.d(TAG, "请求公告对话框应用列表URL: " + url);

        StringRequest appsRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        if (isDestroyed || !dialog.isShowing()) {
                            return;
                        }

                        Log.d(TAG, "公告对话框应用列表响应: " + InputValidator.sanitizeForLog(response));
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            boolean success = jsonResponse.getBoolean("success");

                            if (success) {
                                JSONArray appsArray = jsonResponse.getJSONArray("data");
                                // 在对话框中设置应用数据
                                setupNoticeDialogWithAppsData(appsArray, spinnerApps, etNoticeTitle,
                                        etNoticeContent, positiveButton,
                                        tilNoticeTitle, tilNoticeContent);
                            } else {
                                String message = jsonResponse.getString("message");
                                showToast("加载应用列表失败: " + message);
                                dialog.dismiss();
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析公告对话框应用列表响应失败", e);
                            showToast("解析应用列表失败");
                            dialog.dismiss();
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (isDestroyed || !dialog.isShowing()) {
                            return;
                        }

                        Log.e(TAG, "获取公告对话框应用列表网络错误", error);
                        String errorMessage = "网络连接错误";
                        if (error.networkResponse != null) {
                            errorMessage = "网络错误，状态码: " + error.networkResponse.statusCode;
                        }
                        showToast("加载应用列表失败: " + errorMessage);
                        dialog.dismiss();
                    }
                });

        if (requestQueue == null) {
            requestQueue = Volley.newRequestQueue(this);
        }
        requestQueue.add(appsRequest);
    }

    /**
     * 在公告对话框中设置应用数据 - 添加预填充功能
     */
    private void setupNoticeDialogWithAppsData(JSONArray appsArray, OptionSelector spinnerApps,
                                               TextInputEditText etNoticeTitle, TextInputEditText etNoticeContent,
                                               Button positiveButton, TextInputLayout tilNoticeTitle,
                                               TextInputLayout tilNoticeContent) {
        try {
            // 启用输入框和按钮
            setNoticeDialogLoadingState(tilNoticeTitle, tilNoticeContent, etNoticeTitle, etNoticeContent, spinnerApps, false);

            // 启用确认按钮并设置颜色
            positiveButton.setEnabled(true);
            // Button color is set via XML layout

            // 解析应用数据并设置到OptionSelector
            List<AppItem> appList = parseAppsData(appsArray);
            if (appList.isEmpty()) {
                showToast("暂无应用，请先添加应用");
                return;
            }

            // 设置OptionSelector适配器
            List<String> appLabels = new ArrayList<>();
            for (AppItem a : appList) appLabels.add(a.getDisplayName());
            spinnerApps.setOptionList(appLabels, appList);

            // 设置默认选择第一个（最小的APPID）
            spinnerApps.setSelection(0);

            // 预填充第一个应用的公告数据
            updateNoticeFields(spinnerApps, etNoticeTitle, etNoticeContent);

            // 设置选择监听器
            spinnerApps.setOnItemSelectedListener(position -> {
                // 当选择应用时，更新公告字段
                updateNoticeFields(spinnerApps, etNoticeTitle, etNoticeContent);
            });

        } catch (Exception e) {
            Log.e(TAG, "设置公告对话框应用数据时发生异常", e);
            showToast("设置应用数据失败");
        }
    }

    /**
     * 更新公告字段内容 - 从服务器获取选中应用的公告信息
     */
    private void updateNoticeFields(OptionSelector spinner, TextInputEditText etNoticeTitle, TextInputEditText etNoticeContent) {
        AppItem selectedApp = (AppItem) spinner.getSelectedItem();
        if (selectedApp != null) {
            // 获取选中应用的公告信息
            fetchNoticeByAppId(selectedApp.getAppId(), etNoticeTitle, etNoticeContent);
        }
    }

    /**
     * 根据应用ID获取公告信息
     */
    private void fetchNoticeByAppId(int appId, TextInputEditText etNoticeTitle, TextInputEditText etNoticeContent) {
        if (isDestroyed) {
            return;
        }

        String userId = String.valueOf(spManager.getUserId());
        String url = AppConfig.GET_GONGGAO_URL + "?app_id=" + appId + "&user_id=" + userId;
        Log.d(TAG, "请求公告信息URL: " + url);

        StringRequest noticeRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        if (isDestroyed) {
                            return;
                        }

                        Log.d(TAG, "公告信息响应: " + InputValidator.sanitizeForLog(response));
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            boolean success = jsonResponse.getBoolean("success");

                            if (success) {
                                JSONObject data = jsonResponse.getJSONObject("data");
                                String title = data.getString("title");
                                String content = data.getString("content");

                                // 更新UI
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        etNoticeTitle.setText(title);
                                        etNoticeContent.setText(content);
                                    }
                                });
                            } else {
                                // 没有公告数据，清空字段
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        etNoticeTitle.setText("");
                                        etNoticeContent.setText("");
                                    }
                                });
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析公告信息响应失败", e);
                            // 解析失败时清空字段
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    etNoticeTitle.setText("");
                                    etNoticeContent.setText("");
                                }
                            });
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (isDestroyed) {
                            return;
                        }

                        Log.e(TAG, "获取公告信息网络错误", error);
                        // 网络错误时清空字段
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                etNoticeTitle.setText("");
                                etNoticeContent.setText("");
                            }
                        });
                    }
                });

        if (requestQueue == null) {
            requestQueue = Volley.newRequestQueue(this);
        }
        requestQueue.add(noticeRequest);
    }

    /**
     * 处理添加公告提交
     */
    private void handleAddNoticeSubmit(OptionSelector spinner, TextInputEditText etNoticeTitle,
                                       TextInputEditText etNoticeContent, androidx.appcompat.app.AlertDialog dialog) {
        AppItem selectedApp = (AppItem) spinner.getSelectedItem();
        if (selectedApp == null) {
            showToast("请选择应用");
            return;
        }

        String noticeTitle = etNoticeTitle.getText().toString().trim();
        String noticeContent = etNoticeContent.getText().toString().trim();

        if (TextUtils.isEmpty(noticeTitle)) {
            showToast("公告标题不能为空");
            return;
        }

        if (TextUtils.isEmpty(noticeContent)) {
            showToast("公告内容不能为空");
            return;
        }

        // 提交添加公告
        addNotice(selectedApp.getAppId(), noticeTitle, noticeContent, dialog);
    }

    /**
     * 添加或更新公告（服务端已实现存在则更新、不存在则插入）
     */
    private void addNotice(int appId, String title, String content, androidx.appcompat.app.AlertDialog dialog) {
        try {
            int userId = spManager.getUserId();
            if (userId <= 0) {
                showToast("用户信息无效，请重新登录");
                directLogout();
                return;
            }

            // 显示加载状态
            showToast("正在保存公告...");

            JSONObject jsonBody = new JSONObject();
            try {
                jsonBody.put("user_id", userId);
                jsonBody.put("app_id", appId);
                jsonBody.put("title", title);
                jsonBody.put("content", content);
            } catch (JSONException e) {
                Log.e(TAG, "创建添加公告JSON请求体失败", e);
                showToast("数据格式错误");
                return;
            }

            String ADD_NOTICE_URL = AppConfig.ADD_NOTICE_URL;

            JsonObjectRequest addNoticeRequest = new JsonObjectRequest(Request.Method.POST, ADD_NOTICE_URL, jsonBody,
                    new Response.Listener<JSONObject>() {
                        @Override
                        public void onResponse(JSONObject response) {
                            Log.d(TAG, "添加/更新公告响应: " + InputValidator.sanitizeForLog(response.toString()));

                            try {
                                boolean success = response.getBoolean("success");
                                String message = response.getString("message");

                                if (success) {
                                    showToast("公告保存成功");
                                    if (dialog != null && dialog.isShowing()) {
                                        dialog.dismiss();
                                    }
                                    // 可选：刷新统计数据中的公告数量
                                    fetchStatisticsWithVolley();
                                } else {
                                    showToast("保存失败: " + message);
                                }

                            } catch (JSONException e) {
                                Log.e(TAG, "解析添加公告响应失败", e);
                                showToast("响应解析失败");
                            }
                        }
                    },
                    new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            Log.e(TAG, "添加公告网络错误", error);
                            String errorMessage = "网络请求失败";
                            if (error.networkResponse != null) {
                                errorMessage = "网络错误，状态码: " + error.networkResponse.statusCode;
                            }
                            showToast("保存失败: " + errorMessage);
                        }
                    }) {
                @Override
                public Map<String, String> getHeaders() {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/json");
                    headers.put("Accept", "application/json");
                    return headers;
                }
            };

            if (requestQueue == null) {
                requestQueue = Volley.newRequestQueue(this);
            }
            requestQueue.add(addNoticeRequest);

        } catch (Exception e) {
            Log.e(TAG, "添加公告时发生异常", e);
            showToast("保存公告失败");
        }
    }

    /**
     * 显示添加应用对话框
     */
    private void showAddAppDialog() {
        try {
            // 创建对话框构建器
            androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
            builder.setTitle("添加应用");

            // 使用布局填充器加载与登录界面相同的布局
            LayoutInflater inflater = LayoutInflater.from(this);
            View dialogView = inflater.inflate(R.layout.dialog_add_app, null);
            builder.setView(dialogView);

            // 获取输入框引用
            TextInputEditText etAppName = dialogView.findViewById(R.id.etAppName);
            TextInputEditText etAppDescription = dialogView.findViewById(R.id.etAppDescription);

            // 创建并显示对话框
            androidx.appcompat.app.AlertDialog dialog = builder.create();
            dialog.show();

            // 获取布局中的按钮
            Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
            Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);

            btnCancel.setOnClickListener(v -> dialog.dismiss());
            btnConfirm.setOnClickListener(v -> {
                String appName = etAppName.getText().toString().trim();
                String appDescription = etAppDescription.getText().toString().trim();

                if (TextUtils.isEmpty(appName)) {
                    showToast("应用名称不能为空");
                    return;
                }

                if (TextUtils.isEmpty(appDescription)) {
                    appDescription = "";
                }

                dialog.dismiss();
                handleAddApp(appName, appDescription);
            });

        } catch (Exception e) {
            Log.e(TAG, "显示添加应用对话框时发生异常", e);
            showToast("打开对话框失败");
        }
    }

    /**
     * 处理添加应用逻辑 - 修改描述验证
     */
    private void handleAddApp(String appName, String appDescription) {
        try {
            Log.d(TAG, "开始添加应用 - 名称: " + appName + ", 描述: " + appDescription);

            // 显示加载状态
            showToast("正在添加应用...");

            // 获取用户ID
            int userId = spManager.getUserId();
            if (userId <= 0) {
                showToast("用户信息无效，请重新登录");
                directLogout();
                return;
            }

            // 如果描述为空，设置为空字符串
            if (TextUtils.isEmpty(appDescription)) {
                appDescription = "";
            }

            // 创建请求队列（如果不存在）
            if (requestQueue == null) {
                requestQueue = Volley.newRequestQueue(this);
            }

            // 创建JSON请求体
            JSONObject jsonBody = new JSONObject();
            try {
                jsonBody.put("user_id", userId);
                jsonBody.put("app_name", appName);
                jsonBody.put("app_desc", appDescription);
            } catch (JSONException e) {
                Log.e(TAG, "创建JSON请求体失败", e);
                showToast("数据格式错误");
                return;
            }

            // 创建请求
            JsonObjectRequest addAppRequest = new JsonObjectRequest(Request.Method.POST, TokenAuthHelper.appendTokenToUrl(AppConfig.ADD_APP_URL, MainActivity.this), jsonBody,
                    new Response.Listener<JSONObject>() {
                        @Override
                        public void onResponse(JSONObject response) {
                            Log.d(TAG, "添加应用响应: " + InputValidator.sanitizeForLog(response.toString()));

                            try {
                                boolean success = response.getBoolean("success");
                                String message = response.getString("message");

                                if (success) {
                                    // 添加成功
                                    JSONObject data = response.getJSONObject("data");
                                    int appId = data.getInt("app_id");
                                    String appName = data.getString("app_name");

                                    Log.d(TAG, "应用添加成功，应用ID: " + appId);
                                    showToast("应用 '" + appName + "' 添加成功！");

                                    // 可选：刷新统计数据
                                    new Handler().postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            fetchStatisticsWithVolley();
                                        }
                                    }, 1000);

                                } else {
                                    // 添加失败
                                    Log.w(TAG, "添加应用失败: " + message);
                                    showToast("添加失败: " + message);
                                }

                            } catch (JSONException e) {
                                Log.e(TAG, "解析添加应用响应失败", e);
                                showToast("响应解析失败");
                            }
                        }
                    },
                    new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            Log.e(TAG, "添加应用网络错误", error);

                            String errorMessage = "网络请求失败";
                            if (error.networkResponse != null) {
                                int statusCode = error.networkResponse.statusCode;
                                errorMessage = "网络错误，状态码: " + statusCode;
                            } else if (error.getMessage() != null) {
                                errorMessage = error.getMessage();
                            }

                            showToast("添加失败: " + errorMessage);
                        }
                    }) {
                @Override
                public Map<String, String> getHeaders() {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/json");
                    headers.put("Accept", "application/json");
                    return headers;
                }

                @Override
                public String getBodyContentType() {
                    return "application/json; charset=utf-8";
                }
            };

            // 添加到请求队列
            requestQueue.add(addAppRequest);

        } catch (Exception e) {
            Log.e(TAG, "处理添加应用时发生异常", e);
            showToast("添加应用失败: " + e.getMessage());
        }
    }

    /**
     * 显示编辑应用对话框
     */
    private void showEditAppDialog() {
        try {
            // 先显示对话框，再加载数据
            showEditAppDialogWithLoading();

        } catch (Exception e) {
            Log.e(TAG, "显示编辑应用对话框时发生异常", e);
            showToast("打开编辑对话框失败");
        }
    }

    /**
     * 显示正在加载的编辑应用对话框
     */
    private void showEditAppDialogWithLoading() {
        try {
            // 创建对话框构建器
            androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
            builder.setTitle("编辑应用");

            // 使用布局填充器加载编辑应用对话框布局
            LayoutInflater inflater = LayoutInflater.from(this);
            View dialogView = inflater.inflate(R.layout.dialog_edit_app, null);
            builder.setView(dialogView);

            // 获取对话框中的视图引用
            TextInputLayout tilAppName = dialogView.findViewById(R.id.tilAppName);
            TextInputLayout tilAppDescription = dialogView.findViewById(R.id.tilAppDescription);
            TextInputEditText etAppName = dialogView.findViewById(R.id.etAppName);
            TextInputEditText etAppDescription = dialogView.findViewById(R.id.etAppDescription);
            OptionSelector spinnerApps = dialogView.findViewById(R.id.spinnerApps);

            // 获取布局中的按钮
            Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
            Button btnConfirm = dialogView.findViewById(R.id.btn_confirm);

            // 设置初始状态：禁用输入框和按钮，显示加载状态
            setEditDialogLoadingState(tilAppName, tilAppDescription, etAppName, etAppDescription, spinnerApps, true);
            btnConfirm.setEnabled(false);

            // 创建并显示对话框
            androidx.appcompat.app.AlertDialog dialog = builder.create();
            dialog.show();

            // 设置按钮点击监听器
            btnConfirm.setOnClickListener(v -> {
                handleEditAppSubmit(spinnerApps, etAppName, etAppDescription, dialog);
            });

            btnCancel.setOnClickListener(v -> dialog.dismiss());

            // 设置下拉框初始提示文本
            setupSpinnerWithPlaceholder(spinnerApps);

            // 显示对话框后，开始获取用户应用数据
            loadUserAppsInDialog(spinnerApps, etAppName, etAppDescription, btnConfirm,
                    tilAppName, tilAppDescription, dialog);

        } catch (Exception e) {
            Log.e(TAG, "显示编辑应用对话框时发生异常", e);
            showToast("打开编辑对话框失败");
        }
    }

    /**
     * 设置下拉框的占位符
     */
    private void setupSpinnerWithPlaceholder(OptionSelector spinner) {
        spinner.setPlaceholderText("请选择应用");
    }

    /**
     * 在对话框中加载用户应用数据
     */
    private void loadUserAppsInDialog(OptionSelector spinnerApps, TextInputEditText etAppName,
                                      TextInputEditText etAppDescription, Button positiveButton,
                                      TextInputLayout tilAppName, TextInputLayout tilAppDescription,
                                      androidx.appcompat.app.AlertDialog dialog) {
        // 获取用户ID
        int userId = spManager.getUserId();
        if (userId <= 0) {
            showToast("用户信息无效，请重新登录");
            dialog.dismiss();
            directLogout();
            return;
        }

        // 获取用户应用列表
        fetchUserAppsForDialog(userId, spinnerApps, etAppName, etAppDescription,
                positiveButton, tilAppName, tilAppDescription, dialog);
    }

    /**
     * 为对话框获取用户应用列表
     */
    private void fetchUserAppsForDialog(int userId, final OptionSelector spinnerApps,
                                        final TextInputEditText etAppName, final TextInputEditText etAppDescription,
                                        final Button positiveButton, final TextInputLayout tilAppName,
                                        final TextInputLayout tilAppDescription,
                                        final androidx.appcompat.app.AlertDialog dialog) {
        if (isDestroyed) {
            return;
        }

        String url = TokenAuthHelper.appendTokenToUrl(AppConfig.GET_APPS_URL + "?user_id=" + userId, MainActivity.this);
        Log.d(TAG, "请求应用列表URL: " + url);

        StringRequest appsRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        if (isDestroyed || !dialog.isShowing()) {
                            return;
                        }

                        Log.d(TAG, "应用列表响应: " + InputValidator.sanitizeForLog(response));
                        try {
                            JSONObject jsonResponse = new JSONObject(response);
                            boolean success = jsonResponse.getBoolean("success");

                            if (success) {
                                JSONArray appsArray = jsonResponse.getJSONArray("data");
                                // 在对话框中设置应用数据
                                setupDialogWithAppsData(appsArray, spinnerApps, etAppName,
                                        etAppDescription, positiveButton,
                                        tilAppName, tilAppDescription);
                            } else {
                                String message = jsonResponse.getString("message");
                                showToast("加载应用列表失败: " + message);
                                dialog.dismiss();
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "解析应用列表响应失败", e);
                            showToast("解析应用列表失败");
                            dialog.dismiss();
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        if (isDestroyed || !dialog.isShowing()) {
                            return;
                        }

                        Log.e(TAG, "获取应用列表网络错误", error);
                        String errorMessage = "网络连接错误";
                        if (error.networkResponse != null) {
                            errorMessage = "网络错误，状态码: " + error.networkResponse.statusCode;
                        }
                        showToast("加载应用列表失败: " + errorMessage);
                        dialog.dismiss();
                    }
                });

        if (requestQueue == null) {
            requestQueue = Volley.newRequestQueue(this);
        }
        requestQueue.add(appsRequest);
    }

    /**
     * 设置编辑对话框的加载状态
     */
    private void setEditDialogLoadingState(TextInputLayout tilAppName, TextInputLayout tilAppDescription,
                                           TextInputEditText etAppName, TextInputEditText etAppDescription,
                                           OptionSelector spinnerApps, boolean isLoading) {
        tilAppName.setEnabled(!isLoading);
        tilAppDescription.setEnabled(!isLoading);
        etAppName.setEnabled(!isLoading);
        etAppDescription.setEnabled(!isLoading);
        spinnerApps.setEnabled(!isLoading);

        if (isLoading) {
            // 设置加载中的提示文本
            tilAppName.setHint("正在加载应用数据...");
            tilAppDescription.setHint("请稍候...");
            etAppName.setText("");
            etAppDescription.setText("");

            // 设置加载中的样式
            tilAppName.setBoxStrokeColor(Color.GRAY);
            tilAppDescription.setBoxStrokeColor(Color.GRAY);
        } else {
            tilAppName.setHint("应用名称");
            tilAppDescription.setHint("应用描述");

            // 恢复正常样式
            tilAppName.setBoxStrokeColor(Color.parseColor("#808080"));
            tilAppDescription.setBoxStrokeColor(Color.parseColor("#808080"));
        }
    }

    /**
     * 在对话框中设置应用数据
     */
    private void setupDialogWithAppsData(JSONArray appsArray, OptionSelector spinnerApps,
                                         TextInputEditText etAppName, TextInputEditText etAppDescription,
                                         Button positiveButton, TextInputLayout tilAppName,
                                         TextInputLayout tilAppDescription) {
        try {
            // 启用输入框和按钮
            setEditDialogLoadingState(tilAppName, tilAppDescription, etAppName, etAppDescription, spinnerApps, false);

            // 启用确认按钮
            positiveButton.setEnabled(true);

            // 解析应用数据并设置到OptionSelector
            List<AppItem> appList = parseAppsData(appsArray);
            if (appList.isEmpty()) {
                showToast("暂无应用可编辑");
                return;
            }

            // 设置OptionSelector适配器
            List<String> appLabels = new ArrayList<>();
            for (AppItem a : appList) appLabels.add(a.getDisplayName());
            spinnerApps.setOptionList(appLabels, appList);

            // 设置默认选择第一个（最小的APPID）
            spinnerApps.setSelection(0);
            updateEditFields(spinnerApps, etAppName, etAppDescription);

            // 设置选择监听器
            spinnerApps.setOnItemSelectedListener(position -> {
                updateEditFields(spinnerApps, etAppName, etAppDescription);

                // 获取当前选中的APPID
                AppItem selectedApp = (AppItem) spinnerApps.getSelectedItem();
                if (selectedApp != null) {
                    int currentAppId = selectedApp.getAppId();
                    Log.d(TAG, "当前选中的APPID: " + currentAppId);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "设置对话框应用数据时发生异常", e);
            showToast("设置应用数据失败");
        }
    }

    /**
     * 解析应用数据
     */
    private List<AppItem> parseAppsData(JSONArray appsArray) throws JSONException {
        List<AppItem> appList = new ArrayList<>();
        for (int i = 0; i < appsArray.length(); i++) {
            JSONObject appObject = appsArray.getJSONObject(i);
            int appId = appObject.getInt("app_id");
            String appName = appObject.getString("app_name");
            String appDesc = appObject.getString("app_desc");
            appList.add(new AppItem(appId, appName, appDesc));
        }
        return appList;
    }

    /**
     * 处理编辑应用提交 - 修改描述验证
     */
    private void handleEditAppSubmit(OptionSelector spinner, TextInputEditText etAppName,
                                     TextInputEditText etAppDescription, androidx.appcompat.app.AlertDialog dialog) {
        AppItem selectedApp = (AppItem) spinner.getSelectedItem();
        if (selectedApp == null) {
            showToast("请选择要编辑的应用");
            return;
        }

        String newAppName = etAppName.getText().toString().trim();
        String newAppDescription = etAppDescription.getText().toString().trim();

        if (TextUtils.isEmpty(newAppName)) {
            showToast("应用名称不能为空");
            return;
        }

        // 应用描述可以为空，不再进行非空验证
        if (TextUtils.isEmpty(newAppDescription)) {
            newAppDescription = "";
        }

        // 如果没有修改，直接关闭对话框
        if (newAppName.equals(selectedApp.getAppName()) && newAppDescription.equals(selectedApp.getAppDesc())) {
            showToast("应用信息未更改");
            dialog.dismiss();
            return;
        }

        // 提交修改
        updateAppInfo(selectedApp.getAppId(), newAppName, newAppDescription, dialog);
    }

    /**
     * 更新应用信息
     */
    private void updateAppInfo(int appId, String appName, String appDesc, androidx.appcompat.app.AlertDialog dialog) {
        try {
            int userId = spManager.getUserId();
            if (userId <= 0) {
                showToast("用户信息无效，请重新登录");
                directLogout();
                return;
            }

            JSONObject jsonBody = new JSONObject();
            try {
                jsonBody.put("user_id", userId);
                jsonBody.put("app_id", appId);
                jsonBody.put("app_name", appName);
                jsonBody.put("app_desc", appDesc);
            } catch (JSONException e) {
                Log.e(TAG, "创建更新应用JSON请求体失败", e);
                showToast("数据格式错误");
                return;
            }

            JsonObjectRequest updateRequest = new JsonObjectRequest(Request.Method.POST, TokenAuthHelper.appendTokenToUrl(AppConfig.UPDATE_APP_URL, MainActivity.this), jsonBody,
                    new Response.Listener<JSONObject>() {
                        @Override
                        public void onResponse(JSONObject response) {
                            Log.d(TAG, "更新应用响应: " + InputValidator.sanitizeForLog(response.toString()));

                            try {
                                boolean success = response.getBoolean("success");
                                String message = response.getString("message");

                                if (success) {
                                    showToast("应用更新成功");
                                    dialog.dismiss();

                                    // 可选：刷新统计数据
                                    new Handler().postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            fetchStatisticsWithVolley();
                                        }
                                    }, 1000);

                                } else {
                                    showToast("更新失败: " + message);
                                }

                            } catch (JSONException e) {
                                Log.e(TAG, "解析更新应用响应失败", e);
                                showToast("响应解析失败");
                            }
                        }
                    },
                    new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            Log.e(TAG, "更新应用网络错误", error);
                            String errorMessage = "网络请求失败";
                            if (error.networkResponse != null) {
                                errorMessage = "网络错误，状态码: " + error.networkResponse.statusCode;
                            }
                            showToast("更新失败: " + errorMessage);
                        }
                    }) {
                @Override
                public Map<String, String> getHeaders() {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Type", "application/json");
                    headers.put("Accept", "application/json");
                    return headers;
                }
            };

            if (requestQueue == null) {
                requestQueue = Volley.newRequestQueue(this);
            }
            requestQueue.add(updateRequest);

        } catch (Exception e) {
            Log.e(TAG, "更新应用信息时发生异常", e);
            showToast("更新应用失败");
        }
    }

    /**
     * 应用项数据类
     */
    public static class AppItem {
        private int appId;
        private String appName;
        private String appDesc;
        private String createTime;
        // 用于保存导出参数，以便在权限授予后使用
        private int pendingExportAppId;
        private String pendingExportKamiType;
        private String pendingExportFormat;
        private androidx.appcompat.app.AlertDialog pendingExportDialog;

        public AppItem(int appId, String appName, String appDesc) {
            this.appId = appId;
            this.appName = appName;
            this.appDesc = appDesc;
        }

        public int getAppId() { return appId; }
        public String getAppName() { return appName; }
        public String getAppDesc() { return appDesc; }
        public String getCreateTime() { return createTime; }
        public void setCreateTime(String createTime) { this.createTime = createTime; }

        // 用于OptionSelector显示
        public String getDisplayName() {
            return appName + " - (" + appId + ")";
        }

        @Override
        public String toString() {
            return getDisplayName();
        }
    }

    /**
     * 更新编辑框内容
     */
    private void updateEditFields(OptionSelector spinner, TextInputEditText etAppName, TextInputEditText etAppDescription) {
        AppItem selectedApp = (AppItem) spinner.getSelectedItem();
        if (selectedApp != null) {
            etAppName.setText(selectedApp.getAppName());
            etAppDescription.setText(selectedApp.getAppDesc());
        }
    }

    // Home page methods moved to HomeFragment

    /**
     * 刷新统计数据 - 改为发送广播通知HomeFragment自行刷新
     */
    private void fetchStatisticsWithVolley() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
                new Intent("com.cloud.dex.ACTION_REFRESH_STATISTICS"));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    // 处理ActionBar上的菜单按钮点击
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        try {
            if (item.getItemId() == android.R.id.home) {
                // 先显示涟漪动画
                applyRippleEffectOnHomeButton();
                // 再切换侧边栏
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    drawerLayout.openDrawer(GravityCompat.START);
                }
                return true;
            }
            // 不再处理 R.id.action_user，点击事件已被自定义 actionLayout 接管
            return super.onOptionsItemSelected(item);
        } catch (Exception e) {
            Log.e(TAG, "处理选项菜单时发生异常", e);
            return false;
        }
    }


    /**
     * 为Home按钮（侧边栏展开按钮）添加圆形涟漪扩散效果
     */
    private void applyRippleEffectOnHomeButton() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return; // 低版本无此动画API
        }
        final View homeButton = findViewById(android.R.id.home);
        if (homeButton == null || homeButton.getWidth() <= 0 || homeButton.getHeight() <= 0) {
            return; // 防止尚未布局完成
        }

        // 获取按钮中心点及最终半径
        int cx = homeButton.getWidth() / 2;
        int cy = homeButton.getHeight() / 2;
        int finalRadius = (int) Math.hypot(homeButton.getWidth(), homeButton.getHeight());

        // 创建临时半透明覆盖层（用于播放涟漪）
        final View rippleOverlay = new View(this);
        rippleOverlay.setBackgroundColor(Color.parseColor("#cccccc")); // 半透明白色涟漪

        // 将覆盖层添加到 DecorView 并定位到按钮位置
        final ViewGroup decorView = (ViewGroup) getWindow().getDecorView();
        int[] location = new int[2];
        homeButton.getLocationInWindow(location); // 获取相对窗口坐标
        ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
                homeButton.getWidth(), homeButton.getHeight()
        );
        decorView.addView(rippleOverlay, params);
        rippleOverlay.setX(location[0]);
        rippleOverlay.setY(location[1]);

        // 创建并播放圆形揭示动画
        Animator revealAnimator = ViewAnimationUtils.createCircularReveal(
                rippleOverlay, cx, cy, 0, finalRadius
        );
        revealAnimator.setDuration(400); // 动画时长
        revealAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        revealAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                decorView.removeView(rippleOverlay); // 动画结束后移除临时View
            }
        });
        revealAnimator.start();
    }

    /**
     * 显示用户下拉菜单
     */
    private void showUserDropdownMenu(View anchorView) {
        try {
            LinearLayout menuLayout = new LinearLayout(this);
            menuLayout.setOrientation(LinearLayout.VERTICAL);
            menuLayout.setBackgroundResource(R.drawable.bg_popup_menu);

            String[] items = {"个人中心", "修改信息", "退出登录"};
            // 先创建 PopupWindow 引用，初始为 null
            final PopupWindow[] popupWindowHolder = new PopupWindow[1];

            for (int i = 0; i < items.length; i++) {
                TextView itemView = new TextView(this);
                itemView.setText(items[i]);
                itemView.setTextSize(14);
                itemView.setTextColor(getResources().getColor(R.color.text_black));
                itemView.setGravity(Gravity.CENTER); // 文字水平垂直居中
                itemView.setPadding(16, 14, 16, 14);
                itemView.setBackgroundResource(android.R.drawable.list_selector_background);

                final int position = i;
                itemView.setOnClickListener(v -> {
                    if (popupWindowHolder[0] != null && popupWindowHolder[0].isShowing()) {
                        popupWindowHolder[0].dismiss();
                    }
                    switch (position) {
                        case 0: fetchUserInfoAndShowDialog(); break;
                        case 1: showEditProfileDialog(); break;
                        case 2: showLogoutConfirmationDialog(); break;
                    }
                });
                menuLayout.addView(itemView, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));
            }

            int widthInDp = 110;
            float density = getResources().getDisplayMetrics().density;
            int popupWidth = (int) (widthInDp * density + 0.5f);

            PopupWindow popupWindow = new PopupWindow(menuLayout,
                    popupWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    true);
            popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popupWindow.setOutsideTouchable(true);
            popupWindow.setTouchable(true);

            // 设置到 holder 中
            popupWindowHolder[0] = popupWindow;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                popupWindow.showAsDropDown(anchorView, 0, 0, Gravity.END);
            } else {
                anchorView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                int anchorWidth = anchorView.getMeasuredWidth();
                int xoff = anchorWidth - popupWidth;
                popupWindow.showAsDropDown(anchorView, xoff, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "显示用户下拉菜单时发生异常", e);
        }
    }

    /**
     * 请求用户信息并弹出模态框
     */
    private void fetchUserInfoAndShowDialog() {
        int userId = spManager.getUserId();
        String url = TokenAuthHelper.appendTokenToUrl(AppConfig.USER_INFO_URL + "?user_id=" + userId, MainActivity.this);

        JsonObjectRequest request = new JsonObjectRequest(Request.Method.GET, url, null,
                response -> {
                    try {
                        if (response.getBoolean("success")) {
                            JSONObject data = response.getJSONObject("data");
                            showUserInfoDialog(data);
                        } else {
                            String msg = response.getString("message");
                            showToast("获取用户信息失败: " + msg);
                        }
                    } catch (JSONException e) {
                        showToast("解析用户信息失败");
                    }
                },
                error -> showToast("网络请求失败")
        );
        requestQueue.add(request);
    }

    /**
     * 显示个人中心对话框（仅展示用户信息，无头像、无操作按钮）
     */
    private void showUserInfoDialog(JSONObject user) {
        try {
            String userId = user.getString("id");
            String username = user.getString("username");
            String email = user.getString("email");
            String membership = user.getString("membership_type");

            // 创建布局容器（垂直）
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20));
            layout.setBackgroundColor(Color.WHITE);

            // 账号ID（带复制图标）
            layout.addView(createInfoItem("账号ID", userId, true));
            layout.addView(createDivider());

            // 用户名
            layout.addView(createInfoItem("用户名", username, false));
            layout.addView(createDivider());

            // 邮箱
            layout.addView(createInfoItem("邮箱", email, false));
            layout.addView(createDivider());

            // 会员类型（特殊颜色显示）
            TextView membershipView = new TextView(this);
            membershipView.setText("会员类型：" + membership);
            membershipView.setTextSize(16);
            membershipView.setPadding(0, dpToPx(12), 0, dpToPx(12));
            if ("普通会员".equals(membership) || "免费用户".equals(membership)) {
                membershipView.setTextColor(Color.parseColor("#9E9E9E"));
            } else {
                membershipView.setTextColor(Color.parseColor("#4CAF50"));
            }
            layout.addView(membershipView);
            // 最后一条不加分割线，更美观

            // 创建对话框
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("个人中心")
                    .setView(layout)
                    .setPositiveButton("关闭", null)
                    .setCancelable(true);

            AlertDialog dialog = builder.create();
            dialog.show();

        } catch (JSONException e) {
            showToast("信息解析出错");
            e.printStackTrace();
        }
    }

    /**
     * 创建带标签和值的水平条目，可支持复制按钮
     * @param label 左侧标签
     * @param value 右侧值
     * @param enableCopy 是否显示复制图标并允许复制
     */
    private View createInfoItem(String label, String value, boolean enableCopy) {
        LinearLayout itemLayout = new LinearLayout(this);
        itemLayout.setOrientation(LinearLayout.HORIZONTAL);
        itemLayout.setPadding(0, dpToPx(12), 0, dpToPx(12));
        itemLayout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView labelView = new TextView(this);
        labelView.setText(label + "：");
        labelView.setTextSize(16);
        labelView.setTextColor(Color.parseColor("#757575"));
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(16);
        valueView.setTextColor(getResources().getColor(R.color.text_black));
        valueView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));

        itemLayout.addView(labelView);
        itemLayout.addView(valueView);

        if (enableCopy) {
            ImageView copyIcon = new ImageView(this);
            copyIcon.setImageResource(android.R.drawable.ic_menu_save);
            copyIcon.setColorFilter(Color.parseColor("#757575"));
            copyIcon.setPadding(dpToPx(8), 0, 0, 0);
            copyIcon.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(32), dpToPx(32)));
            copyIcon.setOnClickListener(v -> {
                copyToClipboard(value);
                showToast("已复制 " + label);
            });
            itemLayout.addView(copyIcon);
        }

        return itemLayout;
    }

    /**
     * 创建分割线
     */
    private View createDivider() {
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)));
        divider.setBackgroundColor(Color.parseColor("#E0E0E0"));
        return divider;
    }

    /**
     * 显示修改用户信息对话框 - 修改为只支持修改密码
     */
    private void showEditProfileDialog() {
        try {
            androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
            builder.setTitle("修改密码");

            // 加载布局
            View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_profile, null);
            builder.setView(dialogView);

            // 设置当前用户名
            TextView tvCurrentUsername = dialogView.findViewById(R.id.tvCurrentUsername);
            tvCurrentUsername.setText("当前用户：" + spManager.getUsername());

            // 获取输入框引用
            TextInputEditText etNewPassword = dialogView.findViewById(R.id.etNewPassword);
            TextInputEditText etConfirmPassword = dialogView.findViewById(R.id.etConfirmPassword);
            Button btnConfirmEdit = dialogView.findViewById(R.id.btnConfirmEdit);

            androidx.appcompat.app.AlertDialog dialog = builder.create();
            dialog.show();

            // 设置按钮点击监听
            btnConfirmEdit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String newPassword = etNewPassword.getText().toString().trim();
                    String confirmPassword = etConfirmPassword.getText().toString().trim();

                    if (TextUtils.isEmpty(newPassword)) {
                        showToast("新密码不能为空");
                        return;
                    }

                    if (newPassword.length() < 6) {
                        showToast("密码长度不能少于6位");
                        return;
                    }

                    if (!newPassword.equals(confirmPassword)) {
                        showToast("两次输入的密码不一致");
                        return;
                    }

                    // 调用修改密码的方法
                    updateUserPassword(newPassword);
                    dialog.dismiss();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "显示修改密码对话框时发生异常", e);
            showToast("打开修改密码对话框失败");
        }
    }

    /**
     * 更新用户密码
     */
    private void updateUserPassword(String newPassword) {
        // 显示加载状态
        showToast("正在修改密码...");

        // 获取当前用户ID
        int userId = spManager.getUserId();
        if (userId <= 0) {
            showToast("用户信息无效，请重新登录");
            directLogout();
            return;
        }

        // 创建请求体
        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("user_id", userId);
            jsonBody.put("new_password", newPassword);
        } catch (JSONException e) {
            Log.e(TAG, "创建修改密码JSON请求体失败", e);
            showToast("数据格式错误");
            return;
        }

        String UPDATE_PASSWORD_URL = AppConfig.RESET_PASSWORD_URL;

        JsonObjectRequest updateRequest = new JsonObjectRequest(Request.Method.POST, TokenAuthHelper.appendTokenToUrl(UPDATE_PASSWORD_URL, MainActivity.this), jsonBody,
                new Response.Listener<JSONObject>() {
                    @Override
                    public void onResponse(JSONObject response) {
                        Log.d(TAG, "修改密码响应: " + InputValidator.sanitizeForLog(response.toString()));

                        try {
                            boolean success = response.getBoolean("success");
                            String message = response.getString("message");

                            if (success) {
                                // 修改成功，更新本地存储的密码 - 使用 setPassword 方法
                                spManager.setPassword(newPassword);
                                showToast("密码修改成功");

                                spManager.clearLoginInfo();
                                // 延迟1秒后跳转到登录界面，让用户看到成功提示
                                new Handler().postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        navigateToLoginActivity();
                                    }
                                }, 1000);

                                // 可选：刷新用户界面
                                invalidateOptionsMenu();
                            } else {
                                showToast("修改失败: " + message);
                            }

                        } catch (JSONException e) {
                            Log.e(TAG, "解析修改密码响应失败", e);
                            showToast("响应解析失败");
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.e(TAG, "修改密码网络错误", error);
                        String errorMessage = "网络请求失败";
                        if (error.networkResponse != null) {
                            int statusCode = error.networkResponse.statusCode;
                            errorMessage = "网络错误，状态码: " + statusCode;
                        }
                        showToast("修改失败: " + errorMessage);
                    }
                }) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                headers.put("Accept", "application/json");
                return headers;
            }
        };

        if (requestQueue == null) {
            requestQueue = Volley.newRequestQueue(this);
        }
        requestQueue.add(updateRequest);
    }

    /**
     * 显示退出登录确认对话框
     */
    private void showLogoutConfirmationDialog() {
        try {
            androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
            builder.setTitle("确认退出");
            builder.setMessage("您确定要退出登录吗？");

            // 确认按钮
            builder.setPositiveButton("是", (dialog, which) -> {
                // 用户点击"是"，执行退出登录
                performLogout();
            });

            // 取消按钮
            builder.setNegativeButton("否", (dialog, which) -> {
                // 用户点击"否"，关闭对话框
                dialog.dismiss();
            });

            // 设置对话框不可取消（点击外部不消失）
            builder.setCancelable(false);

            // 创建并显示对话框
            androidx.appcompat.app.AlertDialog dialog = builder.create();
            dialog.show();

            // 可选：设置按钮文字颜色
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(Color.RED);
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.GRAY);

        } catch (Exception e) {
            Log.e(TAG, "显示退出确认对话框时发生异常", e);
        }
    }

    /**
     * 执行退出登录操作（用户主动退出）
     */
    private void performLogout() {
        try {
            // 清除登录信息和会员类型
            spManager.clearLoginInfo();
            spManager.clearMembershipInfo();

            showToast("已退出登录");
            navigateToLoginActivity();
        } catch (Exception e) {
            Log.e(TAG, "退出登录时发生异常", e);
            // 即使退出登录失败，也尝试跳转到登录页面
            navigateToLoginActivity();
        }
    }

    /**
     * 直接退出登录（不显示确认对话框）
     */
    private void directLogout() {
        try {
            // 清除登录信息和会员类型
            spManager.clearLoginInfo();
            spManager.clearMembershipInfo();

            showToast("登录已过期，请重新登录");
            navigateToLoginActivity();
        } catch (Exception e) {
            Log.e(TAG, "直接退出登录时发生异常", e);
            // 即使退出登录失败，也尝试跳转到登录页面
            navigateToLoginActivity();
        }
    }

    private void navigateToLoginActivity() {
        try {
            Intent intent = new Intent(MainActivity.this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "跳转到登录页面时发生异常", e);
            // 如果跳转失败，尝试使用更简单的方式
            try {
                Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                startActivity(intent);
                finish();
            } catch (Exception ex) {
                Log.e(TAG, "再次尝试跳转也失败", ex);
            }
        }
    }

    private void showToast(String message) {
        if (!isDestroyed) {
            try {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "显示Toast失败: " + message, e);
            }
        }
    }

    // 处理返回键
    @Override
    public void onBackPressed() {
        try {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                moveTaskToBack(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "处理返回键时发生异常", e);
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        isDestroyed = true;
        try {
            if (requestQueue != null) {
                requestQueue.cancelAll(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "取消请求队列时发生异常", e);
        }
    }



    // Home page animation/methods moved to HomeFragment
}
