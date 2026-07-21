package uz.bms.floorplan3d;

/**
 * Another tablet in the house, as published in the Home Assistant directory.
 *
 * Everything here comes from that tablet's own registration — nothing is
 * configured by hand, so plugging in a new panel is enough to make it callable
 * from every other one.
 */
final class IntercomPeer {

    final String deviceId;
    final String name;
    final String ip;
    final int port;
    /** The peer's intercom key — signs the invite we send it. */
    final String key;
    final String version;
    /** HA's last_updated for the peer's entity, epoch ms; 0 when unknown. */
    final long lastSeen;

    IntercomPeer(String deviceId, String name, String ip, int port,
                 String key, String version, long lastSeen) {
        this.deviceId = deviceId;
        this.name = name;
        this.ip = ip;
        this.port = port;
        this.key = key;
        this.version = version;
        this.lastSeen = lastSeen;
    }

    /**
     * Fresh enough that the tablet is probably powered on. The window is
     * generous (5× the heartbeat) because it compares HA's clock with this
     * device's, and a tablet whose time has drifted should not disappear from
     * the list over that.
     */
    boolean online() {
        if (lastSeen <= 0) return true; // unknown timestamp — don't hide it
        return System.currentTimeMillis() - lastSeen < 5 * 60_000L;
    }
}
