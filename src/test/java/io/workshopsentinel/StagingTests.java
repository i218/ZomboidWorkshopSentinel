package io.workshopsentinel;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

/** Real filesystem/locks/journals; Steam is deterministic here, never a live server restart. */
public final class StagingTests {
    static final Logger LOG=Logger.getAnonymousLogger();static int passed;
    interface Action {void run()throws Exception;}
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void fails(Action action)throws Exception {try{action.run();}catch(IOException | IllegalArgumentException expected){return;}throw new AssertionError("Expected rejection");}
    static void test(String name,Action action)throws Exception {action.run();System.out.println("PASS staging: "+name);passed++;}
    static String acf(Map<String,Long> revisions,Map<String,String> manifests){
        StringBuilder installed=new StringBuilder(),details=new StringBuilder();
        for(String id:revisions.keySet()){
            String row="\""+id+"\" { \"manifest\" \""+manifests.get(id)+"\" \"timeupdated\" \""+revisions.get(id)+"\" ";
            installed.append(row).append("\"size\" \"3\" } ");
            details.append(row).append("\"latest_manifest\" \"").append(manifests.get(id)).append("\" \"latest_timeupdated\" \"").append(revisions.get(id)).append("\" } ");
        }
        return "\"AppWorkshop\" { \"appid\" \"108600\" \"unknown\" \"retained\" \"SizeOnDisk\" \"3\" \"WorkshopItemsInstalled\" {"+installed+"} \"WorkshopItemDetails\" {"+details+"} }";
    }
    static final class Fixture {
        final Path base,live,root,source;final StagingSettings settings;
        final Map<String,Long> remote=new LinkedHashMap<>();final Map<String,String> manifests=new LinkedHashMap<>();
        int downloads;Action duringDownload=()->{};boolean failDownload,wrongRevision;
        Fixture()throws Exception {
            Path tests=Path.of("build/staging-tests").toAbsolutePath();Files.createDirectories(tests);
            base=Files.createTempDirectory(tests,"scenario-");live=base.resolve("server/steamapps/workshop");root=base.resolve("staging");
            source=root.resolve("fake-workshop");Files.createDirectories(live.resolve("content/108600/10"));
            Files.writeString(live.resolve("content/108600/10/data.txt"),"old");
            Files.writeString(live.resolve("appworkshop_108600.acf"),acf(Map.of("10",100L),Map.of("10","1000")));
            remote.put("10",200L);manifests.put("10","2000");
            Properties p=new Properties();p.setProperty("stagingEnabled","true");p.setProperty("stagingDirectory",root.toString());p.setProperty("stagingWorkshopDirectory",live.toString());
            settings=new StagingSettings(p,base);
        }
        WorkshopStaging open()throws IOException {return new WorkshopStaging(settings,ids->{Map<String,Long> result=new LinkedHashMap<>();for(String id:ids)result.put(id,remote.get(id));return result;},ids->{
            downloads++;if(failDownload)throw new IOException("simulated Steam outage");duringDownload.run();
            Files.createDirectories(source);Map<String,Long> stamps=new LinkedHashMap<>();Map<String,String> idsManifests=new LinkedHashMap<>();
            for(String id:ids){stamps.put(id,wrongRevision?100L:remote.get(id));idsManifests.put(id,manifests.get(id));Path folder=source.resolve("content/108600").resolve(id);Files.createDirectories(folder);Files.writeString(folder.resolve("data.txt"),manifests.get(id));}
            Files.writeString(source.resolve("appworkshop_108600.acf"),acf(stamps,idsManifests));
            return new WorkshopStaging.Download(source, ()->{});
        },LOG);}
        String content()throws IOException{return Files.readString(live.resolve("content/108600/10/data.txt"));}
        Path bundle()throws IOException {Properties p=StageFiles.properties(root.resolve("pending.properties"));return root.resolve("ready/10").resolve(p.getProperty("10"));}
    }
    public static void main(String[] args)throws Exception {
        LOG.setLevel(Level.OFF);
        if(args.length==3 && args[0].equals("live-refresh")){
            Config config=Config.load(Path.of(args[1]),Path.of(args[2]));
            try(WorkshopStaging staging=WorkshopStaging.open(config,Logger.getAnonymousLogger())){
                check(staging.refresh(config.configuredWorkshopIds()).equals(config.configuredWorkshopIds()),
                    "Live fixture must stage an item absent from the running server cache");
            }
            return;
        }
        if(args.length==2 && args[0].equals("lease")){
            Path base=Path.of(args[1]);Properties p=new Properties();p.setProperty("stagingEnabled","true");
            p.setProperty("stagingDirectory",base.resolve("staging").toString());p.setProperty("stagingWorkshopDirectory",base.resolve("server/steamapps/workshop").toString());
            try(WorkshopStaging s=new WorkshopStaging(new StagingSettings(p,base),ids->Map.of(),ids->{throw new AssertionError("Not a download test");},LOG)){
                Files.writeString(base.resolve("lease-ready"),"ready");System.in.read();
            }return;
        }
        test("public defaults and isolated path validation",()->{
            Properties p=new Properties();Config c=new Config(p,Path.of("test/config.properties"),Path.of("test/server.ini"));check(!c.staging.enabled && c.dryRun,"defaults changed");
            p.setProperty("stagingEnabled","true");fails(()->new StagingSettings(p,Path.of("test")));
            p.setProperty("stagingDirectory","same");p.setProperty("stagingWorkshopDirectory","same/sub");fails(()->new StagingSettings(p,Path.of("test")));
        });
        test("download queue never alters live files and catches startup-baseline misses",()->{
            Fixture f=new Fixture();String acf=Files.readString(f.live.resolve("appworkshop_108600.acf"));
            try(WorkshopStaging s=f.open()){
                check(s.refresh(Set.of("10")).equals(Set.of("10")),"missed revision not surfaced");check(f.content().equals("old"),"runtime installed files");
                check(Files.readString(f.live.resolve("appworkshop_108600.acf")).equals(acf),"runtime modified ACF");
                s.refresh(Set.of("10"));check(f.downloads==1,"same staged revision downloaded again");
                f.remote.put("10",300L);f.manifests.put("10","3000");s.refresh(Set.of("10"));check(f.downloads==2,"second public patch skipped");
            }
        });
        test("SHA-256 rejects corrupt files, extra files and receipt mismatch",()->{
            Fixture f=new Fixture();try(WorkshopStaging s=f.open()){
                s.refresh(Set.of("10"));Path bundle=f.bundle();Files.writeString(bundle.resolve("content/data.txt"),"broken");fails(()->s.verify(bundle,"10"));
                Files.writeString(bundle.resolve("content/data.txt"),"2000");Files.writeString(bundle.resolve("content/extra.txt"),"unexpected");fails(()->s.verify(bundle,"10"));Files.delete(bundle.resolve("content/extra.txt"));
                Properties p=StageFiles.properties(bundle.resolve("receipt.properties"));p.setProperty("manifest","12345");StageFiles.properties(bundle.resolve("receipt.properties"),p);fails(()->s.verify(bundle,"10"));
            }
        });
        test("pre-start replaces old queue with newest public revision; repeat apply is idempotent",()->{
            Fixture f=new Fixture();WorkshopAcf original=WorkshopAcf.read(f.live.resolve("appworkshop_108600.acf"));
            WorkshopAcf.node(original.items(),"10").put("custom-item","keep");WorkshopAcf.node(original.details(),"10").put("custom-details","keep");
            Files.writeString(f.live.resolve("appworkshop_108600.acf"),original.encode());
            try(WorkshopStaging s=f.open()){
                s.refresh(Set.of("10"));f.remote.put("10",300L);f.manifests.put("10","3000");s.applyAtStartup(Set.of("10"));
                check(f.content().equals("3000"),"old queued patch applied");WorkshopAcf acf=WorkshopAcf.read(f.live.resolve("appworkshop_108600.acf"));
                check(acf.updated("10")==300 && acf.manifest("10").equals("3000"),"ACF identity differs");check(acf.app().get("unknown").equals("retained"),"unknown ACF fields lost");
                check(WorkshopAcf.node(acf.items(),"10").get("custom-item").equals("keep") && WorkshopAcf.node(acf.details(),"10").get("custom-details").equals("keep"),"per-item administrator fields lost");
                String before=Files.readString(f.live.resolve("appworkshop_108600.acf"));s.applyAtStartup(Set.of("10"));
                check(Files.readString(f.live.resolve("appworkshop_108600.acf")).equals(before),"identical revision installed twice");
                Files.writeString(f.live.resolve("content/108600/10/data.txt"),"corrupt-live");s.applyAtStartup(Set.of("10"));check(f.content().equals("3000"),"corrupt installed bytes accepted as current");
            }
        });
        test("same-second manifest changes are revalidated at startup",()->{
            Fixture f=new Fixture();try(WorkshopStaging s=f.open()){
                s.refresh(Set.of("10"));f.manifests.put("10","2222");s.applyAtStartup(Set.of("10"));check(f.content().equals("2222"),"timestamp-only stale version accepted");
            }
            Fixture race=new Fixture();race.duringDownload=()->{if(race.downloads==2)race.manifests.put("10","2222");};
            try(WorkshopStaging s=race.open()){s.applyAtStartup(Set.of("10"));check(race.content().equals("2222"),"same-second publication between native checks was missed");}
        });
        test("a valid queued bundle needs only one fresh native download before apply",()->{
            Fixture f=new Fixture();
            try(WorkshopStaging s=f.open()){
                s.refresh(Set.of("10"));
                s.applyAtStartup(Set.of("10"));
                check(f.downloads==2 && f.content().equals("2000"),"Queued content was not reused and independently verified");
            }
        });
        test("a second publication during download triggers retry; continuous churn refuses startup",()->{
            Fixture f=new Fixture();f.duringDownload=()->{if(f.downloads==1){f.remote.put("10",300L);f.manifests.put("10","3000");}};
            try(WorkshopStaging s=f.open()){s.applyAtStartup(Set.of("10"));check(f.content().equals("3000"),"did not refresh changed publication");}
            Fixture churn=new Fixture();churn.duringDownload=()->churn.remote.put("10",churn.remote.get("10")+1);
            try(WorkshopStaging s=churn.open()){fails(()->s.applyAtStartup(Set.of("10")));check(churn.content().equals("old"),"churning content was installed");}
        });
        test("failed/unavailable/incomplete downloads cannot apply or downgrade newer installed data",()->{
            Fixture f=new Fixture();f.failDownload=true;try(WorkshopStaging s=f.open()){fails(()->s.applyAtStartup(Set.of("10")));check(f.content().equals("old"),"failed download changed live files");}
            Fixture wrong=new Fixture();wrong.wrongRevision=true;try(WorkshopStaging s=wrong.open()){fails(()->s.applyAtStartup(Set.of("10")));check(wrong.content().equals("old"),"unverified source applied");}
            Fixture newer=new Fixture();Files.writeString(newer.live.resolve("appworkshop_108600.acf"),acf(Map.of("10",500L),Map.of("10","5000")));
            try(WorkshopStaging s=newer.open()){fails(()->s.applyAtStartup(Set.of("10")));check(newer.content().equals("old"),"newer installed item overwritten");}
        });
        test("live-process and concurrent helper lock excludes application",()->{
            Fixture f=new Fixture();try(WorkshopStaging one=f.open()){fails(()->{try(WorkshopStaging ignored=f.open()){} });}
            try(WorkshopStaging again=f.open()){check(again!=null,"lease not released");}
        });
        test("another JVM cannot apply while the runtime lease is held",()->{
            Fixture f=new Fixture();String javaCommand=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            Process holder=new ProcessBuilder(javaCommand,"-cp",System.getProperty("java.class.path"),StagingTests.class.getName(),"lease",f.base.toString()).redirectErrorStream(true).start();
            try {
                for(int i=0;i<200 && !Files.exists(f.base.resolve("lease-ready")) && holder.isAlive();i++)Thread.sleep(25);
                check(Files.exists(f.base.resolve("lease-ready")),"child JVM did not acquire runtime lease");
                fails(()->{try(WorkshopStaging ignored=f.open()){} });
            }finally{holder.getOutputStream().close();if(!holder.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))holder.destroyForcibly();}
        });
        test("ordinary I/O failure rolls content and ACF back immediately",()->{
            Fixture f=new Fixture();String original=Files.readString(f.live.resolve("appworkshop_108600.acf"));
            try(WorkshopStaging s=f.open()) {s.boundary=phase->{if(phase.equals("acf"))throw new IOException("injected disk error");};fails(()->s.applyAtStartup(Set.of("10")));}
            check(f.content().equals("old") && original.equals(Files.readString(f.live.resolve("appworkshop_108600.acf"))),"failed apply not rolled back");
        });
        test("crash recovery at each durable swap boundary",()->{
            for(String stop:List.of("prepared","old:10","new:10","acf")){
                Fixture f=new Fixture();String original=Files.readString(f.live.resolve("appworkshop_108600.acf"));
                try(WorkshopStaging s=f.open()){s.boundary=phase->{if(phase.equals(stop))throw new SimulatedCrash();};try{s.applyAtStartup(Set.of("10"));throw new AssertionError("crash not injected");}catch(SimulatedCrash expected){}}
                try(WorkshopStaging recovered=f.open()){recovered.recover();recovered.recover();}
                check(f.content().equals("old") && Files.readString(f.live.resolve("appworkshop_108600.acf")).equals(original),"crash rollback failed: "+stop);
            }
        });
        test("multi-item crash restores both old and previously missing items",()->{
            Fixture f=new Fixture();f.remote.put("20",200L);f.manifests.put("20","2000");
            try(WorkshopStaging s=f.open()){s.boundary=phase->{if(phase.equals("new:20"))throw new SimulatedCrash();};try{s.applyAtStartup(new LinkedHashSet<>(List.of("10","20")));}catch(SimulatedCrash expected){}}
            try(WorkshopStaging s=f.open()){s.recover();}
            check(f.content().equals("old") && !Files.exists(f.live.resolve("content/108600/20")),"new item remained after rollback");
        });
        test("ambiguous ACF, path traversal and configured byte limits rejected",()->{
            Fixture f=new Fixture();Files.writeString(f.live.resolve("appworkshop_108600.acf"),"\"AppWorkshop\" {\"appid\" \"108600\" \"appid\" \"1\"}");fails(f::open);
            Fixture clean=new Fixture();try(WorkshopStaging s=clean.open()){fails(()->s.refresh(Set.of("../10")));}
            Path tooBig=clean.base.resolve("large");Files.createDirectories(tooBig);Files.writeString(tooBig.resolve("file"),"12345");fails(()->StageFiles.inventory(tooBig,4));
        });
        test("corrupt metadata and rollback backups refuse recovery",()->{
            Fixture f=new Fixture();try(WorkshopStaging s=f.open()){s.refresh(Set.of("10"));Path receipt=f.bundle().resolve("receipt.properties");
                Files.writeString(receipt,Files.readString(receipt).replace("manifest=2000","manifest=3000"));fails(()->s.verify(f.bundle(),"10"));}
            Fixture crash=new Fixture();try(WorkshopStaging s=crash.open()){s.boundary=phase->{if(phase.equals("prepared"))throw new SimulatedCrash();};
                try{s.applyAtStartup(Set.of("10"));}catch(SimulatedCrash expected){}}
            try(DirectoryStream<Path> tx=Files.newDirectoryStream(crash.live,".workshopsentinel-txn-*")){
                Path transaction=tx.iterator().next();Files.writeString(transaction.resolve("original.acf"),"damaged");}
            try(WorkshopStaging s=crash.open()){fails(s::recover);check(crash.content().equals("old"),"bad rollback touched live files");}
        });
        test("damaged queue hints are quarantined and rebuilt from fresh Steam data",()->{
            Fixture f=new Fixture();try(WorkshopStaging s=f.open()){
                s.refresh(Set.of("10"));Files.writeString(f.root.resolve("pending.properties"),"damaged");
                s.refresh(Set.of("10"));check(f.downloads==2 && f.content().equals("old"),"queue hint corruption prevented fresh download or altered live files");
                Path receipt=f.bundle().resolve("receipt.properties");Files.writeString(receipt,"damaged");
                s.refresh(Set.of("10"));check(f.downloads==3,"corrupt cached receipt not redownloaded");
            }
        });
        System.out.println("PASS: "+passed+" staging scenarios (real filesystem, simulated Steam; no live server changes)");
    }
    static final class SimulatedCrash extends Error {}
}
