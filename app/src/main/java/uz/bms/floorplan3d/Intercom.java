package uz.bms.floorplan3d;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The intercom: one voice call at a time between the tablets in a house.
 *
 * Three pieces, deliberately kept apart:
 * <ul>
 *   <li>{@link IntercomRegistry} — who exists, published to Home Assistant, so a
 *       new panel is configured by typing its name and nothing else.</li>
 *   <li>signalling — ring, answer, hang up — over the tablet's existing HTTP
 *       control endpoint ({@link RemoteHttpService}, port 2323).</li>
 *   <li>{@link AudioLink} — the voice itself, plain PCM over UDP, tablet to
 *       tablet, never touching Home Assistant or the internet.</li>
 * </ul>
 *
 * An invite is signed with the callee's own intercom key, which the caller read
 * from the directory; the callee's answers are signed with a one-shot key the
 * caller minted for that call, so a reply can only ever belong to the call it
 * was issued for. Neither is the remote-control password — turning the intercom
 * on does not hand anyone the ability to drive the kiosk.
 */
public final class Intercom {

    // --- settings -----------------------------------------------------------

    public static final String KEY_ENABLED = "intercom_enabled";
    public static final String KEY_NAME = "intercom_name";
    public static final String KEY_DEVICE_ID = "intercom_device_id";
    public static final String KEY_SECRET = "intercom_key";
    /** Play the far end on the media stream instead of the voice-call one. */
    public static final String KEY_MEDIA_STREAM = "intercom_media_stream";

    /** No answer after this long and the call gives up by itself. */
    private static final long RING_TIMEOUT_MS = 45_000L;

    private static final String CALL_CHANNEL = "kiosk_intercom_call";
    private static final int CALL_NOTIF_ID = 7322;

    public enum State { IDLE, OUTGOING, INCOMING, ACTIVE }

    public interface Listener {
        /** Something about the call changed; re-read the getters. */
        void onIntercomChanged();
    }

    // --- singleton ----------------------------------------------------------

    private static volatile Intercom instance;

    public static Intercom get(Context c) {
        Intercom i = instance;
        if (i == null) {
            synchronized (Intercom.class) {
                i = instance;
                if (i == null) {
                    i = new Intercom(c.getApplicationContext());
                    instance = i;
                }
            }
        }
        return i;
    }

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();

    private State state = State.IDLE;
    private Call call;
    private AudioLink audio;
    private String lastError;
    private MediaPlayer ringer;
    private ToneGenerator ringback;
    private volatile Runnable ringTimeout;

    private Intercom(Context app) {
        this.app = app;
    }

    /** One call in flight. Immutable except for the audio ports. */
    private static final class Call {
        int id;
        boolean incoming;
        String peerId = "";
        String peerName = "";
        String peerIp = "";
        int peerHttpPort = RemoteHttpService.DEFAULT_PORT;
        /** Outgoing only: the callee's key, read from the directory. */
        String peerKey = "";
        /** Signs everything the callee sends back about THIS call. */
        String replyKey = "";
        DatagramSocket socket;
        int remoteAudioPort;
        long activeSince;
    }

