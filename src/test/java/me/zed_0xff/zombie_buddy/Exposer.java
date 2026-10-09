package me.zed_0xff.zombie_buddy;
/** Test double only; never packaged in the mod. */
public final class Exposer {
    public static Class<?> exposed;
    public static String alias;
    public static void exposeClass(Class<?> type, String name) { exposed = type; alias = name; }
}
