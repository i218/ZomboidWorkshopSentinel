package io.workshopsentinel;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class StubProviders {
    private StubProviders() { }
    public static WorkshopUpdateProvider noop() { return ids -> Collections.emptySet(); }
    /** Only accepts configured IDs; file remains intact for repeatable dry-run tests. */
    public static WorkshopUpdateProvider file(Path path) {
        return ids -> {
            if (!Files.exists(path)) return Collections.emptySet();
            Set<String> updates = new LinkedHashSet<>(Config.ids(Files.readString(path, StandardCharsets.UTF_8)));
            updates.retainAll(ids);
            return updates;
        };
    }
}
