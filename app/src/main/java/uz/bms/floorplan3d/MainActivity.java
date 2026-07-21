package uz.bms.floorplan3d;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.net.Uri;
import android.net.http.SslError;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Fullscreen kiosk. Loads the bundled 3D floor-plan kiosk page and hands it the
 * Home Assistant URL + long-lived token; the page connects to HA over WebSocket
 * and renders the live plan (view + control only). Editing/authoring is done in
 * Home Assistant, not here.
 */
public class MainActivity extends Activity {

    public static final String PREFS = "bms3d";
    public static final String KEY_URL = "ha_url";
    public static final String KEY_TOKEN = "ha_token";
    public static final String KEY_KIOSK = "kiosk_lock"; // screen-pinning kiosk mode
    public static final String KEY_JS = "javascript_enabled";
    /** Seconds between automatic page reloads. 0 = off. */
    public static final String KEY_AUTO_RELOAD = "auto_reload_seconds";
    /** Load this page instead of the bundled 3D one. Empty = bundled. */
    public static final String KEY_START_URL = "start_url";

    private WebView web;
    private final Handler reloadHandler = new Handler(Looper.getMainLooper());
    private Runnable autoReload;
    /** Home Assistant base URL, used to proxy the intercom's ringtone/snapshot. */
    private String haBase = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String url = p.getString(KEY_URL, "");
        String token = p.getString(KEY_TOKEN, "");
        if (url.isEmpty() || token.isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return;
        }
        haBase = normalizeBase(url);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        // On by default — the 3D panel is a JS app and won't run without it.
        s.setJavaScriptEnabled(p.getBoolean(KEY_JS, true));
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // The kiosk page runs from file:// and opens a WebSocket to HA — allow it.
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        // The default WebChromeClient DENIES every permission the page asks for,
        // which is why an intercom's camera feed stayed blank here while it played
        // fine in a desktop browser. Grant what the page requests, but only for
        // resources Android has already granted this app.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    java.util.List<String> ok = new java.util.ArrayList<>();
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                            if (hasPermission(Manifest.permission.CAMERA)) ok.add(r);
                        } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                            if (hasPermission(Manifest.permission.RECORD_AUDIO)) ok.add(r);
                        } else {
                            // Protected media ids etc. — nothing extra to check.
                            ok.add(r);
                        }
                    }
                    if (ok.isEmpty()) {
                        request.deny();
                    } else {
                        request.grant(ok.toArray(new String[0]));
                    }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback cb) {
                cb.invoke(origin,
                        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                                || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
                        false);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // The BMS Intercom pop-up (served by Home Assistant) points its
                // ringtone and camera snapshot at HA-root paths like
                // /bms_intercom_static/ring1.mp3. The kiosk page is bundled and
                // runs from file://, so those resolve to file:///… and fail — which
                // is why the doorbell ring stayed silent here while the video (over
                // WebRTC/WebSocket) played fine. Fetch just those assets from the
                // configured Home Assistant instead, so the ring is heard.
                try {
                    String method = request.getMethod();
                    if (method != null && !"GET".equalsIgnoreCase(method)) return null;
                    Uri u = request.getUrl();
                    String path = (u != null) ? u.getPath() : null;
                    if (path == null) return null;
                    if (!path.startsWith("/bms_intercom_static/")
                            && !path.startsWith("/api/camera_proxy")) {
                        return null; // not an intercom asset — leave it alone
                    }
                    if (haBase.isEmpty()) return null;
                    String target = haBase + path;
                    String q = u.getQuery();
                    if (q != null && !q.isEmpty()) target += "?" + q;
                    return proxyFromHa(target);
                } catch (Exception e) {
                    return null; // fall back to the default load
                }
            }

            @Override
            public void onReceivedSslError(WebView view, final SslErrorHandler handler,
                                           SslError error) {
                // LAN kit (intercoms, NVRs) serves HTTPS with a self-signed cert.
                // Blanket-accepting would also cover a real MITM on the Home
                // Assistant connection — which carries a long-lived token — so
                // ask once per host and remember only what was accepted.
                final String host = TrustedHosts.hostOf(error.getUrl());
                if (host != null && TrustedHosts.isTrusted(MainActivity.this, host)) {
                    handler.proceed();
                    return;
                }
                if (isFinishing()) {
                    handler.cancel();
                    return;
                }
                String reason;
                switch (error.getPrimaryError()) {
                    case SslError.SSL_UNTRUSTED:
                        reason = "сертификат выдан неизвестным центром (самоподписанный)";
                        break;
                    case SslError.SSL_EXPIRED:
                        reason = "срок действия сертификата истёк";
                        break;
                    case SslError.SSL_IDMISMATCH:
                        reason = "имя в сертификате не совпадает с адресом";
                        break;
                    case SslError.SSL_NOTYETVALID:
                        reason = "сертификат ещё не вступил в силу";
                        break;
                    default:
                        reason = "сертификат не прошёл проверку";
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Небезопасное соединение")
                        .setMessage(host + "\n\n" + reason
                                + "\n\nРазрешайте только для своего оборудования в локальной сети.")
                        .setPositiveButton("Разрешить", (dlg, w) -> {
                            TrustedHosts.trust(MainActivity.this, host);
                            handler.proceed();
                        })
                        .setNegativeButton("Отмена", (dlg, w) -> handler.cancel())
                        .setOnCancelListener(dlg -> handler.cancel())
                        .show();
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        // Hidden re-config: long-press the bottom-left corner to reopen settings.
        View hot = new View(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM | Gravity.START);
        hot.setLayoutParams(lp);
        hot.setOnLongClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        });
        root.addView(hot);

        setContentView(root);
        loadKiosk(url, token);
    }

    private void loadKiosk(String url, String token) {
        // A start page pointed at Home Assistant itself loads the real frontend,
        // so anything an integration adds there — the intercom pop-up with its
        // camera, most of all — works exactly as it does on a desktop. The
        // bundled page can't do that: it hosts only the 3D card, not HA, so a
        // pop-up HA injects into a dashboard simply doesn't exist in it.
        // Empty (the default) keeps the bundled page, which still opens offline.
        String start = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_START_URL, "").trim();
        if (!start.isEmpty()) {
            web.loadUrl(start);
            return;
        }
        String html = readAsset("kiosk/index.html");
        String cfg = "<script>window.__HA3D__={haUrl:" + jsStr(url)
                + ",token:" + jsStr(token) + ",useSession:false,app:true};</script>";
        // The kiosk page carries an <!--HA3D_INJECT--> placeholder for exactly this.
        if (html.contains("<!--HA3D_INJECT-->")) {
            html = html.replace("<!--HA3D_INJECT-->", cfg);
        } else {
            html = html.replaceFirst("(?i)<head[^>]*>", "$0" + cfg);
        }
        web.loadDataWithBaseURL("file:///android_asset/kiosk/", html, "text/html", "utf-8", null);
    }

    private String readAsset(String name) {
        try (InputStream is = getAssets().open(name);
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "<html><body style='background:#111;color:#fff;font-family:sans-serif;padding:24px'>"
                    + "Kiosk asset missing: " + e.getMessage() + "</body></html>";
        }
    }

    private static String jsStr(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // --- Intercom asset proxy (ringtone / camera snapshot) ------------------

    /** Normalize a HA base URL: add a scheme if missing, drop trailing slashes. */
    private static String normalizeBase(String u) {
        if (u == null) return "";
        u = u.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** Fetch a Home Assistant asset and hand it to the WebView. Tolerates the
     *  self-signed LAN certificate, matching the app's per-host SSL trust. Runs on
     *  the WebView's background thread (shouldInterceptRequest), so blocking IO is
     *  fine here. */
    private WebResourceResponse proxyFromHa(String target) throws Exception {
        HttpURLConnection c = openTrusting(new URL(target));
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(8000);
        c.setReadTimeout(0); // 0 = no read timeout, so a live MJPEG stream isn't cut
        int code = c.getResponseCode();
        if (code < 200 || code >= 400) {
            c.disconnect();
            return null;
        }
        String ct = c.getContentType();
        String mime = "application/octet-stream";
        String enc = null;
        if (ct != null) {
            String[] parts = ct.split(";");
            mime = parts[0].trim();
            for (int i = 1; i < parts.length; i++) {
                String pt = parts[i].trim();
                if (pt.toLowerCase().startsWith("charset=")) {
                    enc = pt.substring("charset=".length()).trim();
                }
            }
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse(mime, enc, 200, "OK", headers, c.getInputStream());
    }

    /** An HttpURLConnection that accepts the configured HA's self-signed cert. */
    private HttpURLConnection openTrusting(URL url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (c instanceof HttpsURLConnection) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            HttpsURLConnection hc = (HttpsURLConnection) c;
            hc.setSSLSocketFactory(ctx.getSocketFactory());
            hc.setHostnameVerifier((h, sess) -> true);
        }
        return c;
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        enterKiosk();
        requestMediaPermissions();
        // Remote control (HTTP) can reach the page only while it is on screen.
        KioskBus.setHandler(remoteHandler);
        RemoteHttpService.sync(this);
        startAutoReload();
    }

    @Override
    protected void onPause() {
        KioskBus.clearHandler(remoteHandler);
        stopAutoReload(); // don't reload a page nobody is looking at
        super.onPause();
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** Ask for camera/mic once, so a video intercom embedded in the panel can
     *  actually open a stream. Without the runtime grant the page's request is
     *  refused above and the feed just stays black, with nothing to explain it. */
    private void requestMediaPermissions() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (!hasPermission(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (need.isEmpty()) return;
        try {
            requestPermissions(need.toArray(new String[0]), 42);
        } catch (Exception ignored) {
            // Some kiosk ROMs block the dialog; the panel itself still works.
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        // Re-run the page so a stream that was refused before can start now.
        if (code == 42 && web != null) web.reload();
    }

    /** Optional periodic reload (settings → "Автообновление страницы"). Off at 0,
     *  which is the default — the panel already syncs its own data live. */
    private void startAutoReload() {
        stopAutoReload();
        int secs = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_AUTO_RELOAD, 0);
        if (secs <= 0) return;
        final long period = secs * 1000L;
        autoReload = new Runnable() {
            @Override
            public void run() {
                if (web != null) web.reload();
                reloadHandler.postDelayed(this, period);
            }
        };
        reloadHandler.postDelayed(autoReload, period);
    }

    private void stopAutoReload() {
        if (autoReload != null) {
            reloadHandler.removeCallbacks(autoReload);
            autoReload = null;
        }
    }

    /** Commands from RemoteHttpService, marshalled onto the UI thread. */
    private final KioskBus.Handler remoteHandler = new KioskBus.Handler() {
        @Override
        public void onCommand(final KioskBus.Command cmd) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (web == null) return;
                    if ("reload".equals(cmd.name)) {
                        web.reload();
                    } else if ("loadUrl".equals(cmd.name) && cmd.url != null) {
                        web.loadUrl(cmd.url);
                    } else if ("loadStartUrl".equals(cmd.name)) {
                        // Back to the bundled 3D page with the saved credentials.
                        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                        loadKiosk(p.getString(KEY_URL, ""), p.getString(KEY_TOKEN, ""));
                    }
                }
            });
        }
    };

    /**
     * Kiosk lock (screen pinning / Lock Task Mode): blocks Home & Recents and
     * restricts the status-bar pull-down, so the panel can't be left. Exit is
     * only via the hidden bottom-left settings → "Exit kiosk" (stopLockTask).
     * Enabled unless the user turned it off in settings.
     *
     * Without device-owner, Android still lets a user unpin by holding Back +
     * Overview; to fully block that too, one-time provision this app as device
     * owner (see the README).
     */
    private void enterKiosk() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_KIOSK, true)) return; // kiosk lock disabled in settings
        try {
            // If provisioned as DEVICE OWNER, allow-list ourselves so Lock Task Mode
            // becomes un-escapable (no "screen pinned" toast, no Back+Overview exit).
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                ComponentName admin = new ComponentName(this, AdminReceiver.class);
                dpm.setLockTaskPackages(admin, new String[]{ getPackageName() });
            }
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            int state = (am != null) ? am.getLockTaskModeState() : ActivityManager.LOCK_TASK_MODE_NONE;
            if (state == ActivityManager.LOCK_TASK_MODE_NONE) {
                startLockTask();
            }
        } catch (Exception ignored) {
            // Some OEM ROMs disallow lock task without device-owner — immersive
            // fullscreen + the blocked buttons still apply.
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    private void immersive() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        // Otherwise stay put — this is a kiosk.
    }

    @Override
    protected void onDestroy() {
        KioskBus.clearHandler(remoteHandler);
        stopAutoReload();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
