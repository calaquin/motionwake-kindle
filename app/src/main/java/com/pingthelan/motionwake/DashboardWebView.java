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

        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Offline Recovery Runbook</title>"
                + "<style>"
                + "*{box-sizing:border-box}body{margin:0;background:#080a0d;color:#d5dbe0;font-family:Arial,sans-serif;font-size:13px}"
                + "#page{padding:12px}a{color:#9bbddd;text-decoration:none}.top{height:40px;border-bottom:1px solid #303840;margin-bottom:10px}"
                + "h1{display:inline;font-size:20px;color:#e0b95f}.back{float:right;margin-top:4px;padding:3px 8px;border:1px solid #39434c;color:#aab4bd}"
                + ".banner{padding:10px;margin-bottom:12px;background:#1a1712;border:1px solid #453d2d;color:#e0b95f;font-size:13px;font-weight:bold}"
                + ".section{margin-bottom:15px;background:#11151a;border:1px solid #252b32;padding:12px}"
                + "h2{font-size:14px;margin:0 0 8px;color:#76a6d3;border-bottom:1px solid #252b32;padding-bottom:4px}"
                + "table{width:100%;border-collapse:collapse;margin-top:6px}th,td{text-align:left;padding:6px;border-bottom:1px solid #252b32}th{color:#89939e;font-size:11px}"
                + "ol,ul{margin:6px 0;padding-left:20px;color:#c0c8d0}li{margin-bottom:4px}"
                + ".notice{font-size:11px;color:#7f8a94;margin-top:15px;text-align:center;border-top:1px solid #252b32;padding-top:10px}"
                + ".status-badge{display:inline-block;padding:2px 6px;border-radius:2px;font-size:10px;font-weight:bold;text-transform:uppercase}"
                + ".status-online{background:#112a1a;color:#5fd085;border:1px solid #255232}"
                + ".status-offline{background:#2a1112;color:#df7474;border:1px solid #522528}"
                + "</style></head><body><div id=\"page\">"
                + "<div class=\"top\"><h1>⚡ OFFLINE RECOVERY RUNBOOK</h1></div>"
                + "<div id=\"offline-banner\" class=\"banner\">⚡ OFFLINE MODE — Displaying cached recovery snapshot</div>"
                + "<div class=\"section\">"
                + "<h2>INFRASTRUCTURE DIRECTORY (NON-SECRET)</h2>"
                + "<table><thead><tr><th>HOST / ROLE</th><th>IP ADDRESS</th><th>STATUS</th></tr></thead><tbody>"
                + "<tr><td>PingTheLan (Primary Dashboard)</td><td>10.0.0.133</td><td><span class=\"status-badge status-offline\">UNREACHABLE</span></td></tr>"
                + "<tr><td>Pi-PingTheLan (WoL Relay &amp; Backup)</td><td>10.0.0.179</td><td><span class=\"status-badge status-offline\">UNREACHABLE</span></td></tr>"
                + "<tr><td>Gateway (LAN Router)</td><td>10.0.0.1</td><td><span class=\"status-badge status-online\">ROUTER</span></td></tr>"
                + "</tbody></table></div>"
                + "<div class=\"section\">"
                + "<h2>EXPECTED BOOT &amp; RECOVERY ORDER</h2>"
                + "<ol id=\"boot-sequence\">"
                + "<li><strong>1. Network &amp; Gateway (10.0.0.1)</strong> — Verify router power and LAN link LEDs.</li>"
                + "<li><strong>2. Storage &amp; Host Power (Pi-PingTheLan / PingTheLan)</strong> — Boot host hardware; verify disk mounts.</li>"
                + "<li><strong>3. Core Docker Services</strong> — Confirm Portainer, Home Assistant, and Database containers return to healthy state.</li>"
                + "<li><strong>4. Remote VPS &amp; Twingate Relay</strong> — Verify external network tunnel connectivity.</li>"
                + "</ol></div>"
                + "<div class=\"section\">"
                + "<h2>EMERGENCY OPERATIONAL STEPS</h2>"
                + "<ul>"
                + "<li><strong>Power Outage:</strong> Kindle Fire remains operational on internal battery. Monitor battery level and local network status.</li>"
                + "<li><strong>Network Loss:</strong> Verify LAN router power at <code>10.0.0.1</code>. Use Pi-PingTheLan (<code>10.0.0.179</code>) to dispatch WoL magic packets.</li>"
                + "<li><strong>Service Recovery:</strong> Once host power returns, containers auto-restart based on systemd policy.</li>"
                + "</ul></div>"
                + "<div class=\"section\">"
                + "<h2>LAST KNOWN MACHINE SNAPSHOT</h2>"
                + "<div id=\"machine-snapshot\">Loading snapshot data...</div>"
                + "</div>"
                + "<div class=\"notice\">🔒 Security Boundary: No PINs, control tokens, SSH keys, or agent secrets are stored in offline caches.</div>"
                + "</div>"
                + "<script>"
                + "(function(){"
                + "function id(x){return document.getElementById(x)}"
                + "function esc(x){return String(x).replace(/&/g,\"&amp;\").replace(/</g,\"&lt;\").replace(/>/g,\"&gt;\")}"
                + "function when(t){if(!t)return \"--\";var d=new Date(t*1000);return (d.getMonth()+1)+\"/\"+d.getDate()+\" \"+(\"0\"+d.getHours()).slice(-2)+\":\"+(\"0\"+d.getMinutes()).slice(-2)}"
                + "function renderSnapshot(d){"
                + "if(!d)return;"
                + "if(d.cached_at){id(\"offline-banner\").innerHTML=\"⚡ OFFLINE MODE — Cached at \"+when(d.cached_at)}"
                + "var machines=d.machines||[],h=\"\",i;"
                + "if(machines.length>0){"
                + "h=\"<table><thead><tr><th>MACHINE</th><th>STATE</th><th>SUMMARY</th></tr></thead><tbody>\";"
                + "for(i=0;i<machines.length;i++){"
                + "var m=machines[i],st=m.state||\"unknown\";"
                + "var cls=st===\"online\"?\"status-online\":\"status-offline\";"
                + "h+=\"<tr><td><strong>\"+esc(m.label)+\"</strong></td><td><span class=\\\"status-badge \"+cls+\"\\\">\"+esc(st)+\"</span></td><td>\"+esc(m.role||\"--\")+\"</td></tr>\";"
                + "}"
                + "h+=\"</tbody></table>\";"
                + "id(\"machine-snapshot\").innerHTML=h;"
                + "}else{"
                + "id(\"machine-snapshot\").innerHTML=\"No cached machine data available.\";"
                + "}"
                + "}"
                + "var raw=null;"
                + "try{raw=localStorage.getItem(\"kindle-dashboard-offline-snapshot\")}catch(e){}"
                + "if(raw){try{renderSnapshot(JSON.parse(raw))}catch(e){}}"
                + "}());"
                + "</script></body></html>";

        loadDataWithBaseURL(PRIMARY_DASHBOARD_URL, html, "text/html", "UTF-8", null);
    }
}
