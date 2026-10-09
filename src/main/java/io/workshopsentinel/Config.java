package io.workshopsentinel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Config {
    public final boolean dryRun, shutdownEnabled, clientOptional;
    public final String provider, shutdownAdapter;
    public final long checkSeconds, pollSeconds, waitSeconds, countdownSeconds, tickMillis, timeoutSeconds;
    public final Path directory, serverIni, triggerFile, markerFile, baselineFile;
    public final Set<String> explicitIds;

    public Config(Properties p, Path file, Path defaultIni) {
        directory = file.toAbsolutePath().normalize().getParent();
        dryRun = bool(p, "dryRun", true);
        shutdownEnabled = bool(p, "shutdownEnabled", false);
        clientOptional = bool(p, "clientOptional", true);
        provider = p.getProperty("provider", "steam").trim();
        shutdownAdapter = p.getProperty("shutdownAdapter", "marker").trim();
        if (!Set.of("steam", "noop", "file").contains(provider)) throw new IllegalArgumentException("Unknown provider");
        if (!Set.of("marker", "pz-quit-experimental").contains(shutdownAdapter)) throw new IllegalArgumentException("Unknown shutdownAdapter");
        if (!dryRun && shutdownEnabled && provider.equals("file")) throw new IllegalArgumentException("Simulation provider cannot stop a live server");
        checkSeconds = number(p, "checkIntervalSeconds", 300, 1, 86400);
        pollSeconds = number(p, "playerPollSeconds", 60, 1, 86400);
        waitSeconds = number(p, "maxWaitSeconds", 1800, 1, 86400);
        countdownSeconds = number(p, "countdownSeconds", 300, 1, 86400);
        tickMillis = number(p, "tickMillis", 1000, 100, 60000);
        timeoutSeconds = number(p, "httpTimeoutSeconds", 20, 1, 120);
        serverIni = path(p, "serverIni", defaultIni.toAbsolutePath().toString());
        triggerFile = path(p, "triggerFile", "simulate-update.txt");
        markerFile = path(p, "markerFile", "restart-marker.properties");
        baselineFile = p.getProperty("installedBaselineFile", "").trim().isEmpty() ? null : path(p, "installedBaselineFile", "");
        explicitIds = ids(p.getProperty("workshopIds", ""));
    }

    public static Config load(Path file, Path defaultIni) throws IOException {
        Properties p = new Properties();
        if (Files.exists(file)) try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
        return new Config(p, file, defaultIni);
    }
    private Path path(Properties p, String key, String fallback) {
        String configured = p.getProperty(key, "").trim();
        Path value = Paths.get(configured.isEmpty() ? fallback : configured);
        return (value.isAbsolute() ? value : directory.resolve(value)).normalize();
    }
    private static boolean bool(Properties p, String key, boolean fallback) {
        String s = p.getProperty(key, Boolean.toString(fallback)).trim();
        if (!s.equals("true") && !s.equals("false")) throw new IllegalArgumentException(key + " must be true or false");
        return Boolean.parseBoolean(s);
    }
    private static long number(Properties p, String key, long fallback, long min, long max) {
        long n = Long.parseLong(p.getProperty(key, Long.toString(fallback)).trim());
        if (n < min || n > max) throw new IllegalArgumentException(key + " outside " + min + ".." + max);
        return n;
    }
    public static Set<String> ids(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String id : text.split("[;,\\s]+")) {
            if (id.isEmpty()) continue;
            if (!id.matches("[1-9][0-9]{0,19}") || new java.math.BigInteger(id).bitLength() > 64)
                throw new IllegalArgumentException("Invalid Workshop ID: " + id);
            result.add(id);
        }
        return Collections.unmodifiableSet(result);
    }
    public Set<String> configuredWorkshopIds() throws IOException {
        if (!explicitIds.isEmpty()) return explicitIds;
        for (String line : Files.readAllLines(serverIni, StandardCharsets.UTF_8)) {
            String value = line.trim();
            if (value.startsWith("WorkshopItems=")) return ids(value.substring("WorkshopItems=".length()));
        }
        throw new IOException("WorkshopItems not found in " + serverIni);
    }
}
