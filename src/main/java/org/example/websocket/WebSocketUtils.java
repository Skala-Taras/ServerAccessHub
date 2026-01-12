package org.example.websocket;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket Utility Functions - RFC 6455 Handshake Support
 * 
 * <p>Provides utility methods for WebSocket protocol implementation,
 * specifically handling the HTTP upgrade handshake process.</p>
 * 
 * <h2>WebSocket Handshake Process (RFC 6455):</h2>
 * <ol>
 *   <li>Client sends HTTP GET with "Upgrade: websocket" header</li>
 *   <li>Client includes Sec-WebSocket-Key (random base64 string)</li>
 *   <li>Server concatenates key with magic GUID</li>
 *   <li>Server computes SHA-1 hash and base64 encodes result</li>
 *   <li>Server responds with "101 Switching Protocols"</li>
 *   <li>Connection upgrades to WebSocket binary protocol</li>
 * </ol>
 * 
 * <h2>Security Note:</h2>
 * <p>The GUID and hashing are NOT for security - they prove the server
 * understands WebSocket protocol and prevent proxy confusion.</p>
 * 
 * @author ServerAccessHub Team
 * @version 2.0.0
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455">RFC 6455 - The WebSocket Protocol</a>
 */
public class WebSocketUtils {

    /**
     * Magic GUID defined by RFC 6455 for WebSocket handshake.
     * This exact string MUST be used - it's part of the protocol specification.
     */
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /**
     * Check if an HTTP request is a WebSocket upgrade request.
     * 
     * <p>WebSocket connections begin as HTTP requests with specific headers:</p>
     * <pre>
     * GET /chat HTTP/1.1
     * Upgrade: websocket
     * Connection: Upgrade
     * Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
     * </pre>
     * 
     * @param request The raw HTTP request headers as a string
     * @return true if request contains "Upgrade: websocket" header
     */
    public static boolean isWebSocketUpgrade(String request) {
        return request.toLowerCase().contains("upgrade: websocket");
    }

    /**
     * Create the HTTP 101 response for WebSocket handshake.
     * 
     * <p>Implements the server-side WebSocket handshake as defined in RFC 6455 Section 4.2.2:</p>
     * <ol>
     *   <li>Extract Sec-WebSocket-Key from client request</li>
     *   <li>Concatenate key with magic GUID</li>
     *   <li>Compute SHA-1 hash of concatenated string</li>
     *   <li>Base64 encode the hash</li>
     *   <li>Return as Sec-WebSocket-Accept header value</li>
     * </ol>
     * 
     * <h3>Example Response:</h3>
     * <pre>
     * HTTP/1.1 101 Switching Protocols
     * Connection: Upgrade
     * Upgrade: websocket
     * Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
     * </pre>
     * 
     * @param request The complete HTTP request headers containing Sec-WebSocket-Key
     * @return The complete HTTP 101 response string, or null if key not found
     */
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
