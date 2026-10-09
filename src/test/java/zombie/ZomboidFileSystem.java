package zombie;
/** Test double only, never included in the mod JAR. */
public final class ZomboidFileSystem {
    public static final ZomboidFileSystem instance = new ZomboidFileSystem();
    public final java.util.List<String> loaded = new java.util.ArrayList<>();
    public java.util.List<String> getModIDs() { return loaded; }
}
