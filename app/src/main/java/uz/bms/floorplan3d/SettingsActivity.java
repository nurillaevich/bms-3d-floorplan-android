package uz.bms.floorplan3d;

import android.app.Activity;
import android.app.ActivityManager;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;


import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * First-run / re-config screen: Home Assistant address + long-lived token, plus a
 * one-tap "update the app" button that pulls the latest signed APK straight from
 * this project's GitHub releases and launches the installer.
 */
public class SettingsActivity extends Activity {

    // Where the auto-update looks for a newer APK. We resolve the latest tag via
    // the WEB "latest" redirect (github.com/.../releases/latest → 302 to
    // /releases/tag/<tag>), NOT the REST API (api.github.com): the API caps
    // unauthenticated calls at 60/hour per IP and returns HTTP 403 once that is
    // spent, whereas the web redirect + the release asset CDN are not so limited.
    private static final String RELEASES_LATEST =
            "https://github.com/nurillaevich/bms-3d-floorplan-android/releases/latest";
    private static final String RELEASE_DL =
            "https://github.com/nurillaevich/bms-3d-floorplan-android/releases/download/";
    private static final String APK_ASSET = "bms-3d-floorplan.apk";

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

        // --- Kiosk lock ---------------------------------------------------------
        View div2 = new View(this);
        div2.setBackgroundColor(0xFF262A33);
        LinearLayout.LayoutParams dl2 = new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(d)));
        dl2.topMargin = Math.round(22 * d);
        div2.setLayoutParams(dl2);
        box.addView(div2);

        CheckBox kiosk = new CheckBox(this);
        kiosk.setText("Режим киоска (блокировать выход)");
        kiosk.setTextColor(0xFFFFFFFF);
        kiosk.setChecked(p.getBoolean(MainActivity.KEY_KIOSK, true));
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-1, -2);
        kp.topMargin = Math.round(12 * d);
        kiosk.setLayoutParams(kp);
        kiosk.setOnCheckedChangeListener((b, checked) -> {
            p.edit().putBoolean(MainActivity.KEY_KIOSK, checked).apply();
            if (!checked) stopKioskLock(); // unlock right away
        });
        box.addView(kiosk);

        // --- Remote control (HTTP) -------------------------------------------
        // Lets Home Assistant drive the panel over the LAN:
        //   http://<tablet-ip>:<port>/?cmd=screenOn&password=<pass>
        // Off by default: it opens a port on the local network, so it should be
        // a deliberate choice rather than something a panel quietly ships with.
        TextView rcTitle = new TextView(this);
        rcTitle.setText("Удалённое управление (HTTP)");
        rcTitle.setTextColor(0xFFFFFFFF);
        rcTitle.setTextSize(16);
        LinearLayout.LayoutParams rtp = new LinearLayout.LayoutParams(-1, -2);
        rtp.topMargin = Math.round(22 * d);
        rcTitle.setLayoutParams(rtp);
        box.addView(rcTitle);

        final TextView rcHint = new TextView(this);
        rcHint.setTextColor(0xFFB9C0CC);
        rcHint.setTextSize(13);
        LinearLayout.LayoutParams rhp = new LinearLayout.LayoutParams(-1, -2);
        rhp.topMargin = Math.round(6 * d);
        rcHint.setLayoutParams(rhp);
        box.addView(rcHint);

        final EditText rcPort = field(
                p.getString(RemoteHttpService.KEY_PORT, String.valueOf(RemoteHttpService.DEFAULT_PORT)),
                "Порт (по умолчанию 2323)", InputType.TYPE_CLASS_NUMBER, d, box);
        final EditText rcPass = field(
                p.getString(RemoteHttpService.KEY_PASSWORD, RemoteHttpService.DEFAULT_PASSWORD),
                "Пароль", InputType.TYPE_CLASS_TEXT, d, box);

        final CheckBox rc = new CheckBox(this);
        rc.setText("Включить удалённое управление");
        rc.setTextColor(0xFFFFFFFF);
        rc.setChecked(RemoteHttpService.isEnabled(this));
        LinearLayout.LayoutParams rcp = new LinearLayout.LayoutParams(-1, -2);
        rcp.topMargin = Math.round(12 * d);
        rc.setLayoutParams(rcp);
        box.addView(rc);

        final Runnable refreshRcHint = () -> {
            if (!RemoteHttpService.isEnabled(this)) {
                rcHint.setText("Выключено. Включите, чтобы управлять панелью из Home Assistant.");
                return;
            }
            String ip = RemoteHttpService.localIp(this);
            rcHint.setText("http://" + (ip == null ? "<ip-планшета>" : ip) + ":"
                    + RemoteHttpService.port(this) + "/?cmd=screenOn&password="
                    + RemoteHttpService.password(this)
                    + "

Команды: screenOn, screenOff, reload, loadStartUrl,
"
                    + "loadUrl&url=..., getInfo");
        };
        refreshRcHint.run();

        // Port/password are read live by the server, so save on edit and restart
        // it — otherwise a changed port keeps serving on the old one.
        final Runnable saveRc = () -> {
            p.edit()
                    .putString(RemoteHttpService.KEY_PORT, rcPort.getText().toString().trim())
                    .putString(RemoteHttpService.KEY_PASSWORD, rcPass.getText().toString().trim())
                    .apply();
            if (RemoteHttpService.isEnabled(this)) {
                stopService(new Intent(this, RemoteHttpService.class));
            }
            RemoteHttpService.sync(this);
            refreshRcHint.run();
        };
        rcPort.setOnFocusChangeListener((v, has) -> { if (!has) saveRc.run(); });
        rcPass.setOnFocusChangeListener((v, has) -> { if (!has) saveRc.run(); });

        rc.setOnCheckedChangeListener((b, checked) -> {
            p.edit().putBoolean(RemoteHttpService.KEY_ENABLED, checked).apply();
            saveRc.run();
            Toast.makeText(this,
                    checked ? "Удалённое управление включено" : "Удалённое управление выключено",
                    Toast.LENGTH_SHORT).show();
        });

        // Open the Android "home app" picker (the «Рабочий стол» list) so the user
        // can set this app as the default desktop — then Home returns to the kiosk.
        Button home = new Button(this);
        home.setText("Сделать домашним экраном");
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = Math.round(12 * d);
        home.setLayoutParams(hlp);
        home.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(this,
                        "Откройте: Настройки → Приложения → Приложение по умолчанию → Рабочий стол",
                        Toast.LENGTH_LONG).show();
            }
        });
        box.addView(home);

        Button exit = new Button(this);
        exit.setText("Выйти из приложения");
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
        ep.topMargin = Math.round(12 * d);
        exit.setLayoutParams(ep);
        exit.setOnClickListener(v -> {
            stopKioskLock();
            finishAffinity(); // close the whole app → back to the home screen
        });
        box.addView(exit);

        root.addView(box);
        setContentView(root);
    }

    /** Leave Lock Task Mode if we're in it (so the app can be exited). */
    private void stopKioskLock() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                stopLockTask();
            }
        } catch (Exception ignored) {
            // not pinned / OEM quirk — nothing to undo
        }
    }

    /** Fetch the latest release, download its APK, and launch the installer. */
    private void checkAndUpdate() {
        update.setEnabled(false);
        update.setText("Проверка обновлений…");
        new Thread(() -> {
            try {
                String tag = resolveLatestTag(); // e.g. "v1.1.25"
                if (tag == null || tag.isEmpty()) {
                    fail("Не удалось определить последнюю версию");
                    return;
                }
                // Already on the latest tag — nothing to download.
                if (tag.equals("v" + installedVersion())) {
                    ui.post(() -> {
                        update.setEnabled(true);
                        update.setText("Обновить приложение");
                        Toast.makeText(this, "Уже установлена последняя версия (" + tag + ")",
                                Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                final String apkUrl = RELEASE_DL + tag + "/" + APK_ASSET;
                ui.post(() -> update.setText("Загрузка " + tag + "…"));

                File apk = new File(getExternalCacheDir(), "update.apk");
                download(apkUrl, apk);
                ui.post(() -> installApk(apk));
            } catch (Exception e) {
                fail("Не удалось обновить: " + e.getMessage());
            }
        }).start();
    }

    /** Resolve the latest release's tag (e.g. "v1.1.25") from the web "latest"
     *  redirect's Location header. Avoids the rate-limited REST API (which 403s
     *  after 60 unauthenticated requests/hour), so the updater keeps working. */
    private String resolveLatestTag() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(RELEASES_LATEST).openConnection();
        try {
            c.setInstanceFollowRedirects(false); // read the redirect target ourselves
            c.setRequestProperty("User-Agent", "bms-3d-floorplan-android");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            if (loc != null && (code == 301 || code == 302 || code == 303 || code == 307 || code == 308)) {
                // .../releases/tag/v1.1.25  ->  v1.1.25
                int slash = loc.lastIndexOf('/');
                if (slash >= 0 && slash < loc.length() - 1) return loc.substring(slash + 1);
            }
            throw new Exception("GitHub HTTP " + code);
        } finally {
            c.disconnect();
        }
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
