package com.inxups.minegpt.bridge;

/** Status returned to the MCP host. The token is only returned by the dedicated pairing tool. */
record BridgeStatus(int port, boolean minecraftConnected, int queuedMessages) {
}
