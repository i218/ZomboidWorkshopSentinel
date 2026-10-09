package io.workshopsentinel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Marker is informational; this mod never reads it as a command on startup. */
public final class RestartMarker {
    private RestartMarker() { }
    public static void write(Path path, boolean dryRun, boolean shutdownEnabled, String adapter,
                             String reason, Set<String> ids, long elapsedMillis) throws IOException {
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Properties p = new Properties();
        p.setProperty("schema", "1");
        p.setProperty("requestId", UUID.randomUUID().toString());
        p.setProperty("requestedAtUtc", Instant.now().toString());
        p.setProperty("dryRun", Boolean.toString(dryRun));
        p.setProperty("shutdownEnabled", Boolean.toString(shutdownEnabled));
        p.setProperty("adapter", adapter);
        p.setProperty("reason", reason);
        p.setProperty("workshopIds", String.join(";", ids));
        p.setProperty("updateAgeMillis", Long.toString(elapsedMillis));
        p.setProperty("status", dryRun ? "dry-run" : "requested");
        Path temp = Files.createTempFile(absolute.getParent(), "restart-marker-", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp.toFile());
                 Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                p.store(writer, "WorkshopSentinel restart request; not proof of actual restart");
                writer.flush();
                out.getFD().sync();
            }
            // Fail closed if filesystem cannot provide atomic replacement.
            Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
