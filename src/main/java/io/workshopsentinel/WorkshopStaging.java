package io.workshopsentinel;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/** Isolated download queue and an offline, journalled pre-start transaction. */
public final class WorkshopStaging implements AutoCloseable {
    @FunctionalInterface public interface Revisions { Map<String,Long> read(Set<String> ids)throws Exception; }
    @FunctionalInterface public interface Downloader { void download(Set<String> ids)throws Exception; }
    private final StagingSettings settings;
    private final Revisions remote;
    private final Downloader downloader;
    private final Logger log;
    private final FileChannel channel;
    private final FileLock lease;
    private final Path acf, sourceWorkshop;
    // Test seam for interruption at durable transaction boundaries; never configured externally.
    interface Boundary {void reached(String phase)throws IOException;}
    Boundary boundary=phase->{};
    static final class Changed extends IOException {Changed(String message){super(message);}}
    public WorkshopStaging(StagingSettings settings,Revisions remote,Downloader downloader,Logger log)throws IOException {
        this.settings=settings;this.remote=remote;this.downloader=downloader;this.log=log;
        if(!settings.enabled)throw new IOException("Staging is disabled");
        StageFiles.noLinks(settings.root);StageFiles.noLinks(settings.workshop);
        acf=settings.workshop.resolve("appworkshop_108600.acf");WorkshopAcf.read(acf);
        sourceWorkshop=settings.steamcmd.getParent().resolve("steamapps/workshop");
        Files.createDirectories(settings.root);
        Path lock=settings.workshop.resolve(".workshopsentinel.lock");StageFiles.noLinks(lock);
        channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock obtained;
        try {obtained=channel.tryLock();if(obtained==null)throw new IOException("Workshop staging is already in use; server must be stopped before applying");}
        catch(IOException | OverlappingFileLockException e){channel.close();throw new IOException("Workshop staging lock unavailable",e);}
        lease=obtained;
    }
    static WorkshopStaging open(Config c,Logger log)throws IOException {
        SteamWorkshopUpdateProvider metadata=new SteamWorkshopUpdateProvider(c.timeoutSeconds,null,log);
        return new WorkshopStaging(c.staging,metadata::revisions,new SteamCmdDownloader(c.staging),log);
    }
    private Map<String,Long> revisions(Set<String> ids)throws Exception {
        Map<String,Long> result=remote.read(ids);
        if(!result.keySet().equals(ids) || result.values().stream().anyMatch(v->v==null || v<=0))throw new IOException("Incomplete staging revision query");
        return result;
    }
    private void ids(Set<String> ids)throws IOException {
        for(String id:ids)if(!Config.ids(id).equals(Set.of(id)))throw new IOException("Invalid staging ID");
    }
    /** Worker I/O only: never touches active content/ACF. Also catches updates missed by a JVM baseline. */
    public Set<String> refresh(Set<String> ids)throws Exception {
        try(DirectoryStream<Path> paths=Files.newDirectoryStream(settings.workshop,".workshopsentinel-txn-*")){
            for(Path tx:paths){Path journal=tx.resolve("journal.properties");StageFiles.noLinks(journal);
                if(Files.exists(journal) && "PREPARED".equals(StageFiles.properties(journal).getProperty("phase")))
                    throw new IOException("Interrupted startup transaction: run pre-start recovery before staging");}
        }
        prepare(ids,false);Map<String,Long> latest=revisions(ids);WorkshopAcf installed=WorkshopAcf.read(acf);
        Set<String> outdated=new LinkedHashSet<>();for(String id:ids)
            if(!installed.items().containsKey(id) || installed.updated(id)<latest.get(id))outdated.add(id);
        return outdated;
    }
    private Map<String,Path> prepare(Set<String> ids,boolean force)throws Exception {
        ids(ids);if(ids.isEmpty())return Map.of();
        Map<String,Long> before=revisions(ids);WorkshopAcf installed=WorkshopAcf.read(acf);
        Set<String> needed=new LinkedHashSet<>();
        Properties pending=new Properties();Path index=settings.root.resolve("pending.properties");
        if(Files.exists(index))try{pending=StageFiles.properties(index);}
        catch(StageFiles.CorruptMetadata corrupt){
            // This index is only a cache hint, never a transaction journal. Rebuild
            // it from Steam; keep the damaged original for operator inspection.
            Files.move(index,settings.root.resolve("corrupt-pending-"+UUID.randomUUID()+".properties"),StandardCopyOption.ATOMIC_MOVE);
            log.warning("Corrupt staging queue index quarantined; fresh Steam validation required");
        }
        for(String id:ids){
            if(force){needed.add(id);continue;}
            if(installed.items().containsKey(id) && installed.updated(id)>=before.get(id))continue;
            String queued=pending.getProperty(id);boolean ready=false;
            if(queued!=null){
                try {
                    Path bundle=bundle(id,queued);Properties manifest=StageFiles.properties(bundle.resolve("receipt.properties"));
                    ready=Long.toString(before.get(id)).equals(manifest.getProperty("steamUpdated"));
                }catch(StageFiles.CorruptMetadata corrupt){log.warning("Corrupt staged receipt for "+id+"; downloading a fresh generation");}
            }
            if(!ready)needed.add(id);
        }
        if(needed.isEmpty())return Map.of();
        downloader.download(needed);
        Map<String,Long> after=revisions(needed);WorkshopAcf downloaded=WorkshopAcf.read(sourceWorkshop.resolve("appworkshop_108600.acf"));
        Map<String,Path> bundles=new LinkedHashMap<>();
        for(String id:needed){
            if(!before.get(id).equals(after.get(id)))throw new Changed("Steam changed during download: "+id);
            downloaded.verified(id,after.get(id));
            String generation=UUID.randomUUID().toString();Path bundle=bundle(id,generation);
            StageFiles.copy(sourceWorkshop.resolve("content/108600").resolve(id),bundle.resolve("content"),settings.maxBytes);
            Properties receipt=new Properties();receipt.setProperty("schema","1");receipt.setProperty("id",id);
            receipt.setProperty("manifest",downloaded.manifest(id));receipt.setProperty("steamUpdated",after.get(id).toString());
            receipt.setProperty("downloadedAt",Instant.now().toString());receipt.setProperty("verifiedAt",Instant.now().toString());
            for(Map.Entry<String,String> file:StageFiles.inventory(bundle.resolve("content"),settings.maxBytes).entrySet())receipt.setProperty("sha256."+file.getKey(),file.getValue());
            StageFiles.properties(bundle.resolve("receipt.properties"),receipt);
            // Preserve only this item's validated native metadata, not the downloader's entire cache.
            StageFiles.atomic(bundle.resolve("steam.acf"),downloaded.encode());
            verify(bundle,id);bundles.put(id,bundle);pending.setProperty(id,generation);
        }
        StageFiles.properties(settings.root.resolve("pending.properties"),pending);
        log.info("Workshop staging prepared: items="+needed.size()+"; active files unchanged");return bundles;
    }
    private Path bundle(String id,String generation)throws IOException {
        if(!generation.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IOException("Invalid staging generation");
        Path result=settings.root.resolve("ready").resolve(id).resolve(generation);StageFiles.noLinks(result);return result;
    }
    Properties verify(Path bundle,String id)throws IOException {
        StageFiles.noLinks(bundle);Properties p=StageFiles.properties(bundle.resolve("receipt.properties"));
        if(!"1".equals(p.getProperty("schema")) || !id.equals(p.getProperty("id")))throw new IOException("Invalid staging receipt");
        Map<String,String> expected=new TreeMap<>();for(String key:p.stringPropertyNames())if(key.startsWith("sha256."))expected.put(key.substring(7),p.getProperty(key));
        if(!expected.equals(StageFiles.inventory(bundle.resolve("content"),settings.maxBytes)))throw new IOException("Corrupt or modified staged item: "+id);
        try {
            WorkshopAcf metadata=WorkshopAcf.read(bundle.resolve("steam.acf"));metadata.verified(id,Long.parseLong(p.getProperty("steamUpdated")));
            if(!metadata.manifest(id).equals(p.getProperty("manifest")))throw new IOException("Manifest identity mismatch");
            Instant.parse(p.getProperty("downloadedAt"));Instant.parse(p.getProperty("verifiedAt"));
        }catch(RuntimeException e){throw new IOException("Invalid staging receipt fields",e);}return p;
    }
    /** Must execute before starting the game JVM; never called by the mod's runtime hook. */
    public void applyAtStartup(Set<String> ids)throws Exception {
        ids(ids);recover();
        for(int attempt=1;attempt<=3;attempt++){
            try {
                // Always ask SteamCMD to validate again, including same-second manifest changes.
                Map<String,Path> bundles=prepare(ids,true);
                if(!ids.isEmpty())apply(bundles,ids);
                return;
            }catch(Changed changed){log.warning(changed.getMessage()+"; startup retry "+attempt+"/3");if(attempt==3)throw changed;}
        }
    }
    private void apply(Map<String,Path> bundles,Set<String> ids)throws Exception {
        Map<String,Long> latest=revisions(ids);WorkshopAcf target=WorkshopAcf.read(acf);String originalHash=StageFiles.hash(acf);
        // Confirm native manifest identities again; timestamp alone is not a version identifier.
        downloader.download(ids);WorkshopAcf fresh=WorkshopAcf.read(sourceWorkshop.resolve("appworkshop_108600.acf"));
        Path transaction=settings.workshop.resolve(".workshopsentinel-txn-"+UUID.randomUUID());StageFiles.noLinks(transaction);Files.createDirectories(transaction);
        Properties journal=new Properties();journal.setProperty("schema","1");journal.setProperty("phase","PREPARED");journal.setProperty("ids",String.join(";",ids));
        Map<String,Map<String,String>> expectedFiles=new LinkedHashMap<>();Set<String> changed=new LinkedHashSet<>();
        for(String id:ids){
            Properties receipt=verify(bundles.get(id),id);fresh.verified(id,latest.get(id));
            long staged=Long.parseLong(receipt.getProperty("steamUpdated"));
            if(staged!=latest.get(id) || !receipt.getProperty("manifest").equals(fresh.manifest(id)))throw new Changed("Prepared revision is no longer latest: "+id);
            if(target.items().containsKey(id) && target.updated(id)>staged)throw new IOException("Refusing to downgrade installed Workshop item: "+id);
            Map<String,String> expected=new TreeMap<>();for(String key:receipt.stringPropertyNames())if(key.startsWith("sha256."))expected.put(key.substring(7),receipt.getProperty(key));
            if(target.items().containsKey(id) && target.manifest(id).equals(receipt.getProperty("manifest"))
                && target.updated(id)==staged && Files.isDirectory(content(id))
                && expected.equals(StageFiles.inventory(content(id),settings.maxBytes)))continue;
            expectedFiles.put(id,expected);changed.add(id);
            StageFiles.copy(bundles.get(id).resolve("content"),transaction.resolve("new").resolve(id),settings.maxBytes);
            journal.setProperty("had."+id,Boolean.toString(Files.exists(content(id))));
            mergeItem(target.items(),fresh.items(),id);
            mergeItem(target.details(),fresh.details(),id);
        }
        // Check once more after potentially lengthy hashing/copying, before any active file moves.
        if(!latest.equals(revisions(ids)))throw new Changed("Steam changed during startup preparation");
        if(!originalHash.equals(StageFiles.hash(acf)))throw new IOException("Live Workshop ACF changed concurrently");
        if(changed.isEmpty()){Files.delete(transaction);log.info("Workshop startup verification: current content retained; no replacement required");return;}
        journal.setProperty("ids",String.join(";",changed));
        long total=0;for(String id:target.items().keySet())total=Math.addExact(total,Long.parseLong(Objects.toString(WorkshopAcf.node(target.items(),id).get("size"),"0")));
        target.app().put("SizeOnDisk",Long.toString(total));
        Files.copy(acf,transaction.resolve("original.acf"));
        StageFiles.force(transaction.resolve("original.acf"));
        journal.setProperty("originalAcfSha256",StageFiles.hash(transaction.resolve("original.acf")));
        StageFiles.atomic(transaction.resolve("next.acf"),target.encode());
        StageFiles.properties(transaction.resolve("journal.properties"),journal);boundary.reached("prepared");
        try {
            Files.createDirectories(transaction.resolve("old"));Files.createDirectories(transaction.resolve("failed"));
            for(String id:changed){
                Path destination=content(id);StageFiles.noLinks(destination);Files.createDirectories(destination.getParent());
                if(Files.exists(destination))Files.move(destination,transaction.resolve("old").resolve(id),StandardCopyOption.ATOMIC_MOVE);
                boundary.reached("old:"+id);
                Files.move(transaction.resolve("new").resolve(id),destination,StandardCopyOption.ATOMIC_MOVE);boundary.reached("new:"+id);
            }
            Files.move(transaction.resolve("next.acf"),acf,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);boundary.reached("acf");
            if(!latest.equals(revisions(ids)))throw new Changed("Steam changed during installation");
            for(String id:changed)if(!expectedFiles.get(id).equals(StageFiles.inventory(content(id),settings.maxBytes)))throw new IOException("Installed file verification failed: "+id);
            journal.setProperty("phase","COMMITTED");journal.setProperty("appliedAt",Instant.now().toString());
            StageFiles.properties(transaction.resolve("journal.properties"),journal);
            log.info("Workshop startup transaction committed: items="+changed.size()+"; backup="+transaction);
        }catch(Exception failure){rollback(transaction,journal);throw failure;}
    }
    private static void mergeItem(Map<String,Object> target,Map<String,Object> fresh,String id)throws IOException {
        Map<String,Object> merged=target.containsKey(id)?new LinkedHashMap<>(WorkshopAcf.node(target,id)):new LinkedHashMap<>();
        // These are known Steam freshness fields, not administrator extensions.
        merged.remove("latest_manifest");merged.remove("latest_timeupdated");
        merged.putAll(WorkshopAcf.node(fresh,id));target.put(id,merged);
    }
    private Path content(String id)throws IOException {Path result=settings.workshop.resolve("content/108600").resolve(id);StageFiles.noLinks(result);return result;}
    /** Interrupted transactions are restored before any new download/apply is attempted. */
    void recover()throws IOException {
        try(DirectoryStream<Path> paths=Files.newDirectoryStream(settings.workshop,".workshopsentinel-txn-*")){
            for(Path transaction:paths){StageFiles.noLinks(transaction);Path journal=transaction.resolve("journal.properties");
                if(!Files.exists(journal))continue; // Abandoned preparation never touched the live tree.
                Properties p=StageFiles.properties(journal);String phase=p.getProperty("phase","");
                if(!"1".equals(p.getProperty("schema")))throw new IOException("Unknown transaction schema");
                if(phase.equals("PREPARED"))rollback(transaction,p);
                else if(!Set.of("COMMITTED","ROLLED_BACK").contains(phase))throw new IOException("Unknown transaction phase");
            }
        }
    }
    private void rollback(Path transaction,Properties journal)throws IOException {
        Set<String> ids=Config.ids(journal.getProperty("ids",""));ids(ids);
        if(ids.isEmpty())throw new IOException("Empty rollback item list");
        StageFiles.noLinks(transaction);StageFiles.noLinks(transaction.resolve("original.acf"));
        if(!StageFiles.hash(transaction.resolve("original.acf")).equals(journal.getProperty("originalAcfSha256")))
            throw new IOException("Rollback ACF backup is corrupt; startup refused");
        Files.createDirectories(transaction.resolve("failed"));
        for(String id:ids){
            Path backup=transaction.resolve("old").resolve(id), destination=content(id);StageFiles.noLinks(backup);
            String had=journal.getProperty("had."+id);if(!Set.of("true","false").contains(had))throw new IOException("Invalid transaction history");
            boolean movedNew=!Files.exists(transaction.resolve("new").resolve(id));
            if(Files.exists(backup) || (had.equals("false") && movedNew)){
                if(Files.exists(destination))Files.move(destination,transaction.resolve("failed").resolve(id),StandardCopyOption.ATOMIC_MOVE);
                if(Files.exists(backup))Files.move(backup,destination,StandardCopyOption.ATOMIC_MOVE);
            }
        }
        StageFiles.atomic(acf,Files.readString(transaction.resolve("original.acf")));
        journal.setProperty("phase","ROLLED_BACK");StageFiles.properties(transaction.resolve("journal.properties"),journal);
        log.warning("Workshop startup transaction rolled back: "+transaction);
    }
    @Override public void close()throws IOException {try{lease.release();}finally{channel.close();}}
}
