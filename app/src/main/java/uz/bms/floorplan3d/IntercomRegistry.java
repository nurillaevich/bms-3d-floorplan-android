package uz.bms.floorplan3d;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The tablet directory, kept in Home Assistant.
 *
 * Each panel writes ONE state entity describing itself —
 * {@code sensor.bms_intercom_<device id>} with its display name, LAN address and
 * intercom key — and reads the others back from the same place. That is the
 * whole of the configuration: give a tablet a name and it appears on every other
 * tablet's list by itself. Nothing has to be entered twice, and nothing has to
 * be updated when a DHCP lease moves a panel to a new address.
 *
 * HA is used because every tablet is already talking to it with a long-lived
 * token, so no extra server, broker or discovery protocol has to exist. States
 * pushed over the REST API do not survive a Home Assistant restart, which is
 * exactly why {@link RemoteHttpService} re-publishes on a heartbeat — the
 * directory heals itself within a minute instead of needing a visit.
 */
final class IntercomRegistry {

    /** Entities are prefixed so a peer can be told apart from anything else. */
    static final String PREFIX = "sensor.bms_intercom_";

    private IntercomRegistry() {}

    static String entityId(String deviceId) {
        return PREFIX + deviceId;
    }

    private static String haBase(Context c) {
        SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        return Http.normalizeBase(p.getString(MainActivity.KEY_URL, ""));
    }

    private static String token(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(MainActivity.KEY_TOKEN, "");
    }

    /**
     * Publish (or refresh) this tablet's entry. Blocking — call from a worker.
     *
     * The {@code heartbeat} attribute changes on every write on purpose: HA only
     * bumps last_updated when the state or an attribute actually changes, and
     * without it a panel that has been off for a day would still look fresh.
     */
    static void publish(Context c) throws Exception {
        String base = haBase(c);
        String tok = token(c);
        if (base.isEmpty() || tok.isEmpty()) throw new Exception("Home Assistant не настроен");

        String name = Intercom.deviceName(c);
        if (name.isEmpty()) throw new Exception("Имя планшета не задано");

        String ip = RemoteHttpService.localIp(c);
        if (ip == null) throw new Exception("Нет адреса в сети");

        JSONObject attrs = new JSONObject()
                .put("friendly_name", "Интерком: " + name)
                .put("icon", "mdi:deskphone")
                .put("bms_intercom", true)
                .put("device_id", Intercom.deviceId(c))
                .put("device_name", name)
                .put("ip", ip)
                .put("port", RemoteHttpService.port(c))
                // Signs an invite addressed to this tablet. Readable by anyone
                // with access to this Home Assistant — which is the same set of
                // people who could already control the house, so the intercom is
                // not the weak link. It is NOT the remote-control password.
                .put("key", Intercom.key(c))
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("heartbeat", System.currentTimeMillis() / 1000L);

        String body = new JSONObject()
                .put("state", "online")
                .put("attributes", attrs)
                .toString();

        Http.postJson(base + "/api/states/" + entityId(Intercom.deviceId(c)), tok, body, 10000);
    }

    /**
     * Every other tablet in the house. Blocking — call from a worker.
     *
     * Reads the whole state machine (there is no server-side filter in HA's REST
     * API) and keeps the intercom entities. On a big installation that is a few
     * hundred KB, so it is fetched when the call list is on screen rather than
     * on a timer.
     */
    static List<IntercomPeer> peers(Context c) throws Exception {
        String base = haBase(c);
        String tok = token(c);
        if (base.isEmpty() || tok.isEmpty()) throw new Exception("Home Assistant не настроен");

        String self = Intercom.deviceId(c);
        String json = Http.get(base + "/api/states", tok, 15000);
        JSONArray all = new JSONArray(json);
        List<IntercomPeer> out = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject st = all.optJSONObject(i);
            if (st == null) continue;
            String id = st.optString("entity_id", "");
            if (!id.startsWith(PREFIX)) continue;
            JSONObject a = st.optJSONObject("attributes");
            if (a == null || !a.optBoolean("bms_intercom", false)) continue;

            String deviceId = a.optString("device_id", id.substring(PREFIX.length()));
            if (self.equals(deviceId)) continue; // ourselves

            String ip = a.optString("ip", "");
            String key = a.optString("key", "");
            if (ip.isEmpty() || key.isEmpty()) continue; // half-written entry

            out.add(new IntercomPeer(
                    deviceId,
                    a.optString("device_name", deviceId),
                    ip,
                    a.optInt("port", RemoteHttpService.DEFAULT_PORT),
                    key,
                    a.optString("app_version", ""),
                    parseHaTime(st.optString("last_updated", ""))));
        }
        Collections.sort(out, (x, y) -> x.name.compareToIgnoreCase(y.name));
        return out;
    }

    /** Remove this tablet from the directory (intercom switched off). */
    static void unpublish(Context c) throws Exception {
        String base = haBase(c);
        String tok = token(c);
        if (base.isEmpty() || tok.isEmpty()) return;
        String body = new JSONObject()
                .put("state", "offline")
                .put("attributes", new JSONObject()
                        .put("friendly_name", "Интерком: " + Intercom.deviceName(c))
                        .put("bms_intercom", false))
                .toString();
        Http.postJson(base + "/api/states/" + entityId(Intercom.deviceId(c)), tok, body, 10000);
    }

    /**
     * "2026-07-21T09:14:03.123456+00:00" → epoch ms. HA reports UTC; only the
     * seconds part is parsed, because the fractional digits and the "+00:00"
     * colon are both awkward for SimpleDateFormat on old Android.
     */
    private static long parseHaTime(String v) {
        if (v == null || v.length() < 19) return 0L;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(v.substring(0, 19));
            return (d == null) ? 0L : d.getTime();
        } catch (Exception e) {
            return 0L;
        }
    }
}
