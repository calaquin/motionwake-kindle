package com.pingthelan.motionwake;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class DashboardActivity extends Activity {

    private static final String DASHBOARD_URL =
            "http://10.0.0.179:8099/";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        webView = new WebView(this);

        webView.setBackgroundColor(Color.BLACK);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);

        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    String url) {

                if (url != null
                        && (url.startsWith("http://")
                        || url.startsWith("https://"))) {

                    view.loadUrl(url);
                }

                return true;
            }

            @Override
            public void onPageFinished(
                    WebView view,
                    String url) {

                super.onPageFinished(view, url);
                hideSystemUi();
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    int errorCode,
                    String description,
                    String failingUrl) {

                String html =
                        "<html>"
                        + "<body style=\"margin:0;background:#080a0d;"
                        + "color:#ddd;font-family:sans-serif;"
                        + "text-align:center;\">"
                        + "<div style=\"padding-top:250px;"
                        + "font-size:36px;\">"
                        + "Dashboard unavailable"
                        + "</div>"
                        + "<div style=\"margin-top:20px;"
                        + "font-size:18px;color:#777;\">"
                        + "http://10.0.0.179:8099/"
                        + "</div>"
                        + "</body>"
                        + "</html>";

                view.loadDataWithBaseURL(
                        DASHBOARD_URL,
                        html,
                        "text/html",
                        "UTF-8",
                        null
                );
            }
        });

        setContentView(webView);

        hideSystemUi();

        webView.loadUrl(DASHBOARD_URL);
    }

    private void hideSystemUi() {
        if (webView == null) {
            return;
        }

        webView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LOW_PROFILE
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }

        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
