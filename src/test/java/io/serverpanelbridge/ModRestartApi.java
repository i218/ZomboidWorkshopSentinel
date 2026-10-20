package io.serverpanelbridge;
/** Test double only; never shipped in the Sentinel JAR. */
public final class ModRestartApi {
    public static int calls,version=1;
    public static String answer="QUEUED",lastRequester,lastId;
    public static int apiVersion(){return version;}
    public static String requestRestart(String requester,String id){calls++;lastRequester=requester;lastId=id;return answer;}
}
