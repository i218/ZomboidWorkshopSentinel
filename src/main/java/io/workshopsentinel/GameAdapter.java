package io.workshopsentinel;

/** Game-thread-only contract. Negative count means unknown, never empty. */
public interface GameAdapter {
    int playerCount() throws Exception;
    void broadcast(String message) throws Exception;
}
