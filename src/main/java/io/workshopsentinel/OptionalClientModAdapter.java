package io.workshopsentinel;

import java.util.*;
import java.util.logging.Logger;

/**
 * Installed B42 ConnectionDetails.writeMods reads GameServer.ServerMods, whereas
 * ZomboidFileSystem.getModIDs() returns a separate, already-loaded runtime list.
 * Call only on the first server OnTick, after loading Lua/Java, never during discovery.
 * Does not alter the .ini, other mods, Workshop download list, or checksums.
 * TODO: confirm a player without this mod can connect on each supported B42 release.
 */
public final class OptionalClientModAdapter {
    private OptionalClientModAdapter() { }
    public static void apply(Logger log) throws ReflectiveOperationException {
        Class<?> server = Class.forName("zombie.network.GameServer");
        if (!PzServerRuntime.isServer(server)) throw new IllegalStateException("Not a dedicated server");
        Class<?> fs = Class.forName("zombie.ZomboidFileSystem");
        Object instance = fs.getField("instance").get(null);
        Object advertised = server.getField("ServerMods").get(null);
        Object loaded = fs.getMethod("getModIDs").invoke(instance);
        int removed = exclude(advertised, loaded);
        log.info("Client optional mode: removed " + removed + " WorkshopSentinel entry/entries from GameServer.ServerMods; server runtime retained");
    }
    static int exclude(Object advertised, Object loaded) {
        if (!(advertised instanceof List<?>) || !(loaded instanceof List<?>) || advertised == loaded)
            throw new IllegalStateException("Unsupported B42 mod lists; refusing to alter loaded mods");
        if (((List<?>) loaded).stream().noneMatch(OptionalClientModAdapter::isOwnId))
            throw new IllegalStateException("WorkshopSentinel has not finished loading");
        List<?> list = (List<?>) advertised;
        int before = list.size();
        list.removeIf(OptionalClientModAdapter::isOwnId);
        return before - list.size();
    }
    private static boolean isOwnId(Object id) {
        return "WorkshopSentinel".equals(id) || "\\WorkshopSentinel".equals(id);
    }
}
