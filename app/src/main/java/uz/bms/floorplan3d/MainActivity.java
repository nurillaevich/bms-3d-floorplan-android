package uz.bms.floorplan3d;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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

    private WebView web;

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

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // The kiosk page runs from file:// and opens a WebSocket to HA — allow it.
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        web.setWebChromeClient(new WebChromeClient());

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

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        enterKiosk();
        // Remote control (HTTP) can reach the page only while it is on screen.
        KioskBus.setHandler(remoteHandler);
        RemoteHttpService.sync(this);
    }

    @Override
    protected void onPause() {
        KioskBus.clearHandler(remoteHandler);
        super.onPause();
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
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
