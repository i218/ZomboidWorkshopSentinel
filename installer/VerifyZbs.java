import java.nio.file.*;
import java.security.*;
import java.lang.reflect.*;
import java.util.*;

/** Tests the installed ZB verifier with an in-memory public key map; no approvals/config writes. */
public final class VerifyZbs {
    public static void main(String[] args) throws Exception {
        Path jar = Paths.get(args[0]), sidecar = Paths.get(args[0] + ".zbs");
        String key = Files.readString(Paths.get(args[1])).trim().replace("JavaModZBS:", "");
        Class<?> idType = Class.forName("me.zed_0xff.zombie_buddy.SteamWorkshop$SteamID64");
        Object id = idType.getConstructor(String.class).newInstance(args[2]);
        Class<?> authorType = Class.forName("me.zed_0xff.zombie_buddy.KnownAuthors$AuthorEntry");
        Object author = authorType.getConstructor().newInstance();
        authorType.getField("id").set(author, id);
        authorType.getField("name").set(author, "i218 (isolated verification fixture)");
        authorType.getField("keys").set(author, List.of(key));
        Map<Object,Object> keys = Map.of(id, author);
        Method verify = Class.forName("me.zed_0xff.zombie_buddy.ZBSVerifier")
            .getMethod("verify", Path.class, Path.class, String.class, idType, Map.class);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar));
        StringBuilder hex = new StringBuilder(); for (byte b : digest) hex.append(String.format("%02x", b & 255));
        String hash = hex.toString();
        Object valid = verify.invoke(null, jar, sidecar, hash, id, keys);
        if (!valid.getClass().getSimpleName().equals("ValidSignature")) throw new AssertionError("ZB rejected signature: " + valid);
        String changed = (hash.charAt(0) == '0' ? "1" : "0") + hash.substring(1);
        Object invalid = verify.invoke(null, jar, sidecar, changed, id, keys);
        if (invalid.getClass().getSimpleName().equals("ValidSignature")) throw new AssertionError("ZB accepted changed JAR hash");
        Object other = idType.getConstructor(String.class).newInstance("76561199013754122");
        Object mismatch = verify.invoke(null, jar, sidecar, hash, other, keys);
        if (mismatch.getClass().getSimpleName().equals("ValidSignature")) throw new AssertionError("ZB accepted uploader mismatch");
        System.out.println("PASS installed ZombieBuddy verifier: valid signature, changed hash rejected, uploader mismatch rejected; trust state untouched");
    }
}
