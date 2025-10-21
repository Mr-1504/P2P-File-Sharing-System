package main.java.infras.utils;

import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Utility to obtain a LAN (site-local) IPv4 even when there is no Internet access.
 */
public class NetworkUtils {

    /**
     * Get a LAN IPv4 by scanning network interfaces (no external connectivity required).
     * Order preference: 192.168.x.x > 10.x.x.x > 172.16-31.x.x > any other site-local.
     * Skips loopback, link-local (169.254.x.x), virtual and down interfaces.
     */
    public static String getLanIp() {
        List<String> siteLocal = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String nameLower = ni.getName().toLowerCase();
                String displayLower = ni.getDisplayName().toLowerCase();

                if (nameLower.contains("vmnet") || displayLower.contains("vmware")
                        || displayLower.contains("virtualbox") || displayLower.contains("docker")
                        || displayLower.contains("hyper-v") || displayLower.contains("bridge")
                        || displayLower.contains("tap") || displayLower.contains("loopback")) {
                    continue;
                }


                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address)) continue;
                    String ip = addr.getHostAddress();
                    if (addr.isLoopbackAddress()) continue;
                    if (ip.startsWith("169.254.")) continue; // link-local
                    if (addr.isSiteLocalAddress()) {
                        siteLocal.add(ip);
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        if (siteLocal.isEmpty()) return null;
        // Prioritize private ranges manually
        String best = null;
        for (String ip : siteLocal) {
            if (ip.startsWith("192.168.")) return ip;
            if (best == null && ip.startsWith("10.")) best = ip;
        }
        if (best != null) return best;
        for (String ip : siteLocal) {
            if (ip.startsWith("172.")) {
                // Check 172.16 - 172.31
                String[] parts = ip.split("\\.");
                int second = Integer.parseInt(parts[1]);
                if (second >= 16 && second <= 31) return ip;
            }
        }
        return siteLocal.get(0);
    }

    /**
     * Fallback: if no LAN IP found via interface scan, try the UDP connect trick.
     */
    public static String getLanIpWithFallback() {
        String ip = getLanIp();
        if (ip != null) return ip;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            return socket.getLocalAddress().getHostAddress();
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Public method: prefer pure LAN detection first.
     */
    public static String getCurrentIp() {
        return getLanIpWithFallback();
    }

     public static String getBroadcastIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;

                String nameLower = ni.getName().toLowerCase();
                String displayLower = ni.getDisplayName().toLowerCase();

                if (nameLower.contains("vmnet") || displayLower.contains("vmware")
                        || displayLower.contains("virtualbox") || displayLower.contains("docker")
                        || displayLower.contains("hyper-v") || displayLower.contains("bridge")
                        || displayLower.contains("tap") || displayLower.contains("wsl")
                        || displayLower.contains("loopback") || displayLower.contains("virtual")) {
                    continue;
                }

                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        InetAddress broadcast = ia.getBroadcast();
                        if (broadcast != null) {
                            return broadcast.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException ignored) {}
        return null;
    }
}