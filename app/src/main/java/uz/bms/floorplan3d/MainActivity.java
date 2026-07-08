package uz.bms.floorplan3d;

import android.app.Activity;
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

        // Hidden re-config: long-press the top-right corner to reopen settings.
        View hot = new View(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.TOP | Gravity.END);
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
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
