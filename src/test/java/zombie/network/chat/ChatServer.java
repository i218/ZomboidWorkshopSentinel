package zombie.network.chat;
/** Test double only, never included in the mod JAR. */
public final class ChatServer {
    public static String lastMessage;
    private static final ChatServer INSTANCE = new ChatServer();
    public static ChatServer getInstance() { return INSTANCE; }
    public void sendServerAlertMessageToServerChat(String message) { lastMessage = message; }
}
