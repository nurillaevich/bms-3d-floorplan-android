package uz.bms.floorplan3d;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

import fi.iki.elonen.NanoHTTPD;

/**
 * A small HTTP control endpoint so Home Assistant (rest_command) can drive the
 * wall tablet:
 *
 *   http://&lt;tablet-ip&gt;:2323/?cmd=screenOn&amp;password=1234
 *
 * Commands: screenOn, screenOff, reload, loadUrl (&amp;url=…), loadStartUrl,
 * getInfo. Add &amp;type=json for a JSON reply instead of plain text.
 *
 * Every request must carry the correct &amp;password= — without it the endpoint
 * would let anyone on the LAN wake the screen or point the kiosk at a page of
 * their choosing. It runs as a foreground service because Android will
 * otherwise stop a background service (and with it the endpoint) within minutes.
 */
public class RemoteHttpService extends Service {

    public static final String KEY_ENABLED = "remote_enabled";
    public static final String KEY_PORT = "remote_port";
    public static final String KEY_PASSWORD = "remote_password";
    public static final int DEFAULT_PORT = 2323;
    public static final String DEFAULT_PASSWORD = "1234";

    private static final int NOTIF_ID = 7321;
    private static final String CHANNEL_ID = "kiosk_remote";
    /** How often this tablet re-announces itself in the intercom directory. */
    private static final long HEARTBEAT_MS = 60_000L;

