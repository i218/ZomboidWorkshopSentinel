package io.workshopsentinel;

import java.util.*;
import java.util.logging.*;

/** Deterministic state machine, used only by the game thread. Times are monotonic milliseconds. */
public final class SentinelEngine {
    public enum State { NORMAL, UPDATE_PENDING, COUNTDOWN, RESTARTING }
    @FunctionalInterface public interface RestartAction {
        void request(String reason, Set<String> ids, long updateAgeMillis) throws Exception;
    }
    private final Config config;
    private final GameAdapter game;
    private final RestartAction restart;
    private final Logger log;
    private State state = State.NORMAL;
    private final Set<String> updates = new LinkedHashSet<>();
    private long detectedAt, nextPoll, countdownAt, retryAt, lastReminder = -1;

    public SentinelEngine(Config config, GameAdapter game, RestartAction restart, Logger log) {
        this.config = config; this.game = game; this.restart = restart; this.log = log;
    }
    public State state() { return state; }
    public Set<String> updates() { return Collections.unmodifiableSet(new LinkedHashSet<>(updates)); }
    public void found(Set<String> ids, long now) {
        if (ids.isEmpty() || state == State.RESTARTING) return;
        updates.addAll(ids);
        if (state == State.NORMAL) {
            detectedAt = now;
            nextPoll = now;
            transition(State.UPDATE_PENDING);
            announce(prefix() + "Workshop update detected. Waiting for an empty server; forced warning after "
                + config.waitSeconds + " seconds.");
        }
        // Additional updates, provider errors, and clean responses NEVER reset detectedAt.
    }
    public void tick(long now) {
        if (state == State.NORMAL || state == State.RESTARTING || now < retryAt) return;
        boolean deadline = state == State.UPDATE_PENDING
            ? now - detectedAt >= config.waitSeconds * 1000
            : now - countdownAt >= config.countdownSeconds * 1000;
        if (now < nextPoll && !deadline) return;
        nextPoll = now + config.pollSeconds * 1000;
        int count;
        try {
            count = game.playerCount();
            if (count < 0) throw new IllegalStateException("Unknown player count");
        } catch (Exception e) {
            log.log(Level.WARNING, "Player count unavailable; restart postponed", e);
            retryAt = nextPoll;
            return;
        }
        if (count == 0) { requestRestart("empty-server", now); return; }
        if (state == State.UPDATE_PENDING && deadline) {
            // The full safe-exit window begins AFTER a successful warning, even after a stalled tick.
            if (announce(prefix() + "Workshop update: shutdown in " + config.countdownSeconds
                + " seconds. Please leave safely.")) {
                countdownAt = now;
                lastReminder = (config.countdownSeconds + 59) / 60;
                transition(State.COUNTDOWN);
            } else { retryAt = nextPoll; }
        } else if (state == State.COUNTDOWN) {
            if (deadline) { requestRestart("forced-countdown-complete", now); return; }
            long remaining = (config.countdownSeconds * 1000 - (now - countdownAt) + 59999) / 60000;
            if (remaining != lastReminder) {
                announce(prefix() + "Workshop shutdown in approximately " + remaining + " minute(s). Please leave safely.");
                lastReminder = remaining;
            }
        }
    }
    private void requestRestart(String reason, long now) {
        try {
            restart.request(reason, updates(), now - detectedAt);
            transition(State.RESTARTING);
        } catch (Exception e) {
            retryAt = now + config.pollSeconds * 1000;
            log.log(Level.SEVERE, "Restart request preparation failed; will retry without shutdown", e);
        }
    }
    private String prefix() { return config.dryRun ? "[DRY RUN — no actual shutdown] " : ""; }
    private boolean announce(String message) {
        log.info(message);
        try { game.broadcast(message); return true; }
        catch (Exception e) { log.log(Level.WARNING, "Chat delivery failed", e); return false; }
    }
    private void transition(State next) { log.info("State " + state + " -> " + next); state = next; }
}
