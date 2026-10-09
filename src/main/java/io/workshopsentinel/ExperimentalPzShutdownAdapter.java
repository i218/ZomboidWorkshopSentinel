package io.workshopsentinel;

import java.lang.reflect.Method;

/**
 * TODO B42: ServerMap.instance.QueueQuit() is a historical graceful-quit integration point.
 * Current public B42 Javadocs do not confirm it. Opt-in only; exact signature is probed,
 * no private access, console command injection, kill, System.exit, or alternative fallback.
 * A successful call requests shutdown, not a process restart or proof of save completion.
 */
public final class ExperimentalPzShutdownAdapter implements ShutdownAdapter {
    private final Object instance;
    private final Method queueQuit;
    public ExperimentalPzShutdownAdapter() throws ReflectiveOperationException {
        Class<?> map = Class.forName("zombie.network.ServerMap");
        instance = map.getField("instance").get(null);
        if (instance == null) throw new IllegalStateException("ServerMap not ready");
        queueQuit = map.getMethod("QueueQuit");
        if (queueQuit.getReturnType() != void.class) throw new NoSuchMethodException("Expected void QueueQuit()");
    }
    @Override public void requestShutdown() throws Exception { queueQuit.invoke(instance); }
}
