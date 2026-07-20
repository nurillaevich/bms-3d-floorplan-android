package uz.bms.floorplan3d;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Hosts whose TLS certificate the operator has explicitly accepted.
 *
 * Kit on a house LAN — intercoms, NVRs, gateways — almost always serves HTTPS
 * with a self-signed certificate, which the WebView refuses, so the panel shows
 * a blank frame with nothing to explain it.
 *
 * The tempting fix is to proceed() on every SSL error. That would also silence a
 * real man-in-the-middle on the Home Assistant connection this app carries a
 * long-lived token to — so instead each host is accepted once, by name, after
 * the operator is shown what is wrong with the certificate. Anything not on the
 * list still fails closed.
 */
public final class TrustedHosts {

    private static final String KEY = "trusted_ssl_hosts";

    private TrustedHosts() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isTrusted(Context c, String host) {
        if (host == null || host.isEmpty()) return false;
        return all(c).contains(host.toLowerCase());
    }

    public static void trust(Context c, String host) {
        if (host == null || host.isEmpty()) return;
        Set<String> s = new HashSet<>(all(c));
        s.add(host.toLowerCase());
        sp(c).edit().putStringSet(KEY, s).apply();
    }

    public static Set<String> all(Context c) {
        // Copy: a Set handed back by SharedPreferences must not be mutated.
        return new HashSet<>(sp(c).getStringSet(KEY, new HashSet<>()));
    }

    public static void clear(Context c) {
        sp(c).edit().remove(KEY).apply();
    }

    /** Host part of a URL, for matching and for showing in the prompt. */
    public static String hostOf(String url) {
        try {
            return new java.net.URI(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
