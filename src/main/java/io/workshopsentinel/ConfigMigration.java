package io.workshopsentinel;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Append missing template options without reserializing an administrator's existing file. */
final class ConfigMigration {
    final Properties properties;
    private final Path file;
    private final byte[] original, updated;
    private final boolean existed;
    private final int added;

    private ConfigMigration(Path file, byte[] original, byte[] updated, boolean existed, Properties properties, int added) {
        this.file = file; this.original = original; this.updated = updated;
        this.existed = existed; this.properties = properties; this.added = added;
    }
    static ConfigMigration plan(Path path, String template) throws IOException {
        Path file = path.toAbsolutePath().normalize();
        byte[] original;
        boolean existed = true;
        try { original = Files.readAllBytes(file); }
        catch (NoSuchFileException missing) { original = new byte[0]; existed = false; }
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(original)).toString();
        Properties values = parse(text);
        Properties defaults = parse(template);
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder addition = new StringBuilder(), comments = new StringBuilder();
        int added = 0;
        for (String line : template.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                comments.append(line).append(newline); continue;
            }
            Properties option = parse(line);
            if (option.size() != 1) throw new IOException("Invalid bundled config template");
            String key = option.stringPropertyNames().iterator().next();
            if (!values.containsKey(key)) {
                addition.append(comments).append(line).append(newline);
                values.setProperty(key, defaults.getProperty(key));
                added++;
            }
            comments.setLength(0);
        }
        byte[] updated = original;
        if (!existed) updated = template.getBytes(StandardCharsets.UTF_8);
        else if (added > 0) {
            String separator = text.endsWith("\n") || text.endsWith("\r") || text.isEmpty() ? "" : newline;
            updated = (text + separator + newline + "# Options added by WorkshopSentinel update" + newline + addition).getBytes(StandardCharsets.UTF_8);
        }
        return new ConfigMigration(file, original, updated, existed, values, added);
    }
    private static Properties parse(String text) throws IOException {
        Properties result = new Properties();
        // Preserve a UTF-8 BOM on disk, but do not treat it as part of the first property key.
        try (Reader reader = new StringReader(text.startsWith("\uFEFF") ? text.substring(1) : text)) { result.load(reader); }
        return result;
    }
    void persist(java.util.logging.Logger log) throws IOException {
        if (existed && added == 0) return;
        if (Files.isSymbolicLink(file)) throw new IOException("Config is a symbolic link; update target manually: " + file);
        if (existed) {
            java.nio.file.attribute.DosFileAttributeView dos = Files.getFileAttributeView(file, java.nio.file.attribute.DosFileAttributeView.class);
            if (dos != null && dos.readAttributes().isReadOnly()) throw new AccessDeniedException(file.toString(), null, "Configuration is read-only");
        }
        Files.createDirectories(file.getParent());
        // Never replace an edit made since the original configuration was read.
        if (existed && !Arrays.equals(Files.readAllBytes(file), original)) throw new IOException("Config changed during update: " + file);
        Path backup = null;
        if (existed) {
            backup = file.resolveSibling(file.getFileName() + ".backup-" + UUID.randomUUID() + ".bak");
            Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Path staging = Files.createTempFile(file.getParent(), "workshopsentinel-config-", ".tmp");
        try {
            if (existed) Files.copy(file, staging, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(updated);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            if (existed) {
                if (!Arrays.equals(Files.readAllBytes(file), original)) throw new IOException("Config changed during update: " + file);
                Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else Files.move(staging, file); // No replacement if another process created the config.
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(staging); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        log.info(existed ? "Configuration extended: added=" + added + "; backup=" + backup : "Default configuration created: " + file);
    }
}
