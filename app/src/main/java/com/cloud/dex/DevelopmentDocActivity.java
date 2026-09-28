package com.cloud.dex;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;

public class DevelopmentDocActivity extends AppCompatActivity {
    // 开发文档地址（可根据需要修改）
    // URL unified: AppConfig.DOC_URL
    private static final String TAG = "DevelopmentDocActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_development_doc);

        setupActionBar();

        final ProgressBar progressBar = findViewById(R.id.progressBar);
        WebView webView = findViewById(R.id.webView);

        // 配置 WebView
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);          // 允许 JS
        settings.setAllowFileAccess(false);                   // 禁止 WebView 访问文件系统
        settings.setAllowFileAccessFromFileURLs(false);       // 禁止 file:// URL 访问其他文件
        settings.setAllowUniversalAccessFromFileURLs(false);  // 禁止 file:// URL 跨域访问
        settings.setAllowContentAccess(false);                // 禁止内容访问
        settings.setDomStorageEnabled(true);          // 允许 DOM 存储
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); // 解决 HTTP 页面内 HTTPS 资源问题

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });

        webView.loadUrl(AppConfig.DOC_URL);
    }

    private void setupActionBar() {
        try {
            if (getSupportActionBar() != null) {
                getSupportActionBar().setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.card_bg)));
                SpannableString title = new SpannableString("开发文档");
                title.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.text_black)), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                getSupportActionBar().setTitle(title);
            }
        } catch (Exception e) {
            Log.e(TAG, "设置ActionBar失败", e);
        }
    }

    @Override
    public void onBackPressed() {
        WebView webView = findViewById(R.id.webView);
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}