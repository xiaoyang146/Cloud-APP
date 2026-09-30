package com.cloud.dex;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.util.Linkify;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;

public class AboutCloudActivity extends AppCompatActivity {

    private static final String TAG = "AboutCloudActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about_cloud);

        setupActionBar();
        loadAppInfo();
    }

    private void setupActionBar() {
        try {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.card_bg)));
                SpannableString title = new SpannableString("关于Cloud");
                title.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.text_black)), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                getSupportActionBar().setTitle(title);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置ActionBar失败", e);
        }
    }

    private void loadAppInfo() {
        ImageView ivIcon = findViewById(R.id.ivAppIcon);
        TextView tvAppName = findViewById(R.id.tvAppName);
        TextView tvVersion = findViewById(R.id.tvVersion);
        TextView tvDescription = findViewById(R.id.tvDescription);
        TextView tvWebsite = findViewById(R.id.tvWebsite);
        TextView tvCopyright = findViewById(R.id.tvCopyright);

        try {
            PackageManager pm = getPackageManager();
            // 获取应用图标
            ApplicationInfo appInfo = pm.getApplicationInfo(getPackageName(), 0);
            ivIcon.setImageDrawable(pm.getApplicationIcon(appInfo));

            // 获取应用名称
            String appName = pm.getApplicationLabel(appInfo).toString();
            tvAppName.setText(appName);

            // 获取版本信息
            PackageInfo pInfo = pm.getPackageInfo(getPackageName(), 0);
            String versionName = pInfo.versionName;
            int versionCode = pInfo.versionCode;
            tvVersion.setText("版本" + versionName + " (" + versionCode + ")");

            // 固定描述
            tvDescription.setText("这是一个云应用管理平台");

            // 网站链接
            String websiteText = "网站：" + AppConfig.WEBSITE_URL;
            tvWebsite.setText(websiteText);
            Linkify.addLinks(tvWebsite, Linkify.WEB_URLS);
            tvWebsite.setMovementMethod(LinkMovementMethod.getInstance());

            // 版权信息
            tvCopyright.setText("© 2026 " + appName + " 版权所有");

        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }
}