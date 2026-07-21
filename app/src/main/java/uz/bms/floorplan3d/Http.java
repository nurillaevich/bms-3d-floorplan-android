package uz.bms.floorplan3d;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Blocking HTTP helpers for the small LAN calls this app makes: Home Assistant's
 * REST API (the intercom directory) and tablet-to-tablet signalling.
 *
 * Every call must run off the UI thread.
 *
 * The TLS trust here is deliberately permissive: a home Home Assistant almost
 * always serves a self-signed certificate on the LAN, and the WebView already
 * follows the same rule via {@link TrustedHosts}. It only ever applies to hosts
 * the operator typed into settings or to peers discovered inside their own HA.
 */
final class Http {

    private Http() {}

    static HttpURLConnection openTrusting(URL url) throws Exception {
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

    /** GET, optionally with a bearer token. Throws on any non-2xx. */
    static String get(String url, String bearer, int timeoutMs) throws Exception {
        HttpURLConnection c = openTrusting(new URL(url));
        try {
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            if (bearer != null && !bearer.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            return readOrFail(c);
        } finally {
            c.disconnect();
        }
    }

    /** POST a JSON body with a bearer token. Throws on any non-2xx. */
    static String postJson(String url, String bearer, String body, int timeoutMs) throws Exception {
        HttpURLConnection c = openTrusting(new URL(url));
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Content-Type", "application/json");
            if (bearer != null && !bearer.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(out);
            }
            return readOrFail(c);
        } finally {
            c.disconnect();
        }
    }

    private static String readOrFail(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        String text = (in == null) ? "" : slurp(in);
        if (code < 200 || code >= 300) {
            // Truncated: an HA error page can be a full HTML document, and the
            // whole thing in a toast helps nobody.
            String head = text.length() > 200 ? text.substring(0, 200) : text;
            throw new Exception("HTTP " + code + (head.isEmpty() ? "" : (": " + head)));
        }
        return text;
    }

    private static String slurp(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Normalize a base URL: add a scheme if missing, drop trailing slashes. */
    static String normalizeBase(String u) {
        if (u == null) return "";
        u = u.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** URL-encode a query-string value. */
    static String enc(String v) {
        try {
            return java.net.URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
