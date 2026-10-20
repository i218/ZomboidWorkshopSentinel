package io.workshopsentinel;
import java.lang.reflect.*;
import java.util.UUID;
import java.util.logging.Logger;
/** Optional public Bridge API only; never accesses its internals or starts a process. */
public final class ServerPanelBridgeAdapter implements ShutdownAdapter {
    private final String requestId=UUID.randomUUID().toString();
    private final Logger log;
    public ServerPanelBridgeAdapter(Logger log){this.log=log;}
    @Override public void requestShutdown() throws Exception {
        Class<?> api=Class.forName("io.serverpanelbridge.ModRestartApi");
        if(!Integer.valueOf(1).equals(api.getMethod("apiVersion").invoke(null)))throw new IllegalStateException("Unsupported ServerPanelBridge restart API");
        String result=(String)api.getMethod("requestRestart",String.class,String.class).invoke(null,"WorkshopSentinel",requestId);
        if(!"QUEUED".equals(result) && !"ARMED".equals(result))throw new IllegalStateException("ServerPanelBridge rejected restart: "+result);
        log.info("ServerPanelBridge accepted restart request "+requestId+"; final outcome belongs to Bridge logs; startup not confirmed");
    }
}
