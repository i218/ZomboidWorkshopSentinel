package io.workshopsentinel;

import java.lang.reflect.Field;

/** Build 42 uses GameServer.server; historical builds may expose bServer instead. */
final class PzServerRuntime {
    private PzServerRuntime() { }
    static boolean isServer(Class<?> type) throws ReflectiveOperationException {
        Field flag;
        try { flag = type.getField("server"); }
        catch (NoSuchFieldException e) { flag = type.getField("bServer"); }
        if (flag.getType() != boolean.class) throw new NoSuchFieldException("Expected public boolean server flag");
        return flag.getBoolean(null);
    }
}
