import java.nio.file.*;
import java.lang.reflect.*;

/** Isolated Lua execution against the installed game's runtime, without starting PZ. */
public final class KahluaClientTests {
    public static void main(String[] args) throws Exception {
        Class<?> platformType = Class.forName("se.krka.kahlua.vm.Platform");
        Class<?> tableType = Class.forName("se.krka.kahlua.vm.KahluaTable");
        Class<?> p = Class.forName("se.krka.kahlua.j2se.J2SEPlatform");
        Object platform = p.getConstructor().newInstance();
        Object env = p.getMethod("newTable").invoke(platform);
        tableType.getMethod("rawset", Object.class, Object.class).invoke(env, "_G", env);
        // Independent Unicode expectations bypass Lua compilation entirely.
        tableType.getMethod("rawset", Object.class, Object.class).invoke(env, "expectedRussianMenu", "\u041e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u044f \u043c\u043e\u0434\u043e\u0432");
        tableType.getMethod("rawset", Object.class, Object.class).invoke(env, "expectedRussianCopy", "\u041a\u043e\u043f\u0438\u0440\u043e\u0432\u0430\u0442\u044c \u0441\u0441\u044b\u043b\u043a\u0443");
        tableType.getMethod("rawset", Object.class, Object.class).invoke(env, "expectedRussianWorkshop", "\u041e\u0442\u043a\u0440\u044b\u0442\u044c Workshop");
        Class.forName("se.krka.kahlua.stdlib.BaseLib").getMethod("register", tableType).invoke(null, env);
        for (String lib : new String[]{"se.krka.kahlua.stdlib.StringLib", "se.krka.kahlua.stdlib.TableLib", "se.krka.kahlua.j2se.MathLib", "se.krka.kahlua.stdlib.CoroutineLib"})
            Class.forName(lib).getMethod("register", platformType, tableType).invoke(null, platform, env);
        Class<?> threadType = Class.forName("se.krka.kahlua.vm.KahluaThread");
        Object thread = threadType.getConstructor(platformType, tableType).newInstance(platform, env);
        threadType.getField("debugOwnerThread").set(thread, Thread.currentThread());
        Method compile = Class.forName("se.krka.kahlua.luaj.compiler.LuaCompiler")
            .getMethod("loadstring", String.class, String.class, tableType);
        Method call = threadType.getMethod("call", Object.class, Object[].class);
        for (String file : args) {
            Object closure = compile.invoke(null, Files.readString(Paths.get(file)), file, env);
            try {
                Object result = call.invoke(thread, closure, new Object[0]);
                tableType.getMethod("rawset", Object.class, Object.class).invoke(env, "lastResult", result);
            }
            catch (InvocationTargetException e) { throw new RuntimeException("Lua failed: " + file, e.getCause()); }
        }
        System.out.println("PASS client Lua tests (game API and UI doubles)");
    }
}
