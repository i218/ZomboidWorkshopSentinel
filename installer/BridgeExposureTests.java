import java.lang.reflect.Method;

/** Real ZB/Kahlua exposure test; never starts a server or changes approval state. */
public final class BridgeExposureTests {
    public static final class SameNameBridge { public static boolean ping() { return true; } }
    public static void main(String[] args) throws Exception {
        Class.forName("zombie.network.GameServer").getField("server").setBoolean(null, false);
        Class<?> platformType = Class.forName("se.krka.kahlua.vm.Platform");
        Class<?> tableType = Class.forName("se.krka.kahlua.vm.KahluaTable");
        Class<?> platformClass = Class.forName("se.krka.kahlua.j2se.J2SEPlatform");
        Object platform = platformClass.getConstructor().newInstance();
        Object env = platformClass.getMethod("newTable").invoke(platform);
        tableType.getMethod("rawset", Object.class, Object.class).invoke(env, "_G", env);
        Class.forName("se.krka.kahlua.stdlib.BaseLib").getMethod("register", tableType).invoke(null, env);
        Class<?> converterType = Class.forName("se.krka.kahlua.converter.KahluaConverterManager");
        Class<?> luaManager = Class.forName("zombie.Lua.LuaManager");
        luaManager.getField("env").set(null, env);
        luaManager.getField("exposer").set(null, Class.forName("zombie.Lua.LuaManager$Exposer")
            .getConstructor(converterType, platformType, tableType)
            .newInstance(converterType.getConstructor().newInstance(), platform, env));
        Class<?> exposer = Class.forName("me.zed_0xff.zombie_buddy.Exposer");
        exposer.getMethod("exposeClass", Class.class, String.class).invoke(null, SameNameBridge.class, "SameNameBridge");
        Method rawget = tableType.getMethod("rawget", Object.class);
        if (rawget.invoke(env, "SameNameBridge") != null) throw new AssertionError("Same-name alias reproduction changed");
        Class.forName("io.workshopsentinel.Main").getMethod("main", String[].class).invoke(null, (Object)new String[0]);
        if (rawget.invoke(env, "WorkshopSentinelBridge") == null) throw new AssertionError("Bridge global is missing");
        Class<?> threadType = Class.forName("se.krka.kahlua.vm.KahluaThread");
        Object thread = threadType.getConstructor(platformType, tableType).newInstance(platform, env);
        threadType.getField("debugOwnerThread").set(thread, Thread.currentThread());
        Method compile = Class.forName("se.krka.kahlua.luaj.compiler.LuaCompiler")
            .getMethod("loadstring", String.class, String.class, tableType);
        Method call = threadType.getMethod("call", Object.class, Object[].class);
        Object result = call.invoke(thread, compile.invoke(null, "return type(WorkshopSentinelBridge.onTick)", "bridge-test", env), new Object[0]);
        if (!"function".equals(result)) throw new AssertionError("Lua cannot access bridge method: " + result);
        call.invoke(thread, compile.invoke(null, "WorkshopSentinelBridge.onTick()", "bridge-call", env), new Object[0]);
        System.out.println("PASS real ZombieBuddy exposure: old alias loses global; fixed global and Lua onTick invocation work (server guard false)");
    }
}
