package io.workshopsentinel;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Dedicated anonymous SteamCMD installation only; never uses the live Steam cache. */
final class SteamCmdDownloader implements WorkshopStaging.Downloader {
    private final StagingSettings settings;
    SteamCmdDownloader(StagingSettings settings){this.settings=settings;}
    @Override public void download(Set<String> ids)throws Exception {
        StageFiles.noLinks(settings.steamcmd);
        if(!Files.isRegularFile(settings.steamcmd))throw new IOException("Install a dedicated SteamCMD in stagingDirectory/steamcmd first");
        List<String> list=new ArrayList<>(ids);
        for(int start=0;start<list.size();start+=100){
            List<String> batch=list.subList(start,Math.min(start+100,list.size()));
            List<String> args=new ArrayList<>(List.of(settings.steamcmd.toString(),"+@ShutdownOnFailedCommand","1","+@NoPromptForPassword","1","+login","anonymous"));
            for(String id:batch){if(!Config.ids(id).equals(Set.of(id)))throw new IOException("Invalid download ID");
                args.addAll(List.of("+workshop_download_item","108600",id,"validate"));}
            args.add("+quit");
            Process process=new ProcessBuilder(args).directory(settings.steamcmd.getParent().toFile()).redirectErrorStream(true).start();
            ByteArrayOutputStream captured=new ByteArrayOutputStream();
            Thread drain=new Thread(()->{try(InputStream input=process.getInputStream()){
                byte[] data=new byte[8192];int n;while((n=input.read(data))!=-1){int keep=Math.min(n,2*1024*1024-captured.size());if(keep>0)captured.write(data,0,keep);}
            }catch(IOException ignored){}},"WorkshopSentinel-SteamCMD-output");drain.setDaemon(true);drain.start();
            try {
                if(!process.waitFor(settings.timeoutSeconds,TimeUnit.SECONDS))throw new IOException("SteamCMD download timed out");
                drain.join(5000);if(drain.isAlive())throw new IOException("SteamCMD output did not close");
                String output=captured.toString(java.nio.charset.StandardCharsets.UTF_8);
                StageFiles.atomic(settings.root.resolve("steamcmd-last-output.txt"),output);
                if(process.exitValue()!=0)throw new IOException("SteamCMD failed: exit="+process.exitValue()+"; see stagingDirectory/steamcmd-last-output.txt; initialize/update SteamCMD before retrying");
                for(String id:batch)if(!output.contains("Success. Downloaded item "+id+" "))
                    throw new IOException("SteamCMD did not confirm download: "+id+"; authentication/availability must be checked");
            }finally{
                process.descendants().forEach(ProcessHandle::destroyForcibly);if(process.isAlive())process.destroyForcibly();
                process.getInputStream().close();
            }
        }
    }
}
