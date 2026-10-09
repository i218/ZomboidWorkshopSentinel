package io.workshopsentinel;

import java.util.*;
import java.util.logging.Logger;

/** Explicit network diagnostic, no game APIs, marker, shutdown, subscription, or downloads. */
public final class SteamProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || args[0].isEmpty()) throw new IllegalArgumentException("Supply -PprobeIds=WORKSHOP_ID");
        Set<String> ids = Config.ids(args[0]);
        SteamWorkshopUpdateProvider provider = new SteamWorkshopUpdateProvider(20, null, Logger.getLogger("SteamProbe"));
        Set<String> result = provider.check(ids);
        if (!result.isEmpty()) throw new AssertionError("First session check must establish baseline");
        System.out.println("PASS live Steam transport/XML: " + ids.size() + " PZ item(s); session baseline only");
    }
}
