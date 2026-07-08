package uz.bms.floorplan3d;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * First-run / re-config screen: Home Assistant address + long-lived token, plus a
 * one-tap "update the app" button that pulls the latest signed APK straight from
 * this project's GitHub releases and launches the installer.
 */
public class SettingsActivity extends Activity {

    // Where the auto-update looks for a newer APK.
    private static final String RELEASES_API =
            "https://api.github.com/repos/nurillaevich/bms-3d-floorplan-android/releases/latest";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Button update;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final SharedPreferences p = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        final float d = getResources().getDisplayMetrics().density;
        final int pad = Math.round(24 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF14161B);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(Math.round(460 * d), -2);
        box.setLayoutParams(boxLp);

        TextView title = new TextView(this);
        title.setText("BMS 3D Floor Plan");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(22);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Адрес Home Assistant и долгосрочный токен доступа. Планшет и HA в одной сети Wi-Fi.");
        hint.setTextColor(0xFF99A0AC);
        hint.setTextSize(13);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = Math.round(8 * d);
        hint.setLayoutParams(hp);
        box.addView(hint);

        final EditText url = field(p.getString(MainActivity.KEY_URL, ""), "http://192.168.1.50:8123",
                InputType.TYPE_TEXT_VARIATION_URI, d, box);
        final EditText token = field(p.getString(MainActivity.KEY_TOKEN, ""), "Долгосрочный токен",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, d, box);

        Button save = new Button(this);
        save.setText("Сохранить и подключиться");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = Math.round(18 * d);
        save.setLayoutParams(sp);
        save.setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            String t = token.getText().toString().trim();
            if (u.isEmpty() || t.isEmpty()) {
                Toast.makeText(this, "Заполните оба поля", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!u.matches("(?i)^https?://.*")) u = "http://" + u;
            p.edit().putString(MainActivity.KEY_URL, u).putString(MainActivity.KEY_TOKEN, t).apply();
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
        box.addView(save);

        // --- GitHub auto-update -------------------------------------------------
        View div = new View(this);
        div.setBackgroundColor(0xFF262A33);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(d)));
        dl.topMargin = Math.round(22 * d);
        div.setLayoutParams(dl);
        box.addView(div);

        update = new Button(this);
        update.setText("Обновить приложение");
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-1, -2);
        up.topMargin = Math.round(16 * d);
        update.setLayoutParams(up);
        update.setOnClickListener(v -> checkAndUpdate());
        box.addView(update);

        TextView ver = new TextView(this);
        ver.setText("Установлено: v" + installedVersion() + " · источник: GitHub");
        ver.setTextColor(0xFF677079);
        ver.setTextSize(12);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, -2);
        vp.topMargin = Math.round(8 * d);
        ver.setLayoutParams(vp);
        box.addView(ver);

        root.addView(box);
        setContentView(root);
    }

    /** Fetch the latest release, download its APK, and launch the installer. */
    private void checkAndUpdate() {
        update.setEnabled(false);
        update.setText("Проверка обновлений…");
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(httpGet(RELEASES_API));
                String tag = rel.optString("tag_name", "");
                String apkUrl = null;
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", null);
                        break;
                    }
                }
                if (apkUrl == null) {
                    fail("В последнем релизе нет .apk файла");
                    return;
                }

                final String label = tag.isEmpty() ? "" : (" " + tag);
                ui.post(() -> update.setText("Загрузка" + label + "…"));

                File apk = new File(getExternalCacheDir(), "update.apk");
                download(apkUrl, apk);
                ui.post(() -> installApk(apk));
            } catch (Exception e) {
                fail("Не удалось обновить: " + e.getMessage());
            }
        }).start();
    }

    private void installApk(File apk) {
        try {
            // Android 8+: installing an APK needs "install unknown apps" granted to
            // THIS app first, or the install intent silently does nothing. Send the
            // user to that toggle, then they tap "Обновить" again.
            if (!getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(this,
                        "Разрешите установку из этого источника, затем нажмите «Обновить приложение» ещё раз",
                        Toast.LENGTH_LONG).show();
                Intent grant = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                grant.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(grant);
                update.setEnabled(true);
                update.setText("Обновить приложение");
                return;
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            update.setEnabled(true);
            update.setText("Обновить приложение");
        } catch (Exception e) {
            fail("Не удалось открыть установщик: " + e.getMessage());
        }
    }

    private String httpGet(String spec) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(spec).openConnection();
        try {
            c.setRequestProperty("User-Agent", "bms-3d-floorplan-android");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("GitHub HTTP " + code);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private void download(String spec, File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(spec).openConnection();
        try {
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "bms-3d-floorplan-android");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("Download HTTP " + code);
            try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) fo.write(buf, 0, n);
                fo.flush();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        try (InputStream in = is) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void fail(String msg) {
        ui.post(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            update.setEnabled(true);
            update.setText("Обновить приложение");
        });
    }

    private String installedVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private EditText field(String value, String hint, int inputType, float d, LinearLayout parent) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(0xFF677079);
        int gp = Math.round(10 * d);
        e.setPadding(gp, gp, gp, gp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Math.round(12 * d);
        e.setLayoutParams(lp);
        parent.addView(e);
        return e;
    }

    @Override
    public void onBackPressed() {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        // Only allow leaving settings once configured.
        if (!p.getString(MainActivity.KEY_URL, "").isEmpty()
                && !p.getString(MainActivity.KEY_TOKEN, "").isEmpty()) {
            super.onBackPressed();
        }
    }
}
