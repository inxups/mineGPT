package com.inxups.minegpt.shared;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The bridge intentionally uses IPv4 loopback only. Minecraft development clients set
 * {@code java.net.preferIPv6Addresses=system}, while the ChatGPT-launched Bridge does
 * not; using a hostname or {@code getLoopbackAddress()} could otherwise make each side
 * select a different loopback address family.
 */
public final class BridgeEndpoint {
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 37832;

    private BridgeEndpoint() {
    }

    public static InetAddress address() throws UnknownHostException {
        return InetAddress.getByName(HOST);
    }
}
