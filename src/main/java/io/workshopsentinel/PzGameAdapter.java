package io.workshopsentinel;

import java.lang.reflect.*;
import java.util.Collection;

/** Public API probes isolated here because PZ Javadocs are not a per-build compatibility guarantee. */
public final class PzGameAdapter implements GameAdapter {
    private final Class<?> server, chat;
    private final Method count, players, chatInstance, alert;
    public PzGameAdapter() throws ReflectiveOperationException {
        server = Class.forName("zombie.network.GameServer");
        Method candidate;
        try { candidate = server.getMethod("getPlayerCount"); }
        catch (NoSuchMethodException e) { candidate = null; }
        count = candidate;
        players = count == null ? server.getMethod("getPlayers") : null;
        chat = Class.forName("zombie.network.chat.ChatServer");
        chatInstance = chat.getMethod("getInstance");
        alert = chat.getMethod("sendServerAlertMessageToServerChat", String.class);
        // TODO: verify actual signatures and OnTick ordering against the deployed B42 minor release.
        if (!PzServerRuntime.isServer(server)) throw new IllegalStateException("Not a dedicated server");
    }
    @Override public int playerCount() throws Exception {
        if (!PzServerRuntime.isServer(server)) throw new IllegalStateException("Server is not running");
        Object value = count != null ? count.invoke(null) : players.invoke(null);
        int n = count != null ? ((Number) value).intValue() : ((Collection<?>) value).size();
        if (n < 0) throw new IllegalStateException("Invalid player count");
        return n;
    }
    @Override public void broadcast(String message) throws Exception {
        Object instance = chatInstance.invoke(null);
        if (instance == null) throw new IllegalStateException("ChatServer not ready");
        alert.invoke(instance, "[WorkshopSentinel] " + message);
    }
}
