package uz.bms.floorplan3d;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/**
 * The voice half of a call: raw PCM over UDP, straight from one tablet to the
 * other on the LAN.
 *
 * There is no WebRTC, no SIP and no server in the path. Both panels sit on the
 * same switch, so the two things WebRTC buys — NAT traversal and congestion
 * control — buy nothing here, while its signalling, ICE and codec machinery
 * would be by far the largest thing in this app. Instead each side binds a UDP
 * socket, tells the other its port during signalling, and streams 20 ms frames
 * of 16 kHz mono PCM. That is 256 kbit/s each way: nothing on Wi-Fi, and it
 * keeps latency at one frame because there is nothing to buffer for.
 *
 * Wire format, 12-byte header then the samples:
 * <pre>
 *   0..3  magic "BMSA"
 *   4     version (1)
 *   5     payload type (0 = PCM16 mono 16 kHz)
 *   6..7  sequence, uint16 big-endian
 *   8..11 call id, uint32 big-endian
 *   12..  640 bytes of PCM, device byte order (both ends are Android)
 * </pre>
 *
 * Packets are accepted only from the peer's address and only for the current
 * call id — otherwise anything on the LAN could talk into a room's speaker.
 */
final class AudioLink {

    interface Events {
        /** Nothing has arrived for a while — the other end is gone. */
        void onLinkLost();
    }

    static final int SAMPLE_RATE = 16000;
    /** 20 ms — small enough to stay conversational, big enough to be cheap. */
    static final int FRAME_SAMPLES = 320;
    static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    private static final int HEADER = 12;
    private static final byte VERSION = 1;
    private static final byte PT_PCM16_16K = 0;
    /** Silence for this long means the peer vanished (crash, Wi-Fi drop). */
    private static final long LINK_TIMEOUT_MS = 12_000L;

    private final Context ctx;
    private final DatagramSocket socket;
    private final InetAddress peer;
    private final int peerPort;
    private final int callId;
    private final Events events;
    private final boolean mediaStream;

    private volatile boolean running;
    private volatile boolean muted;
    private volatile long lastRx;

    private Thread sender;
    private Thread receiver;
    private AudioRecord record;
    private AudioTrack track;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private AutomaticGainControl agc;
    private int savedMode = AudioManager.MODE_NORMAL;
    private boolean savedSpeaker;

    AudioLink(Context ctx, DatagramSocket socket, InetAddress peer, int peerPort,
              int callId, boolean mediaStream, Events events) {
        this.ctx = ctx.getApplicationContext();
        this.socket = socket;
        this.peer = peer;
        this.peerPort = peerPort;
        this.callId = callId;
        this.mediaStream = mediaStream;
        this.events = events;
    }

    /** @throws Exception when the microphone or speaker cannot be opened. */
    void start() throws Exception {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            savedMode = am.getMode();
            savedSpeaker = am.isSpeakerphoneOn();
            // Hands-free: this is a wall panel, nobody is holding it to an ear.
            // MODE_IN_COMMUNICATION is also what makes the platform's echo
            // canceller reference the speaker output at all.
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(true);
        }

