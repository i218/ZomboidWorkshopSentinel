package io.workshopsentinel;

/** Minimal static API exposed by ZombieBuddy; never listens to client commands. */
public final class WorkshopSentinelBridge {
    private WorkshopSentinelBridge() { }
    public static void onTick() { Main.tick(); }
}
