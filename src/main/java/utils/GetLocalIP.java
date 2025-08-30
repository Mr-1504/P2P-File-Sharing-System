package main.java.utils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Utility class to get the local IP address of the machine.
 */
public class GetLocalIP {
    /**
     * Retrieves the local IP address of the machine.
     *
     * @return The local IP address as a String, or null if it cannot be determined.
     */
    public static String getCurrentIp() {
    try {
        // Kết nối "ảo" ra ngoài, không cần gửi data
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.connect(java.net.InetAddress.getByName("8.8.8.8"), 53);
            return socket.getLocalAddress().getHostAddress();
        }
    } catch (Exception e) {
        e.printStackTrace();
    }
    return null;
}

}
