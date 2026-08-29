package com.inxups.minegpt.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inxups.minegpt.shared.ProtocolCodec;
import com.inxups.minegpt.shared.ProtocolMessage;
import org.junit.jupiter.api.Test;

class ProtocolCodecTest {
    @Test
    void roundTripsProtocolMessages() {
        ProtocolMessage expected = ProtocolMessage.hello("abc_token");
        assertEquals(expected, ProtocolCodec.decode(ProtocolCodec.encode(expected)));
    }

    @Test
    void rejectsOversizedLines() {
        assertThrows(IllegalArgumentException.class, () -> ProtocolCodec.decode("x".repeat(ProtocolCodec.MAX_LINE_LENGTH + 1)));
    }
}
