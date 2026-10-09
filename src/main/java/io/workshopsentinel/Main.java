package io.workshopsentinel;

import java.nio.file.*;
import java.util.logging.*;

public final class Main {
    private static final Logger LOG = Logger.getLogger("WorkshopSentinel");
    private static boolean registered, attempted;
    private static SentinelService service;
    private Main() { }

    /** Documented ZombieBuddy lifecycle entry; does not start worker or touch game state. */
    public static synchronized void main(String[] args) {
        if (registered) return;
        try {
            Class<?> exposer = Class.forName("me.zed_0xff.zombie_buddy.Exposer");
            // ZB 2.3.4 deletes a same-name alias while renaming the exposed table.
            // Default exposure retains the canonical global WorkshopSentinelBridge.
            exposer.getMethod("exposeClass", Class.class).invoke(null, WorkshopSentinelBridge.class);
            registered = true;
            LOG.info("Java bridge registered v0.4.9; awaiting server OnTick. Dedicated-server guard is deferred until tick.");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "ZombieBuddy Exposer API unavailable; mod disabled. TODO verify deployed ZombieBuddy version", e);
        }
    }
    static synchronized void tick() {
        if (!registered) return;
        // ZombieBuddy's early platform detection can be false during dedicated-server mod loading.
        // The neutral JAR path allows bootstrap; only a verified server tick may start any service.
        try {
            if (!PzServerRuntime.isServer(Class.forName("zombie.network.GameServer"))) return;
        } catch (ReflectiveOperationException e) {
            if (!attempted) LOG.log(Level.SEVERE, "Dedicated-server guard unavailable; monitoring disabled", e);
            attempted = true;
            return;
        }
        if (!attempted) {
            attempted = true;
            try {
                Path cache = cacheDirectory();
                String name = serverName();
                if (!name.matches("[A-Za-z0-9_.-]+") || name.equals(".") || name.equals(".."))
                    throw new IllegalArgumentException("Invalid serverName");
                Path defaultConfig = cache.resolve("WorkshopSentinel").resolve(name).resolve("WorkshopSentinel.properties");
                Path file = Paths.get(System.getProperty("workshopsentinel.config", defaultConfig.toString()));
                Config c = Config.load(file, cache.resolve("Server").resolve(name + ".ini"));
                Files.createDirectories(c.directory);
                FileHandler handler = new FileHandler(c.directory.resolve("WorkshopSentinel-%g.log").toString(), 1024 * 1024, 3, true);
                handler.setEncoding("UTF-8"); handler.setFormatter(new SimpleFormatter()); LOG.addHandler(handler);
                PzGameAdapter game = new PzGameAdapter();
                if (c.clientOptional) OptionalClientModAdapter.apply(LOG);
                ShutdownAdapter shutdown = () -> LOG.info("Marker-only adapter; supervisor/operator handles restart");
                if (!c.dryRun && c.shutdownEnabled && c.shutdownAdapter.equals("pz-quit-experimental"))
                    shutdown = new ExperimentalPzShutdownAdapter();
                WorkshopUpdateProvider provider = c.provider.equals("steam")
                    ? new SteamWorkshopUpdateProvider(c.timeoutSeconds, c.baselineFile, LOG)
                    : c.provider.equals("file") ? StubProviders.file(c.triggerFile) : StubProviders.noop();
                service = new SentinelService(c, game, provider, shutdown, () -> System.nanoTime() / 1_000_000, LOG);
                Runtime.getRuntime().addShutdownHook(new Thread(service::close, "WorkshopSentinel-Cleanup"));
                LOG.info("Started dryRun=" + c.dryRun + " shutdownEnabled=" + c.shutdownEnabled + " provider=" + c.provider
                    + " clientOptional=" + c.clientOptional + " serverIni=" + c.serverIni + " config=" + file.toAbsolutePath());
            } catch (Exception e) { LOG.log(Level.SEVERE, "Initialization failed; monitoring disabled until JVM restart", e); }
        }
        if (service != null) service.onTick();
    }
    static String serverName() {
        String override = System.getProperty("workshopsentinel.serverName");
        if (override != null) return override;
        try {
            Object value = Class.forName("zombie.network.GameServer").getField("serverName").get(null);
            if (value instanceof String && !((String) value).isBlank()) return (String) value;
        } catch (ReflectiveOperationException e) {
            LOG.warning("GameServer.serverName API unavailable; falling back to servertest. Override with workshopsentinel.serverName.");
        }
        return "servertest";
    }
    private static Path cacheDirectory() {
        String override = System.getProperty("workshopsentinel.cachedir");
        if (override != null) return Paths.get(override).toAbsolutePath();
        try {
            // TODO: verify ZomboidFileSystem cache API on target B42; fallback is logged explicitly.
            Class<?> fs = Class.forName("zombie.ZomboidFileSystem");
            Object instance = fs.getField("instance").get(null);
            return Paths.get((String) fs.getMethod("getCacheDir").invoke(instance)).toAbsolutePath();
        } catch (Exception e) {
            Path fallback = Paths.get(System.getProperty("user.home"), "Zomboid");
            LOG.warning("Cache-dir API unavailable; fallback=" + fallback + "; set workshopsentinel.cachedir for custom -cachedir");
            return fallback;
        }
    }
}