    // --- configuration ------------------------------------------------------

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false) && !deviceName(c).isEmpty();
    }

    public static String deviceName(Context c) {
        return sp(c).getString(KEY_NAME, "").trim();
    }

    public static boolean mediaStream(Context c) {
        return sp(c).getBoolean(KEY_MEDIA_STREAM, false);
    }

    /**
     * A stable id for this tablet, minted once. Names are what people see and
     * change ("Кухня" becomes "Кухня 1-этаж"); the directory entity must not
     * move when they do, or the panel would appear twice.
     */
    public static synchronized String deviceId(Context c) {
        SharedPreferences p = sp(c);
        String v = p.getString(KEY_DEVICE_ID, "");
        if (v.isEmpty()) {
            v = randomHex(4);
            p.edit().putString(KEY_DEVICE_ID, v).apply();
        }
        return v;
    }

    /** The secret that signs invites addressed to this tablet. */
    public static synchronized String key(Context c) {
        SharedPreferences p = sp(c);
        String v = p.getString(KEY_SECRET, "");
        if (v.isEmpty()) {
            v = randomHex(16);
            p.edit().putString(KEY_SECRET, v).apply();
        }
        return v;
    }

    // --- state for the UI ---------------------------------------------------

    public synchronized State state() {
        return state;
    }

    public synchronized boolean busy() {
        return state != State.IDLE;
    }

    public synchronized String peerName() {
        return (call == null) ? "" : call.peerName;
    }

    /** When the voice link came up, for the on-screen timer. 0 when not active. */
    public synchronized long activeSince() {
        return (call == null) ? 0L : call.activeSince;
    }

    /** Consumed by the call screen so a failure is shown once, then cleared. */
    public synchronized String takeError() {
        String e = lastError;
        lastError = null;
        return e;
    }

    private synchronized void setError(String e) {
        lastError = e;
    }

    public boolean muted() {
        AudioLink a = audio;
        return a != null && a.isMuted();
    }

    public void setMuted(boolean m) {
        AudioLink a = audio;
        if (a != null) {
            a.setMuted(m);
            notifyChanged();
        }
    }

    public void addListener(Listener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    private void notifyChanged() {
        ui.post(() -> {
            List<Listener> copy;
            synchronized (listeners) {
                copy = new ArrayList<>(listeners);
            }
            for (Listener l : copy) l.onIntercomChanged();
        });
    }

    // --- placing a call -----------------------------------------------------

    /** Ring another tablet. Returns false when one call is already up. */
    public boolean call(IntercomPeer peer) {
        final Call c;
        synchronized (this) {
            if (state != State.IDLE) return false;
            c = new Call();
            c.id = randomCallId();
            c.incoming = false;
            c.peerId = peer.deviceId;
            c.peerName = peer.name;
            c.peerIp = peer.ip;
            c.peerHttpPort = peer.port;
            c.peerKey = peer.key;
            c.replyKey = randomHex(16);
            try {
                c.socket = new DatagramSocket();
            } catch (Exception e) {
                lastError = "Не удалось открыть звуковой порт";
                notifyChanged();
                return false;
            }
            call = c;
            state = State.OUTGOING;
            lastError = null;
        }
        startRingback();
        armRingTimeout("Не отвечает");
        notifyChanged();

        final int audioPort = c.socket.getLocalPort();
        net.execute(() -> {
            try {
                String result = signal(c.peerIp, c.peerHttpPort, "intercomInvite", c.peerKey,
                        "&callId=" + c.id
                                + "&from=" + Http.enc(deviceId(app))
                                + "&name=" + Http.enc(deviceName(app))
                                + "&httpPort=" + RemoteHttpService.port(app)
                                + "&audioPort=" + audioPort
                                + "&replyKey=" + Http.enc(c.replyKey));
                if ("busy".equals(result)) end(c.id, "Занято");
                // "ringing" — now we wait for intercomAccept / intercomReject.
            } catch (Exception e) {
                end(c.id, "Планшет недоступен");
            }
        });
        return true;
    }

    /** Answer the call that is ringing. */
    public void accept() {
        final Call c;
        synchronized (this) {
            if (state != State.INCOMING || call == null) return;
            c = call;
            try {
                c.socket = new DatagramSocket();
            } catch (Exception e) {
                lastError = "Не удалось открыть звуковой порт";
                notifyChanged();
                hangup();
                return;
            }
        }
        stopRingtone();
        cancelRingTimeout();
        final int audioPort = c.socket.getLocalPort();
        net.execute(() -> {
            try {
                signal(c.peerIp, c.peerHttpPort, "intercomAccept", c.replyKey,
                        "&callId=" + c.id + "&audioPort=" + audioPort);
                startAudio(c);
            } catch (Exception e) {
                end(c.id, "Связь потеряна");
            }
        });
    }

    /** Decline a ringing call. */
    public void reject() {
        final Call c;
        synchronized (this) {
            if (state != State.INCOMING || call == null) return;
            c = call;
        }
        net.execute(() -> {
            try {
                signal(c.peerIp, c.peerHttpPort, "intercomReject", c.replyKey, "&callId=" + c.id);
            } catch (Exception ignored) {
                // The caller will time out on its own; nothing to recover here.
            }
        });
        end(c.id, null);
    }

    /** Hang up, or cancel a call that is still ringing. */
    public void hangup() {
        final Call c;
        synchronized (this) {
            if (call == null) return;
            c = call;
        }
        final String key = c.incoming ? c.replyKey : c.peerKey;
        net.execute(() -> {
            try {
                signal(c.peerIp, c.peerHttpPort, "intercomBye", key, "&callId=" + c.id);
            } catch (Exception ignored) {
            }
        });
        end(c.id, null);
    }

    // --- signalling coming in (called on the HTTP server's thread) -----------

    /**
     * True when {@code key} may act on {@code callId}: either it is this
     * tablet's own key (an invite addressed to us) or the one-shot key minted
     * for the call in flight (an answer to something we started).
     */
    synchronized boolean authorized(String key, int callId) {
        if (key == null || key.isEmpty()) return false;
        if (constantEquals(key, key(app))) return true;
        return call != null && call.id == callId
                && !call.replyKey.isEmpty() && constantEquals(key, call.replyKey);
    }

    /** @return "ringing", "busy", "ok" or "ignored" — echoed back to the peer. */
    String onInvite(String remoteIp, String fromId, String fromName, int httpPort,
                    int audioPort, String replyKey, int callId) {
        synchronized (this) {
            if (state != State.IDLE) return "busy";
            Call c = new Call();
            c.id = callId;
            c.incoming = true;
            c.peerId = fromId;
            c.peerName = (fromName == null || fromName.isEmpty()) ? "Планшет" : fromName;
            c.peerIp = remoteIp;
            c.peerHttpPort = httpPort;
            c.replyKey = (replyKey == null) ? "" : replyKey;
            c.remoteAudioPort = audioPort;
            call = c;
            state = State.INCOMING;
            lastError = null;
        }
        startRingtone();
        armRingTimeout(null);
        notifyChanged();
        ui.post(this::showIncomingUi);
        return "ringing";
    }

    String onAccepted(int callId, int remoteAudioPort) {
        final Call c;
        synchronized (this) {
            if (state != State.OUTGOING || call == null || call.id != callId) return "ignored";
            c = call;
            c.remoteAudioPort = remoteAudioPort;
        }
        stopRingback();
        cancelRingTimeout();
        net.execute(() -> startAudio(c));
        return "ok";
    }

    String onRejected(int callId) {
        end(callId, "Отклонено");
        return "ok";
    }

    String onBye(int callId) {
        end(callId, null);
        return "ok";
    }

    // --- call lifecycle -----------------------------------------------------

    private void startAudio(Call c) {
        try {
            InetAddress addr = InetAddress.getByName(c.peerIp);
            // The loss callback fires on the link's own receive thread, and
            // tearing the link down from inside it would have that thread wait
            // on itself — so bounce it onto the signalling worker.
            AudioLink link = new AudioLink(app, c.socket, addr, c.remoteAudioPort, c.id,
                    mediaStream(app), () -> net.execute(() -> end(c.id, "Связь потеряна")));
            link.start();
            synchronized (this) {
                if (call != c) { // hung up while we were opening the microphone
                    link.stop();
                    return;
                }
                audio = link;
                c.activeSince = System.currentTimeMillis();
                state = State.ACTIVE;
            }
            stopRingtone();
            stopRingback();
            cancelRingTimeout();
            notifyChanged();
        } catch (Exception e) {
            end(c.id, "Микрофон недоступен");
        }
    }

    /** Tear a call down, whoever asked. Safe to call more than once. */
    private void end(int callId, String error) {
        AudioLink link;
        DatagramSocket sock;
        synchronized (this) {
            if (call == null || call.id != callId) return;
            link = audio;
            sock = call.socket;
            audio = null;
            call = null;
            state = State.IDLE;
            if (error != null) lastError = error;
        }
        stopRingtone();
        stopRingback();
        cancelRingTimeout();
        clearIncomingNotification();
        if (link != null) {
            link.stop(); // closes the socket too
        } else if (sock != null) {
            try { sock.close(); } catch (Exception ignored) {}
        }
        notifyChanged();
    }

    private void armRingTimeout(final String error) {
        cancelRingTimeout();
        final int id;
        synchronized (this) {
            if (call == null) return;
            id = call.id;
        }
        ringTimeout = () -> {
            // An unanswered call must not ring the house forever, and an
            // outgoing one must not sit holding the microphone port. The reason
            // is set first because hanging up is otherwise a silent teardown,
            // and the caller deserves to be told nobody picked up.
            State s = state();
            if (error != null) setError(error);
            if (s == State.OUTGOING) hangup();
            else if (s == State.INCOMING) reject();
            else end(id, error);
        };
        ui.postDelayed(ringTimeout, RING_TIMEOUT_MS);
    }

    private void cancelRingTimeout() {
        if (ringTimeout != null) {
            ui.removeCallbacks(ringTimeout);
            ringTimeout = null;
        }
    }

    // --- signalling out -----------------------------------------------------

    /** One signalling request to a peer's control endpoint. Blocking. */
    private String signal(String ip, int port, String cmd, String key, String extra)
            throws Exception {
        String url = "http://" + ip + ":" + port + "/?type=json&cmd=" + cmd
                + "&key=" + Http.enc(key) + extra;
        String body = Http.get(url, null, 8000);
        try {
            return new JSONObject(body).optString("result", "ok");
        } catch (Exception e) {
            return "ok"; // an older build answers in plain text; treat as accepted
        }
    }

    // --- ringing ------------------------------------------------------------

    private synchronized void startRingtone() {
        stopRingtone();
        try {
            MediaPlayer mp = new MediaPlayer();
            AssetFileDescriptor fd = app.getAssets().openFd("ring1.mp3");
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            ringer = mp;
        } catch (Exception e) {
            ringer = null; // no ringtone is survivable; the screen still lights up
        }
    }

    private synchronized void stopRingtone() {
        if (ringer != null) {
            try { ringer.stop(); } catch (Exception ignored) {}
            try { ringer.release(); } catch (Exception ignored) {}
            ringer = null;
        }
    }

    /** The "it is ringing over there" tone the caller hears. */
    private synchronized void startRingback() {
        stopRingback();
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
            tg.startTone(ToneGenerator.TONE_SUP_RINGTONE);
            ringback = tg;
        } catch (Exception e) {
            ringback = null;
        }
    }

    private synchronized void stopRingback() {
        if (ringback != null) {
            try { ringback.stopTone(); } catch (Exception ignored) {}
            try { ringback.release(); } catch (Exception ignored) {}
            ringback = null;
        }
    }

    // --- showing the incoming call -----------------------------------------

    /**
     * Get the call screen in front of whoever is walking past.
     *
     * Two routes on purpose. The direct activity start is what works in the
     * normal case (this app is the tablet's home screen and its task is alive).
     * The full-screen-intent notification covers the case where Android refuses
     * a background activity start — it either takes over the screen itself or,
     * at worst, degrades to a heads-up notification that can be tapped.
     */
    private void showIncomingUi() {
        postIncomingNotification();
        try {
            app.startActivity(new Intent(app, CallActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (Exception ignored) {
        }
    }

    private void postIncomingNotification() {
        try {
            NotificationManager nm =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && nm.getNotificationChannel(CALL_CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CALL_CHANNEL, "Входящий вызов", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Вызов с другого планшета");
                ch.setSound(null, null); // the ringtone is played by the app itself
                nm.createNotificationChannel(ch);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(app, 0,
                    new Intent(app, CallActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    flags);
            Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(app, CALL_CHANNEL)
                    : new Notification.Builder(app);
            b.setContentTitle("Входящий вызов")
                    .setContentText(peerName())
                    .setSmallIcon(android.R.drawable.sym_call_incoming)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                b.setCategory(Notification.CATEGORY_CALL);
            }
            nm.notify(CALL_NOTIF_ID, b.build());
        } catch (Exception ignored) {
        }
    }

    void clearIncomingNotification() {
        try {
            NotificationManager nm =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(CALL_NOTIF_ID);
        } catch (Exception ignored) {
        }
    }

    // --- helpers ------------------------------------------------------------

    private static final SecureRandom RNG = new SecureRandom();

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                           .append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    /** Positive so it survives the trip through a query string unambiguously. */
    private static int randomCallId() {
        return RNG.nextInt(Integer.MAX_VALUE - 1) + 1;
    }

    /** Compare without leaking where two keys start to differ. */
    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
