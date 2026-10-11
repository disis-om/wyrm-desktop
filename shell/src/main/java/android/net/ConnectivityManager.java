package android.net;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Wyrm Desktop: Android's ConnectivityManager over this computer's network
 * interfaces, for the shared arena-drop report (DropWatch).
 *
 * The active network is the interface the computer would use to reach the
 * internet (a UDP "connect" picks the route without sending anything). The
 * default-network callback is a 3 s check for that interface changing (Wi-Fi
 * to Ethernet, a VPN coming up), which is how DropWatch counts switches.
 */
public final class ConnectivityManager {
    public static class NetworkCallback {
        public void onAvailable(Network network) {}
        public void onLost(Network network) {}
    }

    private final List<NetworkCallback> callbacks = new ArrayList<>();
    private Timer timer;
    private Network lastSeen;

    public Network getActiveNetwork() {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(new InetSocketAddress(InetAddress.getByName("1.1.1.1"), 443));
            InetAddress local = probe.getLocalAddress();
            if (local == null || local.isAnyLocalAddress()) return null;
            NetworkInterface nic = NetworkInterface.getByInetAddress(local);
            return nic == null ? null : new Network(nic.getName(), nic.getDisplayName());
        } catch (Exception e) {
            return null;
        }
    }

    @Deprecated
    public Network[] getAllNetworks() {
        List<Network> out = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nic.isUp() && !nic.isLoopback() && !nic.isVirtual()) out.add(new Network(nic.getName(), nic.getDisplayName()));
            }
        } catch (Exception ignored) {
        }
        return out.toArray(new Network[0]);
    }

    public NetworkCapabilities getNetworkCapabilities(Network network) {
        if (network == null) return null;
        Network active = getActiveNetwork();
        return new NetworkCapabilities(network, active != null && active.equals(network));
    }

    public synchronized void registerDefaultNetworkCallback(NetworkCallback callback) {
        callbacks.add(callback);
        if (timer != null) return;
        timer = new Timer("wyrm-network-watch", true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                Network now = getActiveNetwork();
                List<NetworkCallback> targets;
                synchronized (ConnectivityManager.this) {
                    if (now == null || now.equals(lastSeen)) return;
                    lastSeen = now;
                    targets = new ArrayList<>(callbacks);
                }
                for (NetworkCallback each : targets) each.onAvailable(now);
            }
        }, 0, 3000);
    }

    public synchronized void unregisterNetworkCallback(NetworkCallback callback) {
        callbacks.remove(callback);
        if (callbacks.isEmpty() && timer != null) {
            timer.cancel();
            timer = null;
            lastSeen = null;
        }
    }
}