    private SharedPreferences prefs;
    private ControlServer server;
    private final android.os.Handler clock =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable heartbeat;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false);
    }

    public static int port(Context c) {
        String v = sp(c).getString(KEY_PORT, null);
        if (v == null || v.trim().isEmpty()) return DEFAULT_PORT;
        try {
            int p = Integer.parseInt(v.trim());
            return (p > 0 && p < 65536) ? p : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    public static String password(Context c) {
        String v = sp(c).getString(KEY_PASSWORD, null);
        return (v == null || v.isEmpty()) ? DEFAULT_PASSWORD : v;
    }

    /**
     * The endpoint also carries intercom signalling, so it must run whenever
     * EITHER feature is on — a tablet with remote control switched off still has
     * to be reachable when another panel rings it.
     */
    public static boolean isNeeded(Context c) {
        return isEnabled(c) || Intercom.isEnabled(c);
    }

    /** Start (or stop) the endpoint to match the current setting. */
    public static void sync(Context c) {
        Intent i = new Intent(c, RemoteHttpService.class);
        if (isNeeded(c)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } else {
            c.stopService(i);
        }
    }

    /** The tablet's LAN address, so the settings screen can show the real URL. */
    public static String localIp(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format("%d.%d.%d.%d",
                            ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
                }
            }
        } catch (Exception ignored) {
            // Falls through to the interface scan — Wi-Fi info needs permissions
            // on some ROMs, and a tablet may be on Ethernet anyway.
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = sp(this);
        startInForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isNeeded(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server == null) {
            try {
                server = new ControlServer(port(this));
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            } catch (Exception e) {
                // Port busy or no network yet — the kiosk itself must keep running,
                // so swallow it. START_STICKY gives us another go later.
                server = null;
            }
        }
        startHeartbeat();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopHeartbeat();
        if (server != null) {
            server.stop();
            server = null;
        }
        super.onDestroy();
    }

    // --- intercom directory heartbeat ---------------------------------------

    /**
     * Re-publish this tablet in the Home Assistant directory every minute.
     *
     * It repeats rather than registering once because states pushed over HA's
     * REST API do not survive a Home Assistant restart, and because a tablet's
     * address can move with its DHCP lease. Repeating turns both into a problem
     * that fixes itself within a minute instead of one that needs a ladder.
     */
    private void startHeartbeat() {
        stopHeartbeat();
        if (!Intercom.isEnabled(this)) return;
        heartbeat = new Runnable() {
            @Override
            public void run() {
                if (Intercom.isEnabled(RemoteHttpService.this)) {
                    new Thread(() -> {
                        try {
                            IntercomRegistry.publish(RemoteHttpService.this);
                        } catch (Exception ignored) {
                            // HA unreachable right now — the next beat retries.
                            // The intercom is still answerable meanwhile: only
                            // the directory listing goes stale, not the phone.
                        }
                    }, "intercom-register").start();
                }
                clock.postDelayed(this, HEARTBEAT_MS);
            }
        };
        clock.post(heartbeat);
    }

    private void stopHeartbeat() {
        if (heartbeat != null) {
            clock.removeCallbacks(heartbeat);
            heartbeat = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "Remote control", NotificationManager.IMPORTANCE_MIN));
            }
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        String what = Intercom.isEnabled(this)
                ? (isEnabled(this) ? "Интерком и удалённое управление" : "Интерком")
                : "Удалённое управление";
        Notification n = b
                .setContentTitle("BMS 3D Floor Plan")
                .setContentText(what + ": порт " + port(this))
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    /** Wake the display and bring the kiosk forward. */
    private void wakeScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "bms3d:screenOn");
                // Held only long enough to wake it; the device timeout resumes after.
                wl.acquire(5000);
                if (wl.isHeld()) wl.release();
            }
        } catch (Exception ignored) {
        }
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /** Screen off needs device-admin; without it we report failure rather than lie. */
    private boolean turnScreenOff() {
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return false;
            dpm.lockNow();
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private class ControlServer extends NanoHTTPD {
        ControlServer(int port) {
            super(port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            String cmd = first(session, "cmd");
            String pass = first(session, "password");
            String url = first(session, "url");
            boolean json = "json".equals(first(session, "type"));

            // Intercom signalling comes from another tablet, not from Home
            // Assistant, and is authenticated with the intercom key instead of
            // the remote-control password — so ringing a panel never implies the
            // right to drive its screen, and vice versa.
            if (cmd != null && cmd.startsWith("intercom")) {
                return intercom(session, cmd, json);
            }

            if (!password(RemoteHttpService.this).equals(pass)) {
                return reply(json, Response.Status.FORBIDDEN, "error", "wrong password", cmd);
            }
            if (!isEnabled(RemoteHttpService.this)) {
                // The endpoint is only up for the intercom's sake.
                return reply(json, Response.Status.FORBIDDEN, "error",
                        "remote control is switched off", cmd);
            }

            if ("getInfo".equals(cmd) || "deviceInfo".equals(cmd)) return deviceInfo();

            boolean ok;
            switch (cmd == null ? "" : cmd) {
                case "screenOn":
                    wakeScreen();
                    ok = true;
                    break;
                case "screenOff":
                    ok = turnScreenOff();
                    break;
                case "loadUrl":
                    ok = url != null && !url.isEmpty()
                            && KioskBus.send(new KioskBus.Command("loadUrl", url));
                    break;
                case "loadStartUrl":
                    ok = KioskBus.send(new KioskBus.Command("loadStartUrl", null));
                    break;
                case "reload":
                case "reloadPage":
                    ok = KioskBus.send(new KioskBus.Command("reload", null));
                    break;
                default:
                    ok = false;
            }
            return reply(json,
                    ok ? Response.Status.OK : Response.Status.BAD_REQUEST,
                    ok ? "OK" : "error",
                    ok ? "command executed" : "unknown command, or the kiosk screen isn't open",
                    cmd);
        }

        /**
         * Ring / answer / hang up, from another tablet on the LAN.
         *
         * Kept on the same port as the rest of the endpoint so a house needs one
         * firewall rule and one open port, not two.
         */
        private Response intercom(IHTTPSession session, String cmd, boolean json) {
            Intercom in = Intercom.get(RemoteHttpService.this);
            if (!Intercom.isEnabled(RemoteHttpService.this)) {
                return intercomReply(json, Response.Status.FORBIDDEN, "error",
                        "intercom is switched off");
            }
            int callId = num(first(session, "callId"), 0);
            if (!in.authorized(first(session, "key"), callId)) {
                return intercomReply(json, Response.Status.FORBIDDEN, "error", "denied");
            }

            String result;
            switch (cmd) {
                case "intercomInvite":
                    // The caller's address comes from the connection, not from a
                    // parameter: it is the one thing in the invite that cannot be
                    // spoofed into pointing the voice stream somewhere else.
                    result = in.onInvite(
                            session.getRemoteIpAddress(),
                            first(session, "from"),
                            first(session, "name"),
                            num(first(session, "httpPort"), DEFAULT_PORT),
                            num(first(session, "audioPort"), 0),
                            first(session, "replyKey"),
                            callId);
                    if ("ringing".equals(result)) wakeScreen();
                    break;
                case "intercomAccept":
                    result = in.onAccepted(callId, num(first(session, "audioPort"), 0));
                    break;
                case "intercomReject":
                    result = in.onRejected(callId);
                    break;
                case "intercomBye":
                    result = in.onBye(callId);
                    break;
                default:
                    return intercomReply(json, Response.Status.BAD_REQUEST, "error",
                            "unknown intercom command");
            }
            return intercomReply(json, Response.Status.OK, "OK", result);
        }

        private Response intercomReply(boolean json, Response.Status http,
                                       String status, String result) {
            if (json) {
                String body;
                try {
                    body = new JSONObject()
                            .put("status", status)
                            .put("result", result)
                            .toString();
                } catch (Exception e) {
                    body = "{\"status\":\"error\"}";
                }
                return newFixedLengthResponse(http, "application/json", body);
            }
            return newFixedLengthResponse(http, "text/plain", status + ": " + result);
        }

        private int num(String v, int fallback) {
            if (v == null || v.trim().isEmpty()) return fallback;
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private String first(IHTTPSession s, String key) {
            java.util.List<String> v = s.getParameters().get(key);
            return (v == null || v.isEmpty()) ? null : v.get(0);
        }

        private Response reply(boolean json, Response.Status http,
                               String status, String message, String cmd) {
            if (json) {
                String body;
                try {
                    body = new JSONObject()
                            .put("status", status)
                            .put("cmd", cmd == null ? "" : cmd)
                            .put("message", message)
                            .toString();
                } catch (Exception e) {
                    body = "{\"status\":\"error\"}";
                }
                return newFixedLengthResponse(http, "application/json", body);
            }
            return newFixedLengthResponse(http, "text/plain", status + ": " + message);
        }

        private Response deviceInfo() {
            String body;
            try {
                body = new JSONObject()
                        .put("status", "OK")
                        .put("appName", "BMS 3D Floor Plan")
                        .put("package", getPackageName())
                        .put("version", BuildConfig.VERSION_NAME)
                        .put("haUrl", prefs.getString(MainActivity.KEY_URL, ""))
                        .put("kioskMode", prefs.getBoolean(MainActivity.KEY_KIOSK, false))
                        .put("port", port(RemoteHttpService.this))
                        .put("screenOpen", KioskBus.hasHandler())
                        .put("model", Build.MODEL)
                        .put("manufacturer", Build.MANUFACTURER)
                        .put("androidVersion", Build.VERSION.RELEASE)
                        .toString();
            } catch (Exception e) {
                body = "{\"status\":\"error\"}";
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", body);
        }
    }
}
