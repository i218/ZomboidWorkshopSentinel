package io.workshopsentinel;

import java.nio.file.*;
import java.util.*;

/** Explicit opt-in; the public mod never writes Workshop content by default. */
public final class StagingSettings {
    public final boolean enabled;
    public final Path root, steamcmd, workshop;
    public final long timeoutSeconds, maxBytes;
    StagingSettings(Properties p, Path directory) {
        String enabledValue=p.getProperty("stagingEnabled","false").trim();
        if(!Set.of("true","false").contains(enabledValue))throw new IllegalArgumentException("stagingEnabled must be true or false");
        enabled=Boolean.parseBoolean(enabledValue);
        root=resolve(directory,p.getProperty("stagingDirectory","staged-workshop"));
        String command=p.getProperty("stagingSteamCmd","").trim();
        if(command.isEmpty())command=System.getProperty("os.name").startsWith("Windows")?"steamcmd/steamcmd.exe":"steamcmd/steamcmd.sh";
        steamcmd=resolve(root,command);
        String target=p.getProperty("stagingWorkshopDirectory","").trim();
        workshop=target.isEmpty()?null:resolve(directory,target);
        timeoutSeconds=Long.parseLong(p.getProperty("stagingDownloadTimeoutSeconds","1800").trim());
        maxBytes=Long.parseLong(p.getProperty("stagingMaxItemBytes","21474836480").trim());
        if(timeoutSeconds<10 || timeoutSeconds>14400 || maxBytes<1 || maxBytes>1099511627776L)
            throw new IllegalArgumentException("Invalid staging limit");
        if(enabled && (workshop==null || workshop.startsWith(root) || root.startsWith(workshop)
            || !steamcmd.getParent().equals(root.resolve("steamcmd"))))
            throw new IllegalArgumentException("Staging needs a separate Workshop root and a dedicated SteamCMD inside stagingDirectory/steamcmd");
    }
    private static Path resolve(Path base,String value) {
        if(value.trim().isEmpty())throw new IllegalArgumentException("Empty staging path");
        Path path=Paths.get(value.trim());return (path.isAbsolute()?path:base.resolve(path)).toAbsolutePath().normalize();
    }
}
