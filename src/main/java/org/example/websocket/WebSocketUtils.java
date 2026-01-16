package org.example.websocket;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket utilities.
 * Handles WebSocket handshake (RFC 6455).
 */
public class WebSocketUtils {

    /** Magic GUID for WebSocket handshake (RFC 6455). */
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** Check if HTTP request is a WebSocket upgrade request. */
    public static boolean isWebSocketUpgrade(String request) {
        return request.toLowerCase().contains("upgrade: websocket");
    }

    /** Create HTTP 101 response for WebSocket handshake. */
    public static String createHandshakeResponse(String request) {
        try {
            // Extract Sec-WebSocket-Key from request headers
            Matcher match = Pattern.compile("Sec-WebSocket-Key: (.*)").matcher(request);
            if (!match.find()) return null;
            String clientKey = match.group(1).trim();

            // Concatenate with magic GUID and hash
            String combined = clientKey + GUID;
            byte[] sha1Hash = MessageDigest.getInstance("SHA-1").digest(combined.getBytes("UTF-8"));

            // Base64 encode for Sec-WebSocket-Accept value
            String acceptKey = Base64.getEncoder().encodeToString(sha1Hash);

            // Build complete 101 response
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
