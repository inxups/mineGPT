package com.inxups.minegpt.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inxups.minegpt.shared.BridgeEndpoint;
import org.junit.jupiter.api.Test;

class BridgeEndpointTest {
    @Test
    void staysOnIpv4WhenTheJvmPrefersIpv6() throws Exception {
        assertEquals("127.0.0.1", BridgeEndpoint.address().getHostAddress());
    }
}