        int inMin = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (inMin <= 0) inMin = FRAME_BYTES * 8;
        record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(inMin, FRAME_BYTES * 8));
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new Exception("Микрофон недоступен");
        }
        attachEffects(record.getAudioSessionId());

        int outMin = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (outMin <= 0) outMin = FRAME_BYTES * 8;
        // VOICE_CALL is the stream the echo canceller listens to, so it is the
        // default. Some cheap panels have no voice-call path at all and play
        // nothing on it — the settings switch moves playback to the media
        // stream for those, at the cost of more echo.
        int usage = mediaStream ? AudioAttributes.USAGE_MEDIA
                                : AudioAttributes.USAGE_VOICE_COMMUNICATION;
        int contentType = mediaStream ? AudioAttributes.CONTENT_TYPE_MUSIC
                                      : AudioAttributes.CONTENT_TYPE_SPEECH;
        track = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build(),
                new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                Math.max(outMin, FRAME_BYTES * 6),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new Exception("Динамик недоступен");
        }

        running = true;
        lastRx = System.currentTimeMillis();
        record.startRecording();
        track.play();

        sender = new Thread(this::pump, "intercom-tx");
        sender.setPriority(Thread.MAX_PRIORITY);
        sender.start();

        receiver = new Thread(this::drain, "intercom-rx");
        receiver.setPriority(Thread.MAX_PRIORITY);
        receiver.start();
    }

    void setMuted(boolean m) {
        muted = m;
    }

    boolean isMuted() {
        return muted;
    }

    void stop() {
        running = false;
        // Closing the socket is what unblocks the receiver's recv() promptly.
        try { socket.close(); } catch (Exception ignored) {}
        joinQuietly(sender);
        joinQuietly(receiver);
        sender = null;
        receiver = null;

        releaseEffects();
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) {}
            try { record.release(); } catch (Exception ignored) {}
            record = null;
        }
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) {}
            try { track.release(); } catch (Exception ignored) {}
            track = null;
        }
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            try {
                am.setSpeakerphoneOn(savedSpeaker);
                am.setMode(savedMode);
            } catch (Exception ignored) {}
        }
    }

    // --- threads ------------------------------------------------------------

    /** Microphone → UDP. */
    private void pump() {
        byte[] pkt = new byte[HEADER + FRAME_BYTES];
        pkt[0] = 'B'; pkt[1] = 'M'; pkt[2] = 'S'; pkt[3] = 'A';
        pkt[4] = VERSION;
        pkt[5] = PT_PCM16_16K;
        pkt[8]  = (byte) (callId >>> 24);
        pkt[9]  = (byte) (callId >>> 16);
        pkt[10] = (byte) (callId >>> 8);
        pkt[11] = (byte) callId;
        int seq = 0;
        DatagramPacket dp = new DatagramPacket(pkt, pkt.length, peer, peerPort);
        while (running) {
            int n;
            try {
                n = record.read(pkt, HEADER, FRAME_BYTES);
            } catch (Exception e) {
                break;
            }
            if (n <= 0) continue;
            if (n < FRAME_BYTES) java.util.Arrays.fill(pkt, HEADER + n, HEADER + FRAME_BYTES, (byte) 0);
            // Muting sends silence rather than nothing: the other end uses the
            // packet flow to notice the call has died, so going quiet on the
            // wire would look identical to a tablet that fell off the network.
            if (muted) java.util.Arrays.fill(pkt, HEADER, HEADER + FRAME_BYTES, (byte) 0);
            pkt[6] = (byte) (seq >>> 8);
            pkt[7] = (byte) seq;
            seq = (seq + 1) & 0xFFFF;
            try {
                socket.send(dp);
            } catch (Exception e) {
                if (!running) break;
                // A transient ENETUNREACH while Wi-Fi roams: keep going, the
                // receive-side timeout is what decides the call is over.
            }
        }
    }

    /** UDP → speaker. */
    private void drain() {
        byte[] buf = new byte[HEADER + FRAME_BYTES * 2];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        int lastSeq = -1;
        try {
            socket.setSoTimeout(2000);
        } catch (Exception ignored) {
        }
        while (running) {
            try {
                dp.setData(buf, 0, buf.length);
                socket.receive(dp);
            } catch (SocketTimeoutException t) {
                if (running && System.currentTimeMillis() - lastRx > LINK_TIMEOUT_MS) {
                    running = false;
                    if (events != null) events.onLinkLost();
                }
                continue;
            } catch (Exception e) {
                break; // socket closed by stop()
            }
            int len = dp.getLength();
            if (len <= HEADER) continue;
            if (!peer.equals(dp.getAddress())) continue;      // not our peer
            if (buf[0] != 'B' || buf[1] != 'M' || buf[2] != 'S' || buf[3] != 'A') continue;
            if (buf[4] != VERSION || buf[5] != PT_PCM16_16K) continue;
            int id = ((buf[8] & 0xFF) << 24) | ((buf[9] & 0xFF) << 16)
                    | ((buf[10] & 0xFF) << 8) | (buf[11] & 0xFF);
            if (id != callId) continue;                        // a previous call

            lastRx = System.currentTimeMillis();
            int seq = ((buf[6] & 0xFF) << 8) | (buf[7] & 0xFF);
            if (lastSeq >= 0) {
                // 16-bit wrap-safe comparison; drop anything that arrived late.
                short gap = (short) (seq - lastSeq);
                if (gap <= 0) continue;
            }
            lastSeq = seq;
            try {
                track.write(buf, HEADER, len - HEADER);
            } catch (Exception e) {
                break;
            }
        }
    }

    // --- platform effects ---------------------------------------------------

    /** Echo cancel / noise suppress / gain, where the device offers them. */
    private void attachEffects(int session) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(session);
                if (aec != null) aec.setEnabled(true);
            }
        } catch (Exception ignored) {}
        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(session);
                if (ns != null) ns.setEnabled(true);
            }
        } catch (Exception ignored) {}
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(session);
                if (agc != null) agc.setEnabled(true);
            }
        } catch (Exception ignored) {}
    }

    private void releaseEffects() {
        try { if (aec != null) aec.release(); } catch (Exception ignored) {}
        try { if (ns != null) ns.release(); } catch (Exception ignored) {}
        try { if (agc != null) agc.release(); } catch (Exception ignored) {}
        aec = null;
        ns = null;
        agc = null;
    }

    private static void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            t.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
