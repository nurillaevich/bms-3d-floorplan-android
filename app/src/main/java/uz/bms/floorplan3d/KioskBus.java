package uz.bms.floorplan3d;

/**
 * Bridge between the background {@link RemoteHttpService} and the running
 * {@link MainActivity}.
 *
 * The HTTP server answers on a worker thread and has no view of the activity;
 * the activity registers a handler here while it is alive. Commands that arrive
 * with nothing listening are dropped rather than queued — a stale "reload" that
 * fires minutes later when the kiosk happens to open is worse than no reload.
 */
public final class KioskBus {

    /** A command from the HTTP control endpoint. `url` is only set for loadUrl. */
    public static final class Command {
        public final String name;
        public final String url;

        public Command(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    public interface Handler {
        void onCommand(Command cmd);
    }

    private static volatile Handler handler;

    private KioskBus() {}

    /** Called by MainActivity while it is in the foreground. */
    public static void setHandler(Handler h) {
        handler = h;
    }

    public static void clearHandler(Handler h) {
        if (handler == h) handler = null;
    }

    /** True when a live activity can act on a command. */
    public static boolean hasHandler() {
        return handler != null;
    }

    public static boolean send(Command cmd) {
        Handler h = handler;
        if (h == null) return false;
        h.onCommand(cmd);
        return true;
    }
}
