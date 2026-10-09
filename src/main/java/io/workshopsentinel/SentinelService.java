package io.workshopsentinel;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.logging.*;

/** Scheduler runs I/O only. The Lua OnTick bridge drains results and calls all game APIs. */
public final class SentinelService implements AutoCloseable {
    private final Config config;
    private final Logger log;
    private final FailureReporter failures;
    private final ScheduledExecutorService worker;
    private final AtomicReference<Set<String>> results = new AtomicReference<>();
    private final SentinelEngine engine;
    private final LongSupplier clock;
    private long lastTick = Long.MIN_VALUE;
    private volatile boolean closed;
    private boolean shutdownPending;
    private final ShutdownAdapter shutdown;

    public SentinelService(Config config, GameAdapter game, WorkshopUpdateProvider provider,
                           ShutdownAdapter shutdown, LongSupplier clock, Logger log) {
        this.config = config; this.shutdown = shutdown; this.clock = clock; this.log = log;
        failures = new FailureReporter(log);
        engine = new SentinelEngine(config, game, (reason, ids, age) -> {
            RestartMarker.write(config.markerFile, config.dryRun, config.shutdownEnabled,
                config.shutdownAdapter, reason, ids, age);
            log.info("Restart marker persisted: " + config.markerFile + "; reason=" + reason);
            shutdownPending = !config.dryRun && config.shutdownEnabled;
            if (!shutdownPending) log.info("Shutdown suppressed by configuration; terminal RESTARTING simulation/request state");
        }, log);
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WorkshopSentinel-IO"); t.setDaemon(true); return t;
        });
        worker.scheduleAtFixedRate(() -> {
            if (closed) return;
            try {
                Set<String> ids = config.configuredWorkshopIds();
                if (ids.isEmpty()) log.warning("WorkshopItems is empty; no items to monitor");
                Set<String> found = provider.check(ids);
                if (!ids.containsAll(found)) throw new IllegalStateException("Provider returned unconfigured IDs");
                if (!found.isEmpty()) results.updateAndGet(old -> {
                    Set<String> merged = new LinkedHashSet<>(found);
                    if (old != null) merged.addAll(old);
                    return Collections.unmodifiableSet(merged);
                });
                failures.recovered("Workshop check");
                log.info("Workshop check completed: configured=" + ids.size() + " updates=" + found);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) { failures.failed("Workshop check", "Retry at the next scheduled check; pending update timer unchanged", e); }
        }, 0, config.checkSeconds, TimeUnit.SECONDS);
    }
    public void onTick() {
        if (closed) return;
        long now = clock.getAsLong();
        if (lastTick != Long.MIN_VALUE && now - lastTick < config.tickMillis) return;
        lastTick = now;
        Set<String> found = results.getAndSet(null);
        if (found != null) engine.found(found, now);
        engine.tick(now);
        if (engine.state() == SentinelEngine.State.RESTARTING) {
            close();
            if (shutdownPending) {
                shutdownPending = false; // Exactly one request, even if adapter throws after partial success.
                try { shutdown.requestShutdown(); log.info("Shutdown requested; external supervisor must relaunch and update Workshop"); }
                catch (Exception e) { log.log(Level.SEVERE, "Shutdown adapter failed; no automatic retry, operator action required", e); }
            }
        }
    }
    public SentinelEngine.State state() { return engine.state(); }
    @Override public void close() { closed = true; worker.shutdownNow(); }
}
