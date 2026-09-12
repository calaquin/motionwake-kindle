package com.pingthelan.motionwake;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.net.HttpURLConnection;
import java.net.URL;

public class DashboardWebView extends WebView {

    private static final String TAG = "MotionWake";
    private static final String PRIMARY_DASHBOARD_URL =
            "http://10.0.0.133:8099/";
    private static final String FALLBACK_DASHBOARD_URL =
            "http://10.0.0.179:8099/";
    private static final long HEALTH_CHECK_INTERVAL_MS = 15000L;
    private static final int HEALTH_CHECK_TIMEOUT_MS = 2500;

    private Handler healthHandler;
    private String currentDashboardUrl;
    private boolean healthCheckInProgress;
    private boolean showingUnavailable;
    private volatile boolean stopped;

    private final Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkPreferredDashboard();
        }
    };

    public DashboardWebView(Context context) {
        super(context);
        healthHandler = new Handler();
        configure();
    }

    @SuppressWarnings("deprecation")
    private void configure() {
        setBackgroundColor(Color.BLACK);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        clearCache(true);

        setWebViewClient(new WebViewClient() {
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
            public void onPageFinished(WebView view, String url) {
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
    }

    public void start() {
        stopped = false;
        checkPreferredDashboard();
    }

    public void shutdown() {
        stopped = true;

        if (healthHandler != null) {
            healthHandler.removeCallbacksAndMessages(null);
            healthHandler = null;
        }

        stopLoading();
        destroy();
    }

    public void hideSystemUi() {
        setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LOW_PROFILE
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        );
    }

    private void checkPreferredDashboard() {
        if (stopped || healthCheckInProgress) {
            return;
        }

        healthCheckInProgress = true;

        Thread checker = new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean primaryAvailable = isDashboardAvailable(
                        PRIMARY_DASHBOARD_URL
                );

                Handler handler = healthHandler;
                if (handler == null) {
                    return;
                }

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        healthCheckInProgress = false;

                        if (stopped) {
                            return;
                        }

                        if (primaryAvailable) {
                            if (!PRIMARY_DASHBOARD_URL.equals(
                                    currentDashboardUrl)
                                    || showingUnavailable) {
                                Log.i(TAG, "Using PingTheLan dashboard");
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
        if (stopped || healthHandler == null) {
            return;
        }

        healthHandler.removeCallbacks(healthCheckRunnable);
        healthHandler.postDelayed(
                healthCheckRunnable,
                HEALTH_CHECK_INTERVAL_MS
        );
    }

    private void loadDashboard(String dashboardUrl) {
        currentDashboardUrl = dashboardUrl;
        showingUnavailable = false;
        stopLoading();
        loadUrl(
                dashboardUrl
                        + "?motionwake="
                        + System.currentTimeMillis()
        );
    }

    private boolean isRequestFor(String requestUrl, String dashboardUrl) {
        return requestUrl != null && requestUrl.startsWith(dashboardUrl);
    }

    private void showUnavailablePage() {
        if (showingUnavailable) {
            return;
        }

        showingUnavailable = true;

        String html =
                "<html>"
                + "<body style=\"margin:0;background:#080a0d;"
                + "color:#ddd;font-family:sans-serif;"
                + "text-align:center;\">"
                + "<div style=\"padding-top:220px;"
                + "font-size:36px;\">Dashboard unavailable</div>"
                + "<div style=\"margin-top:20px;"
                + "font-size:18px;color:#777;\">"
                + "Checking PingTheLan and Pi-PingTheLan..."
                + "</div></body></html>";

        loadData(html, "text/html", "UTF-8");
    }
}
