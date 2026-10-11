package android.net;

import java.util.Locale;

/**
 * Wyrm Desktop: what kind of network an interface is, guessed from its
 * Windows name (Wi-Fi, Ethernet, a VPN adapter, mobile broadband). Windows
 * does not say whether a link is metered or how fast it is to Java, so those
 * read as "not metered" and 0 kbps.
 */
public final class NetworkCapabilities {
    public static final int TRANSPORT_CELLULAR = 0;
    public static final int TRANSPORT_WIFI = 1;
    public static final int TRANSPORT_ETHERNET = 3;
    public static final int TRANSPORT_VPN = 4;

    public static final int NET_CAPABILITY_INTERNET = 12;
    public static final int NET_CAPABILITY_NOT_METERED = 11;
    public static final int NET_CAPABILITY_VALIDATED = 16;

    private final int transport;
    private final boolean internet;

    NetworkCapabilities(Network network, boolean internet) {
        this.transport = classify(network.displayName + " " + network.name);
        this.internet = internet;
    }

    static int classify(String text) {
        String n = text.toLowerCase(Locale.ROOT);
        if (n.contains("vpn") || n.contains("tap-") || n.contains("tap ") || n.contains("tun") || n.contains("wireguard")
                || n.contains("tailscale") || n.contains("zerotier") || n.contains("openvpn") || n.contains("fortinet")
                || n.contains("cisco anyconnect") || n.contains("wintun")) return TRANSPORT_VPN;
        if (n.contains("wi-fi") || n.contains("wifi") || n.contains("wireless") || n.contains("wlan") || n.contains("802.11"))
            return TRANSPORT_WIFI;
        if (n.contains("mobile broadband") || n.contains("cellular") || n.contains("wwan") || n.contains("rndis")
                || n.contains("remote ndis")) return TRANSPORT_CELLULAR;
        return TRANSPORT_ETHERNET;
    }

    public boolean hasTransport(int value) { return transport == value; }

    public boolean hasCapability(int capability) {
        switch (capability) {
            case NET_CAPABILITY_INTERNET:
            case NET_CAPABILITY_VALIDATED: return internet;
            case NET_CAPABILITY_NOT_METERED: return true;
            default: return false;
        }
    }

    public int getLinkDownstreamBandwidthKbps() { return 0; }
    public int getLinkUpstreamBandwidthKbps() { return 0; }
}
