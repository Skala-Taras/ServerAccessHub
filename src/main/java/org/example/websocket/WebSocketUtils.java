package org.example.websocket;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebSocketUtils {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static boolean isWebSocketUpgrade(String request) {
        return request.toLowerCase().contains("upgrade: websocket");
    }

    public static String createHandshakeResponse(String request) {
        try {
            Matcher match = Pattern.compile("Sec-WebSocket-Key: (.*)").matcher(request);
            if (!match.find()) return null;
            String clientKey = match.group(1).trim();

            String combined = clientKey + GUID;
            byte[] sha1Hash = MessageDigest.getInstance("SHA-1").digest(combined.getBytes("UTF-8"));

            String acceptKey = Base64.getEncoder().encodeToString(sha1Hash);

            return "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
