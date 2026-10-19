import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Native game metadata/content and PNG validation only; never initializes Steam or submits an item. */
public final class WorkshopCheck {
    public static void main(String[] args) throws Exception {
        Path folder=Paths.get(args[0]).toAbsolutePath();
        Class<?> fsType=Class.forName("zombie.ZomboidFileSystem");
        Object fs=fsType.getField("instance").get(null);
        Object base=fsType.getField("base").get(fs);
        base.getClass().getMethod("set",File.class).invoke(base,Paths.get(".").toAbsolutePath().toFile());
        fsType.getMethod("setCacheDir",String.class).invoke(fs,folder.getParent().toString());
        Class<?> itemType=Class.forName("zombie.core.znet.SteamWorkshopItem");
        Object item=itemType.getConstructor(String.class).newInstance(folder.toString());
        if(!Boolean.TRUE.equals(itemType.getMethod("readWorkshopTxt").invoke(item)))throw new AssertionError("Native metadata reader rejected package");
        String error=(String)itemType.getMethod("validateContents").invoke(item);
        if(error!=null)throw new AssertionError("Native content validation: "+error+" "+itemType.getMethod("getExtendedErrorInfo",String.class).invoke(item,error));
        String description=(String)itemType.getMethod("getSubmitDescription").invoke(item);
        for(String id:new String[]{"WorkshopSentinel","WorkshopSentinelClient"}) {
            if(!description.contains(id))throw new AssertionError("Native mod ID missing: "+id);
            Path modFolder=folder.resolve("Contents/mods/"+id);
            Object mod=Class.forName("zombie.gameStates.ChooseGameInfo").getMethod("readModInfo",String.class).invoke(null,modFolder.toString());
            if(mod==null || !id.equals(mod.getClass().getMethod("getId").invoke(mod)))throw new AssertionError("Native mod.info rejected: "+id);
            for(String asset:new String[]{"icon.png","poster.png"}) {
                String resolved=(String)(asset.equals("icon.png")?mod.getClass().getMethod("getIcon").invoke(mod):mod.getClass().getMethod("getPoster",int.class).invoke(mod,0));
                if(!Files.isSameFile(Paths.get(resolved),modFolder.resolve("common/"+asset)))throw new AssertionError("Native asset resolution failed");
                try(InputStream input=new BufferedInputStream(Files.newInputStream(Paths.get(resolved)))) {
                    Class<?> pngType=Class.forName("zombie.core.textures.PNGDecoder");
                    Object png=pngType.getConstructor(InputStream.class,boolean.class).newInstance(input,false);
                    int width=(Integer)pngType.getMethod("getWidth").invoke(png);
                    if(width!=(asset.equals("icon.png")?256:512))throw new AssertionError("Bad image size");
                }
            }
        }
        List<String> allowed=Files.readAllLines(Paths.get("media/WorkshopTags.txt"));
        if(!allowed.containsAll((Collection<?>)itemType.getMethod("getTags").invoke(item)))throw new AssertionError("Unsupported tags");
        System.out.println("PASS installed-game Workshop content/metadata validators, both mod IDs, native icon/poster resolution and PNG decoder; no upload attempted");
    }
}
