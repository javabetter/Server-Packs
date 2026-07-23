package com.rpplus.resourcepackplus.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Produces a stable string key identifying "which server we're connected to", used to
 * remember per-server pack choices.
 *
 * IMPORTANT: resource pack pushes can arrive during the network "configuration" phase,
 * before Minecraft.getCurrentServer() is populated — so that field can NOT be trusted
 * as the primary source here (confirmed by real testing: it was silently returning null,
 * collapsing every server into one shared "unknown-server" bucket). The connection's own
 * remote address is available immediately regardless of phase, so that's used first.
 */
public final class ServerKeyUtil {

    private ServerKeyUtil() {}

    /** Preferred: derives the key directly from the live network connection. */
    public static String fromConnection(Connection connection) {
        if (connection != null) {
            SocketAddress address = connection.getRemoteAddress();
            if (address instanceof InetSocketAddress inet) {
                String host = inet.getHostString();
                if (host != null && !host.isEmpty()) {
                    return (host + ":" + inet.getPort()).toLowerCase();
                }
            } else if (address != null) {
                String text = address.toString();
                if (!text.isEmpty()) return text.toLowerCase();
            }
        }
        return currentServerKey();
    }

    /**
     * The friendly name of the current server as it appears in the multiplayer server list
     * (the label the player typed when adding it), or null if unavailable or blank — e.g. on a
     * direct connect, or if we're not connected. Used to name the converted pack folder.
     */
    public static String currentServerName() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        if (server != null && server.name != null && !server.name.isBlank()) {
            return server.name;
        }
        return null;
    }

    /** Fallback used only if the connection's address is unavailable for some reason. */
    public static String currentServerKey() {
        Minecraft client = Minecraft.getInstance();
        ServerData server = client.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isEmpty()) {
            return server.ip.toLowerCase();
        }
        return "unknown-server";
    }
}
