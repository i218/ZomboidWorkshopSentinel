package io.workshopsentinel;

import java.nio.file.*;
import java.util.logging.*;

/** A pre-start check only: never launches, kills or restarts a server process. */
public final class StagingBootstrap {
    private StagingBootstrap(){}
    public static void main(String[] args) {
        try {
            if(args.length!=2)throw new IllegalArgumentException("Usage: StagingBootstrap <config.properties> <server.ini>");
            Config c=Config.load(Paths.get(args[0]),Paths.get(args[1]));
            if(!c.staging.enabled || c.dryRun){System.out.println("WorkshopSentinel staging disabled/dry-run; no content changes");return;}
            Logger log=Logger.getLogger("WorkshopSentinel.PreStart");Files.createDirectories(c.directory);
            FileHandler handler=new FileHandler(c.directory.resolve("WorkshopSentinel-prestart-%g.log").toString(),1024*1024,3,true);
            try {
                handler.setFormatter(new SimpleFormatter());handler.setEncoding("UTF-8");log.setUseParentHandlers(false);log.addHandler(handler);
                try(WorkshopStaging staging=WorkshopStaging.open(c,log)){staging.applyAtStartup(c.configuredWorkshopIds());}
                catch(Exception failure){log.log(Level.WARNING,"Workshop pre-start refused; normal startup must stop",failure);throw failure;}
            }finally{handler.close();log.removeHandler(handler);}
            System.out.println("WorkshopSentinel pre-start verified; continue with the normal server startup");
        }catch(Exception e){System.err.println("WorkshopSentinel pre-start refused: "+e.getClass().getSimpleName()+": "+e.getMessage()+". Do not start the server; check the pre-start log.");System.exit(1);}
    }
}
