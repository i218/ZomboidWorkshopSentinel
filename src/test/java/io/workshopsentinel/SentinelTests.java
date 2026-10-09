package io.workshopsentinel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/** Standalone behavioral suite. Game fixtures are test doubles, not live B42 validation. */
public final class SentinelTests {
    private static final Logger LOG = Logger.getLogger("WorkshopSentinelTests");
    private static final Set<String> IDS = Set.of("3619862853");
    private static int passed;
    private static Path temp;
    private interface Test { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        LOG.setLevel(Level.OFF);
        temp = Files.createTempDirectory("workshop-sentinel-tests-");
        try {
            run("safe defaults and config validation", SentinelTests::config);
            run("automatic running server name and explicit override", SentinelTests::serverName);
            run("configured server WorkshopItems and explicit IDs", SentinelTests::items);
            run("empty server requests once immediately", SentinelTests::empty);
            run("30 minute wait plus full 5 minute countdown", SentinelTests::countdown);
            run("later updates do not reset detection time", SentinelTests::sticky);
            run("leaving during countdown restarts early", SentinelTests::leaving);
            run("unknown players cannot trigger restart", SentinelTests::unknown);
            run("failed warning blocks countdown", SentinelTests::chatFailure);
            run("delayed tick preserves full safe-exit window", SentinelTests::delayedTick);
            run("failed restart preparation retries without losing timer", SentinelTests::markerFailure);
            run("atomic informational marker roundtrip", SentinelTests::marker);
            run("Steam XML validation and unavailable item failure", SentinelTests::xml);
            run("Steam first baseline and sticky changed revision", SentinelTests::steamBaseline);
            run("verified installed baseline catches stale startup", SentinelTests::installedBaseline);
            run("partial batches do not commit a baseline", SentinelTests::batchFailure);
            run("file stub only returns configured IDs", SentinelTests::stub);
            run("worker isolates provider errors and continues polling", SentinelTests::schedulerRecovery);
            run("dry-run suppresses even enabled shutdown adapter", () -> serviceShutdown(true, true, 0));
            run("disabled shutdown suppresses adapter", () -> serviceShutdown(false, false, 0));
            run("explicitly enabled shutdown invoked once", () -> serviceShutdown(false, true, 1));
            run("shutdown failure is not retried", SentinelTests::shutdownFailure);
            run("public PZ adapter contracts with test doubles", SentinelTests::gameAdapter);
            run("optional client mode retains loaded runtime and other required mods", SentinelTests::optionalClient);
            run("optional adapter refuses aliased or unloaded lists", SentinelTests::optionalClientGuards);
            run("bootstrap registers before server flag, client tick cannot start service, server tick can", SentinelTests::bootstrapGuard);
            if (args.length > 0) run("recorded live Steam XML response", () ->
                eq(SteamWorkshopUpdateProvider.parse(Files.readString(Paths.get(args[0])), IDS).keySet(), IDS));
            System.out.println("PASS: " + passed + " behavioral tests (game integration uses test doubles)");
        } finally {
            try (java.util.stream.Stream<Path> stream = Files.walk(temp)) {
                for (Path p : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
            }
        }
    }
    private static void run(String name, Test test) throws Exception { test.run(); passed++; System.out.println("PASS " + name); }
    private static void serverName() {
        String previous = System.getProperty("workshopsentinel.serverName");
        String gameName = zombie.network.GameServer.serverName;
        try {
            System.clearProperty("workshopsentinel.serverName");
            zombie.network.GameServer.serverName = "servertest1";
            eq(Main.serverName(), "servertest1");
            System.setProperty("workshopsentinel.serverName", "override");
            eq(Main.serverName(), "override");
            System.clearProperty("workshopsentinel.serverName");
            zombie.network.GameServer.serverName = null;
            eq(Main.serverName(), "servertest");
        } finally {
            zombie.network.GameServer.serverName = gameName;
            if (previous == null) System.clearProperty("workshopsentinel.serverName"); else System.setProperty("workshopsentinel.serverName", previous);
        }
    }
    private static void eq(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void bootstrapGuard() throws Exception {
        String oldCache = System.getProperty("workshopsentinel.cachedir");
        String oldConfig = System.getProperty("workshopsentinel.config");
        Path directory = Files.createTempDirectory(temp, "bootstrap-");
        Path config = directory.resolve("WorkshopSentinel.properties");
        Files.writeString(config, "provider=noop\nworkshopIds=3619862853\ndryRun=true\nshutdownEnabled=false\nclientOptional=false\n");
        java.lang.reflect.Field serviceField = Main.class.getDeclaredField("service");
        serviceField.setAccessible(true);
        try {
            System.setProperty("workshopsentinel.cachedir", directory.toString());
            System.setProperty("workshopsentinel.config", config.toString());
            zombie.network.GameServer.server = false;
            Main.main(new String[0]);
            eq(me.zed_0xff.zombie_buddy.Exposer.exposed, WorkshopSentinelBridge.class);
            eq(me.zed_0xff.zombie_buddy.Exposer.alias, null);
            WorkshopSentinelBridge.onTick();
            eq(serviceField.get(null), null);
            zombie.network.GameServer.server = true;
            WorkshopSentinelBridge.onTick();
            eq(serviceField.get(null) != null, true);
        } finally {
            Object service = serviceField.get(null);
            if (service != null) ((SentinelService) service).close();
            Logger logger = Logger.getLogger("WorkshopSentinel");
            for (Handler handler : logger.getHandlers()) { handler.close(); logger.removeHandler(handler); }
            zombie.network.GameServer.server = true;
            if (oldCache == null) System.clearProperty("workshopsentinel.cachedir"); else System.setProperty("workshopsentinel.cachedir", oldCache);
            if (oldConfig == null) System.clearProperty("workshopsentinel.config"); else System.setProperty("workshopsentinel.config", oldConfig);
        }
    }
    private static void fails(Test test) throws Exception {
        try { test.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Expected exception");
    }
    private static Config cfg(String... values) throws IOException {
        Properties p = new Properties();
        for (int i = 0; i < values.length; i += 2) p.setProperty(values[i], values[i + 1]);
        Path directory = Files.createTempDirectory(temp, "case-");
        return new Config(p, directory.resolve("config.properties"), directory.resolve("servertest.ini"));
    }
    private static final class Game implements GameAdapter {
        int players = 2;
        boolean brokenCount, brokenChat;
        final List<String> messages = new ArrayList<>();
        @Override public int playerCount() throws IOException { if (brokenCount) throw new IOException("Unavailable"); return players; }
        @Override public void broadcast(String s) throws IOException { if (brokenChat) throw new IOException("Chat unavailable"); messages.add(s); }
    }
    private static final class Scenario {
        final Game game = new Game();
        final List<String> reasons = new ArrayList<>();
        final SentinelEngine engine;
        Scenario() throws IOException { this(cfg()); }
        Scenario(Config c) { engine = new SentinelEngine(c, game, (reason, ids, age) -> reasons.add(reason), LOG); }
        void start() { engine.found(IDS, 0); engine.tick(0); }
    }
    private static void config() throws Exception {
        Config c = cfg(); eq(c.dryRun, true); eq(c.shutdownEnabled, false); eq(c.checkSeconds, 300L);
        eq(c.pollSeconds, 60L); eq(c.waitSeconds, 1800L); eq(c.countdownSeconds, 300L);
        eq(c.clientOptional, true); eq(cfg("clientOptional", "false").clientOptional, false);
        fails(() -> cfg("clientOptional", "yes"));
        fails(() -> cfg("dryRun", "yes")); fails(() -> cfg("tickMillis", "0"));
        fails(() -> cfg("provider", "unknown")); fails(() -> Config.ids("18446744073709551616"));
        fails(() -> cfg("provider", "file", "dryRun", "false", "shutdownEnabled", "true"));
    }
    private static void items() throws Exception {
        Config c = cfg(); Files.writeString(c.serverIni, "Mods=SomeMod\nWorkshopItems=3619862853;123\n");
        eq(c.configuredWorkshopIds(), Set.of("3619862853", "123"));
        eq(cfg("workshopIds", "123;456").configuredWorkshopIds(), Set.of("123", "456"));
        fails(() -> cfg().configuredWorkshopIds());
    }
    private static void empty() throws Exception {
        Scenario s = new Scenario(); s.game.players = 0; s.start(); s.engine.tick(10000);
        eq(s.engine.state(), SentinelEngine.State.RESTARTING); eq(s.reasons, List.of("empty-server"));
    }
    private static void countdown() throws Exception {
        Scenario s = new Scenario(); s.start(); s.engine.tick(1799999);
        eq(s.engine.state(), SentinelEngine.State.UPDATE_PENDING);
        s.engine.tick(1800000); eq(s.engine.state(), SentinelEngine.State.COUNTDOWN);
        s.engine.tick(2099999); eq(s.reasons.size(), 0);
        s.engine.tick(2100000); eq(s.reasons, List.of("forced-countdown-complete"));
    }
    private static void sticky() throws Exception {
        Scenario s = new Scenario(); s.start(); s.engine.found(Set.of("123"), 1700000);
        s.engine.found(Collections.emptySet(), 1799999); s.engine.tick(1800000);
        eq(s.engine.state(), SentinelEngine.State.COUNTDOWN); eq(s.engine.updates(), Set.of("123", "3619862853"));
    }
    private static void leaving() throws Exception {
        Scenario s = new Scenario(); s.start(); s.engine.tick(1800000); s.game.players = 0;
        s.engine.tick(1860000); eq(s.reasons, List.of("empty-server"));
    }
    private static void unknown() throws Exception {
        Scenario s = new Scenario(); s.start(); s.game.brokenCount = true; s.engine.tick(1800000);
        eq(s.engine.state(), SentinelEngine.State.UPDATE_PENDING); s.game.brokenCount = false;
        s.engine.tick(1860000); eq(s.engine.state(), SentinelEngine.State.COUNTDOWN);
        s.game.players = -1; s.engine.tick(2160000); eq(s.reasons.size(), 0);
        s.game.players = 2; s.engine.tick(2220000); eq(s.reasons.size(), 1);
    }
    private static void chatFailure() throws Exception {
        Scenario s = new Scenario(); s.start(); s.game.brokenChat = true; s.engine.tick(1800000);
        eq(s.engine.state(), SentinelEngine.State.UPDATE_PENDING);
        s.game.brokenChat = false; s.engine.tick(1860000); eq(s.engine.state(), SentinelEngine.State.COUNTDOWN);
        s.engine.tick(2100000); eq(s.reasons.size(), 0); s.engine.tick(2160000); eq(s.reasons.size(), 1);
    }
    private static void delayedTick() throws Exception {
        Scenario s = new Scenario(); s.start(); s.engine.tick(3600000);
        eq(s.engine.state(), SentinelEngine.State.COUNTDOWN); s.engine.tick(3899999); eq(s.reasons.size(), 0);
        s.engine.tick(3900000); eq(s.reasons.size(), 1);
    }
    private static void markerFailure() throws Exception {
        Game game = new Game(); game.players = 0; int[] attempts = {0};
        SentinelEngine e = new SentinelEngine(cfg(), game, (reason, ids, age) -> {
            if (++attempts[0] == 1) throw new IOException("Disk unavailable");
        }, LOG);
        e.found(IDS, 0); e.tick(0); eq(e.state(), SentinelEngine.State.UPDATE_PENDING);
        e.tick(59000); eq(attempts[0], 1); e.tick(60000); eq(e.state(), SentinelEngine.State.RESTARTING);
    }
    private static void marker() throws Exception {
        Config c = cfg(); RestartMarker.write(c.markerFile, true, false, "marker", "empty-server", IDS, 123);
        Properties p = new Properties(); try (Reader r = Files.newBufferedReader(c.markerFile)) { p.load(r); }
        eq(p.getProperty("dryRun"), "true"); eq(p.getProperty("status"), "dry-run");
        eq(p.getProperty("workshopIds"), "3619862853");
        try (java.util.stream.Stream<Path> files = Files.list(c.directory)) { eq(files.count(), 1L); }
    }
    private static String xml(Set<String> ids, long revision) {
        StringBuilder b = new StringBuilder("<response><publishedfiledetails>");
        for (String id : ids) b.append("<publishedfile><publishedfileid>").append(id)
            .append("</publishedfileid><result>1</result><consumer_app_id>108600</consumer_app_id><time_updated>")
            .append(revision).append("</time_updated></publishedfile>");
        return b.append("</publishedfiledetails></response>").toString();
    }
    private static void xml() throws Exception {
        eq(SteamWorkshopUpdateProvider.parse(xml(IDS, 100), IDS).get("3619862853"), 100L);
        eq(SteamWorkshopUpdateProvider.parse("<?xml version=\"1.0\"?>\n<!DOCTYPE response>\n" + xml(IDS, 100), IDS).get("3619862853"), 100L);
        fails(() -> SteamWorkshopUpdateProvider.parse(xml(IDS, 100).replace("<result>1", "<result>9"), IDS));
        fails(() -> SteamWorkshopUpdateProvider.parse(xml(IDS, 100).replace("108600", "1"), IDS));
        fails(() -> SteamWorkshopUpdateProvider.parse(xml(Set.of("123"), 100), IDS));
        fails(() -> SteamWorkshopUpdateProvider.parse(xml(Collections.emptySet(), 100), IDS));
        fails(() -> SteamWorkshopUpdateProvider.parse("<response/>", IDS));
        fails(() -> SteamWorkshopUpdateProvider.parse("<!DOCTYPE response [<!ENTITY x SYSTEM 'file:///x'>]><response/>", IDS));
    }
    private static void steamBaseline() throws Exception {
        long[] revision = {100};
        SteamWorkshopUpdateProvider p = new SteamWorkshopUpdateProvider(body -> {
            if (!body.contains("publishedfileids%5B0%5D=3619862853") || !body.contains("format=xml"))
                throw new AssertionError("Bad request");
            return xml(IDS, revision[0]);
        }, null, LOG);
        eq(p.check(IDS), Collections.emptySet()); revision[0] = 200;
        eq(p.check(IDS), IDS); eq(p.check(IDS), IDS);
    }
    private static void installedBaseline() throws Exception {
        Path baseline = temp.resolve("installed.properties"); Files.writeString(baseline, "3619862853=100\n");
        SteamWorkshopUpdateProvider p = new SteamWorkshopUpdateProvider(body -> xml(IDS, 200), baseline, LOG);
        eq(p.check(IDS), IDS);
    }
    private static void batchFailure() throws Exception {
        Set<String> ids = new LinkedHashSet<>(); for (int i = 1; i <= 101; i++) ids.add(Integer.toString(i));
        int[] calls = {0}; long[] revision = {100};
        SteamWorkshopUpdateProvider p = new SteamWorkshopUpdateProvider(body -> {
            if (++calls[0] == 2) throw new IOException("Batch failure");
            Set<String> batch = new LinkedHashSet<>();
            for (String part : body.split("&")) if (part.startsWith("publishedfileids")) batch.add(part.substring(part.indexOf('=') + 1));
            return xml(batch, revision[0]);
        }, null, LOG);
        fails(() -> p.check(ids)); revision[0] = 200;
        eq(p.check(ids), Collections.emptySet()); revision[0] = 300; eq(p.check(ids), ids);
    }
    private static void stub() throws Exception {
        Path file = temp.resolve("trigger.txt"); WorkshopUpdateProvider p = StubProviders.file(file);
        eq(p.check(IDS), Collections.emptySet()); Files.writeString(file, "3619862853;123\n"); eq(p.check(IDS), IDS);
        eq(StubProviders.noop().check(IDS), Collections.emptySet());
    }
    private static void schedulerRecovery() throws Exception {
        Config c = cfg("workshopIds", "3619862853", "checkIntervalSeconds", "1");
        CountDownLatch second = new CountDownLatch(1); int[] calls = {0};
        WorkshopUpdateProvider p = ids -> { if (++calls[0] == 1) throw new IOException("Steam offline"); second.countDown(); return IDS; };
        Game game = new Game();
        try (SentinelService s = new SentinelService(c, game, p, () -> { throw new AssertionError("Unsafe shutdown"); }, System::currentTimeMillis, LOG)) {
            if (!second.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker did not recover");
            awaitTick(s, () -> s.state() == SentinelEngine.State.UPDATE_PENDING);
            eq(game.messages.size(), 1);
        }
    }
    private static void serviceShutdown(boolean dry, boolean enabled, int expected) throws Exception {
        Config c = cfg("workshopIds", "3619862853", "dryRun", Boolean.toString(dry), "shutdownEnabled", Boolean.toString(enabled));
        int[] shutdowns = {0}; Game game = new Game(); game.players = 0;
        try (SentinelService s = new SentinelService(c, game, ids -> IDS, () -> shutdowns[0]++, System::currentTimeMillis, LOG)) {
            awaitTick(s, () -> s.state() == SentinelEngine.State.RESTARTING);
            for (int i = 0; i < 5; i++) s.onTick(); eq(shutdowns[0], expected);
            eq(Files.exists(c.markerFile), true);
        }
    }
    private static void shutdownFailure() throws Exception {
        Config c = cfg("workshopIds", "3619862853", "dryRun", "false", "shutdownEnabled", "true");
        int[] attempts = {0}; Game game = new Game(); game.players = 0;
        try (SentinelService s = new SentinelService(c, game, ids -> IDS, () -> { attempts[0]++; throw new IOException("Quit failed"); }, System::currentTimeMillis, LOG)) {
            awaitTick(s, () -> s.state() == SentinelEngine.State.RESTARTING); s.onTick(); eq(attempts[0], 1);
        }
    }
    private static void awaitTick(SentinelService s, java.util.function.BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) { s.onTick(); Thread.sleep(10); }
        if (!done.getAsBoolean()) throw new AssertionError("Tick condition timeout");
    }
    private static void gameAdapter() throws Exception {
        PzGameAdapter p = new PzGameAdapter(); eq(p.playerCount(), 3); p.broadcast("test");
        eq(zombie.network.chat.ChatServer.lastMessage, "[WorkshopSentinel] test");
        zombie.network.GameServer.server = false; fails(p::playerCount); zombie.network.GameServer.server = true;
    }
    private static void optionalClient() throws Exception {
        List<String> advertised = zombie.network.GameServer.ServerMods;
        List<String> loaded = zombie.ZomboidFileSystem.instance.loaded;
        advertised.clear(); loaded.clear();
        List<String> original = List.of("ZombieBuddy", "WorkshopSentinel", "OtherMod", "\\WorkshopSentinel", "WorkshopSentinelExtra");
        advertised.addAll(original); loaded.addAll(original);
        try {
            OptionalClientModAdapter.apply(LOG);
            eq(advertised, List.of("ZombieBuddy", "OtherMod", "WorkshopSentinelExtra"));
            eq(loaded, original);
            OptionalClientModAdapter.apply(LOG);
            eq(advertised, List.of("ZombieBuddy", "OtherMod", "WorkshopSentinelExtra"));
        } finally { advertised.clear(); loaded.clear(); }
    }
    private static void optionalClientGuards() throws Exception {
        List<String> aliases = new ArrayList<>(List.of("WorkshopSentinel", "OtherMod"));
        fails(() -> OptionalClientModAdapter.exclude(aliases, aliases));
        eq(aliases, List.of("WorkshopSentinel", "OtherMod"));
        fails(() -> OptionalClientModAdapter.exclude(aliases, List.of("OtherMod")));
        eq(aliases, List.of("WorkshopSentinel", "OtherMod"));
        fails(() -> OptionalClientModAdapter.exclude(null, List.of("WorkshopSentinel")));
    }
}
