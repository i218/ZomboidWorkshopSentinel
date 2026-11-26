package io.workshopsentinel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** A new anonymous Steam server session and an empty native cache for each validation. */
final class SteamworksDownloader implements WorkshopStaging.Downloader {
    private final StagingSettings settings;
    private final Logger log;

    SteamworksDownloader(StagingSettings settings, Logger log) {
        this.settings = settings;
        this.log = log;
    }

    @Override public WorkshopStaging.Download download(Set<String> ids) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows"))
            throw new IOException("Steamworks staging currently requires Windows x64");
        if (settings.steamworksLibrary == null || settings.python.isEmpty())
            throw new IOException("Set stagingSteamworksLibrary and stagingPython before enabling Steamworks staging");
        Path python = Path.of(settings.python);
        if (!python.isAbsolute()) throw new IOException("stagingPython must be an absolute path to Python");
        StageFiles.noLinks(python);
        StageFiles.noLinks(settings.steamworksLibrary);
        if (!Files.isRegularFile(python) || !Files.isRegularFile(settings.steamworksLibrary))
            throw new IOException("Steamworks DLL or Python executable is missing");
        for (String id : ids)
            if (!Config.ids(id).equals(Set.of(id))) throw new IOException("Invalid download ID");

        Path run = Files.createTempDirectory(settings.root, "steamworks-");
        boolean ready = false;
        try {
            Files.copy(settings.steamworksLibrary, run.resolve("steam_api64.dll"));
            Path script = run.resolve("worker.py");
            try (InputStream source = getClass().getResourceAsStream("/steamworks-probe.py")) {
                if (source == null) throw new IOException("Packaged Steamworks worker is missing");
                Files.copy(source, script);
            }
            Files.writeString(run.resolve("steam_appid.txt"), "108600\n");
            Path itemList = run.resolve("items.txt");
            Files.write(itemList, ids, StandardCharsets.US_ASCII);
            ProcessBuilder builder = new ProcessBuilder(python.toString(), "-I", "-u", script.toString(),
                "--worker", "--run-dir", run.toString(), "--items-file", itemList.toString(),
                "--max-bytes", Long.toString(settings.maxBytes));
            builder.directory(run.toFile()).redirectErrorStream(true);
            builder.environment().put("SteamAppId", "108600");
            builder.environment().put("SteamGameId", "108600");
            execute(builder);
            Path cache = run.resolve("download-cache");
            validate(cache, run.resolve("installed.tsv"), ids);
            ready = true;
            return new WorkshopStaging.Download(cache, () -> cleanup(run));
        } finally {
            if (!ready) cleanup(run);
        }
    }

    private void execute(ProcessBuilder builder) throws Exception {
        Process process = builder.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    int remaining = Math.min(count, 2 * 1024 * 1024 - captured.size());
                    if (remaining > 0) captured.write(buffer, 0, remaining);
                }
            } catch (IOException ignored) {
                // A missing or incomplete native receipt is rejected separately.
            }
        }, "WorkshopSentinel-Steamworks-output");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!process.waitFor(settings.timeoutSeconds, TimeUnit.SECONDS))
                throw new IOException("Steamworks download timed out");
            drain.join(5000);
            if (drain.isAlive()) throw new IOException("Steamworks output did not close");
            StageFiles.atomic(settings.root.resolve("steamworks-last-output.txt"), captured.toString(StandardCharsets.UTF_8));
            if (process.exitValue() != 0)
                throw new IOException("Steamworks worker failed: exit=" + process.exitValue() + "; see steamworks-last-output.txt");
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    static void validate(Path cache, Path receipt, Set<String> ids) throws IOException {
        WorkshopAcf acf = WorkshopAcf.read(cache.resolve("appworkshop_108600.acf"));
        StageFiles.noLinks(receipt);
        if (Files.size(receipt) > 128L * ids.size()) throw new IOException("Oversized Steamworks receipt");
        Set<String> seen = new HashSet<>();
        for (String line : Files.readAllLines(receipt, StandardCharsets.US_ASCII)) {
            String[] row = line.split("\t", -1);
            if (row.length != 3 || !ids.contains(row[0]) || !seen.add(row[0]))
                throw new IOException("Unexpected Steamworks receipt item");
            Map<String,Object> installed = WorkshopAcf.node(acf.items(), row[0]);
            if (!row[1].equals(installed.get("size")) || !row[2].equals(installed.get("timeupdated")))
                throw new IOException("Steamworks install info differs from ACF for item " + row[0]);
            acf.verified(row[0], acf.updated(row[0]));
        }
        if (!seen.equals(ids)) throw new IOException("Incomplete Steamworks receipt");
    }

    private void cleanup(Path run) {
        try {
            StageFiles.noLinks(run);
            // walk() never follows links. Refuse reparse directories before descending.
            List<Path> entries = new ArrayList<>();
            Files.walkFileTree(run, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    StageFiles.noLinks(dir);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    entries.add(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    if (error != null) throw error;
                    entries.add(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            for (Path entry : entries) Files.delete(entry);
        } catch (IOException e) {
            log.warning("Steamworks temporary cache cleanup failed: " + run + "; " + e.getMessage());
        }
    }
}
