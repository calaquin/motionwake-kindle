package com.pingthelan.motionwake;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.net.HttpURLConnection;
import java.net.URL;

public class DashboardActivity extends Activity {

    private static final String TAG = "MotionWake";
    private static final String PRIMARY_DASHBOARD_URL =
            "http://10.0.0.133:8099/";
    private static final String FALLBACK_DASHBOARD_URL =
            "http://10.0.0.179:8099/";
    private static final long HEALTH_CHECK_INTERVAL_MS = 15000L;
    private static final int HEALTH_CHECK_TIMEOUT_MS = 2500;

    private WebView webView;
    private Handler healthHandler;
    private String currentDashboardUrl;
    private boolean healthCheckInProgress;
    private boolean showingUnavailable;
    private volatile boolean destroyed;

    private final Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkPreferredDashboard();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        webView = new WebView(this);
        healthHandler = new Handler();

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

        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.clearCache(true);

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

                if (url != null
                        && currentDashboardUrl != null
                        && url.startsWith(currentDashboardUrl)) {
                    showingUnavailable = false;
                }

                hideSystemUi();
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    int errorCode,
                    String description,
                    String failingUrl) {

                if (isRequestFor(failingUrl, PRIMARY_DASHBOARD_URL)
                        && PRIMARY_DASHBOARD_URL.equals(
                        currentDashboardUrl)) {
                    Log.w(TAG, "PingTheLan dashboard failed; using Pi");
                    loadDashboard(FALLBACK_DASHBOARD_URL);
                    return;
                }

                if (isRequestFor(failingUrl, FALLBACK_DASHBOARD_URL)
                        && FALLBACK_DASHBOARD_URL.equals(
                        currentDashboardUrl)) {
                    showUnavailablePage();
                }
            }
        });

        setContentView(webView);

        hideSystemUi();

        checkPreferredDashboard();
    }

    private void checkPreferredDashboard() {
        if (destroyed || healthCheckInProgress) {
            return;
        }

        healthCheckInProgress = true;

        Thread checker = new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean primaryAvailable = isDashboardAvailable(
                        PRIMARY_DASHBOARD_URL
                );

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        healthCheckInProgress = false;

                        if (destroyed || webView == null) {
                            return;
                        }

                        if (primaryAvailable) {
                            if (!PRIMARY_DASHBOARD_URL.equals(
                                    currentDashboardUrl)
                                    || showingUnavailable) {
                                Log.i(
                                        TAG,
                                        "Using PingTheLan dashboard"
                                );
                                loadDashboard(PRIMARY_DASHBOARD_URL);
                            }
                        } else if (!FALLBACK_DASHBOARD_URL.equals(
                                currentDashboardUrl)
                                || showingUnavailable) {
                            Log.i(TAG, "Using Pi-PingTheLan dashboard");
                            loadDashboard(FALLBACK_DASHBOARD_URL);
                        }

                        scheduleHealthCheck();
                    }
                });
            }
        }, "DashboardHealthCheck");

        checker.start();
    }

    private boolean isDashboardAvailable(String dashboardUrl) {
        HttpURLConnection connection = null;

        try {
            URL healthUrl = new URL(
                    dashboardUrl
                            + "health?t="
                            + System.currentTimeMillis()
            );

            connection = (HttpURLConnection) healthUrl.openConnection();
            connection.setConnectTimeout(HEALTH_CHECK_TIMEOUT_MS);
            connection.setReadTimeout(HEALTH_CHECK_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Connection", "close");

            return connection.getResponseCode() == 200;
        } catch (Exception error) {
            Log.d(TAG, "Dashboard health check failed: " + dashboardUrl);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void scheduleHealthCheck() {
        if (destroyed || healthHandler == null) {
            return;
        }

        healthHandler.removeCallbacks(healthCheckRunnable);
        healthHandler.postDelayed(
                healthCheckRunnable,
                HEALTH_CHECK_INTERVAL_MS
        );
    }

    private void loadDashboard(String dashboardUrl) {
        if (webView == null) {
            return;
        }

        currentDashboardUrl = dashboardUrl;
        showingUnavailable = false;
        webView.stopLoading();
        webView.loadUrl(
                dashboardUrl
                        + "?motionwake="
                        + System.currentTimeMillis()
        );
    }

    private boolean isRequestFor(String requestUrl, String dashboardUrl) {
        return requestUrl != null && requestUrl.startsWith(dashboardUrl);
    }

    private void showUnavailablePage() {
        if (webView == null || showingUnavailable) {
            return;
        }

        showingUnavailable = true;

        String html =
                "<html>"
                + "<body style=\"margin:0;background:#080a0d;"
                + "color:#ddd;font-family:sans-serif;"
                + "text-align:center;\">"
                + "<div style=\"padding-top:220px;"
                + "font-size:36px;\">"
                + "Dashboard unavailable"
                + "</div>"
                + "<div style=\"margin-top:20px;"
                + "font-size:18px;color:#777;\">"
                + "Checking PingTheLan and Pi-PingTheLan..."
                + "</div>"
                + "</body>"
                + "</html>";

        webView.loadData(
                html,
                "text/html",
                "UTF-8"
        );
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
        destroyed = true;

        if (healthHandler != null) {
            healthHandler.removeCallbacksAndMessages(null);
            healthHandler = null;
        }

        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
