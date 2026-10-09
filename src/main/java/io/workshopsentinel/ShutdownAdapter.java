package io.workshopsentinel;

/** Called once on the game thread, ONLY after marker persistence and explicit configuration. */
@FunctionalInterface
public interface ShutdownAdapter {
    void requestShutdown() throws Exception;
}
