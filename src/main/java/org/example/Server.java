package org.example;

import org.example.websocket.WebSocketHandler;
import org.example.websocket.WebSocketUtils;
import org.example.terminal.TerminalHandler;
import org.example.HTTPHandler.HTTPHandler;
import org.example.filesystem.FileSystemService;

import javax.net.ssl.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.example.HTTPHandler.HTTPHandler.*;

/**
 * HTTPS Server with SSL/TLS Support - Main Entry Point
 * 
 * <p>This class implements a secure HTTPS server that handles both standard HTTP requests
 * and WebSocket connections. It uses Java's SSLServerSocket for encrypted communication.</p>
 * 
 * <h2>Architecture Overview:</h2>
 * <ul>
 *   <li><b>HTTP Requests:</b> Routed to {@link HTTPHandler} for static files and file operations</li>
 *   <li><b>WebSocket Upgrades:</b> Routed to {@link WebSocketHandler} for real-time communication</li>
 *   <li><b>File System:</b> Each client gets isolated {@link FileSystemService} instance</li>
 * </ul>
 * 
 * <h2>Security Features:</h2>
 * <ul>
 *   <li>SSL/TLS encryption via Java keystore (keystore.jks)</li>
 *   <li>Per-client file system isolation (thread-safe)</li>
 *   <li>Sandboxed file operations under cloudStorage directory</li>
 * </ul>
 * 
 * <h2>Configuration:</h2>
 * <ul>
 *   <li>Port: 8080 (HTTPS)</li>
 *   <li>Keystore: keystore.jks in project root</li>
 *   <li>Storage Root: cloudStorage/ directory</li>
 * </ul>
 * 
 * @author ServerAccessHub Team
 * @version 2.0.0
 * @see HTTPHandler
 * @see WebSocketHandler
 * @see FileSystemService
 */
public class Server {

    /** Server listening port (HTTPS) */
    private static final int PORT = 8080;
    
    /** Password for SSL keystore file (loaded from .env) */
    // private static final String KEYSTORE_PASS = getenv("KEYSTORE_PASSWORD");
    private static final String KEYSTORE_PASS = Config.getRequired("KEYSTORE_PASSWORD");

    private static final File ROOT_DIR = new File("cloudStorage");

    /**
     * Start the HTTPS server and begin accepting connections.
     * 
     * <p>This method performs the following initialization:</p>
     * <ol>
     *   <li>Creates cloudStorage directory if not exists</li>
     *   <li>Configures SSL/TLS with keystore credentials</li>
     *   <li>Creates SSLServerSocket on configured port</li>
     *   <li>Enters infinite loop accepting client connections</li>
     *   <li>Spawns new thread for each client (concurrent handling)</li>
     * </ol>
     * 
     * <p><b>Note:</b> This method blocks indefinitely. Call from main() or dedicated thread.</p>
     * 
     * @throws RuntimeException if SSL configuration fails or port is unavailable
     */
    public static void start() {
        try {
            // Ensure storage directory exists
            if (!ROOT_DIR.exists()) ROOT_DIR.mkdirs();

            // Configure SSL/TLS context from keystore
            System.setProperty("javax.net.ssl.keyStore", "keystore.jks");
            System.setProperty("javax.net.ssl.keyStorePassword", KEYSTORE_PASS);

            SSLServerSocketFactory ssf =
                    (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            SSLServerSocket serverSocket =
                    (SSLServerSocket) ssf.createServerSocket(PORT);

            System.out.println("Server running on https://localhost:" + PORT);

            // Accept connections indefinitely (blocking)
            while (true) {
                SSLSocket client = (SSLSocket) serverSocket.accept();
                new Thread(() -> handleClient(client)).start();
            }

        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle a single client connection.
     * 
     * <p>Processes incoming HTTP request, determines protocol type (HTTP vs WebSocket),
     * and routes to appropriate handler. Each client gets its own FileSystemService
     * instance to prevent concurrent access conflicts.</p>
     * 
     * <h3>Request Flow:</h3>
     * <ol>
     *   <li>Create isolated FileSystemService for this client</li>
     *   <li>Read and parse HTTP request headers</li>
     *   <li>Check for WebSocket upgrade header</li>
     *   <li>Route to WebSocketHandler or HTTPHandler</li>
     *   <li>Close socket when done (HTTP) or on disconnect (WebSocket)</li>
     * </ol>
     * 
     * @param socket The SSL socket connected to the client
     */
    private static void handleClient(Socket socket) {
        try {
            // Create isolated FileSystemService for this client (thread-safe)
            FileSystemService fs = new FileSystemService(ROOT_DIR);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            HttpRequest http = readHttpRequest(in);
            if (http == null) return;

            String request = http.rawHeaders;
            String firstLine = http.requestLine;
            InputStream bodyStream = http.bodyStream;

            // Route based on protocol type
            if (WebSocketUtils.isWebSocketUpgrade(request)) {
                // Check if this is a terminal WebSocket request
                if (http.path.startsWith("/terminal-ws")) {
                    System.out.println("INFO: Terminal WebSocket upgrade");
                    TerminalHandler.handle(socket, bodyStream, out, request);
                } else {
                    System.out.println("INFO: WebSocket upgrade");
                    WebSocketHandler.handle(socket, bodyStream, out, request, fs);
                }
            } else {
                // Standard HTTP request - serve static files or handle API
                HTTPHandler.handleHttpRequest(bodyStream, out, http.method, http.path, http.headers);
                socket.close();
            }

        } catch (IOException e) {
            System.out.println("INFO: Client disconnected");
        }
    }

    /**
     * Parse HTTP/1.1 request from input stream.
     * 
     * <p>Reads raw bytes until CRLFCRLF (end of headers) is detected.
     * This approach is necessary because BufferedReader would consume
     * body bytes that are needed for file uploads.</p>
     * 
     * <h3>Why Raw Byte Reading:</h3>
     * <p>Using BufferedReader.readLine() would buffer beyond the header boundary,
     * consuming bytes from the request body. For streaming uploads, we need
     * precise control over where headers end and body begins.</p>
     * 
     * <h3>State Machine:</h3>
     * <ul>
     *   <li>State 0: Normal reading</li>
     *   <li>State 1: Seen CR</li>
     *   <li>State 2: Seen CRLF</li>
     *   <li>State 3: Seen CRLFCR</li>
     *   <li>State 4: Seen CRLFCRLF (headers complete)</li>
     * </ul>
     * 
     * @param in The input stream to read from
     * @return Parsed HttpRequest object, or null if connection closed
     * @throws IOException if headers exceed 32KB or are malformed
     */
    private static HttpRequest readHttpRequest(InputStream in) throws IOException {
        final int maxHeaderBytes = 32 * 1024;
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream(2048);

        int state = 0; // CRLFCRLF detection state machine
        while (headerBuf.size() < maxHeaderBytes) {
            int b = in.read();
            if (b == -1) return null;
            headerBuf.write(b);

            // Detect CRLFCRLF sequence
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') { state = 4; break; }
            else state = (b == '\r') ? 1 : 0;
        }
        if (state != 4) throw new IOException("HTTP header too large or malformed");

        byte[] headerBytes = headerBuf.toByteArray();
        String headerText = new String(headerBytes, StandardCharsets.ISO_8859_1);

        // Parse request line: "METHOD /path HTTP/1.1"
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0) return null;
        String requestLine = lines[0];
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) throw new IOException("Malformed request line");

        String method = parts[0].toUpperCase(Locale.ROOT);
        String path = parts[1]; 

        // Parse headers into lowercase key map
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isEmpty()) continue;
            int idx = line.indexOf(':');
            if (idx <= 0) continue;
            String name = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(idx + 1).trim();
            headers.put(name, value);
        }

        // Body stream starts immediately after headers
        InputStream bodyStream = in;

        return new HttpRequest(method, path, requestLine, headerText, headers, bodyStream);
    }

    /**
     * Immutable container for parsed HTTP request data.
     * 
     * <p>Holds all components of an HTTP request after parsing:
     * method, path, headers, and a reference to the body stream.</p>
     */
    private static final class HttpRequest {
        /** HTTP method (GET, PUT, POST, etc.) */
        final String method;
        
        /** Request path including query string (e.g., "/download?name=file.txt") */
        final String path;
        
        /** Original request line (e.g., "GET /path HTTP/1.1") */
        final String requestLine;
        
        /** Raw header text for WebSocket handshake */
        final String rawHeaders;
        
        /** Parsed headers with lowercase keys */
        final Map<String, String> headers;
        
        /** Input stream positioned at start of request body */
        final InputStream bodyStream;

        /**
         * Construct a new HttpRequest.
         * 
         * @param method HTTP method
         * @param path Request path with query string
         * @param requestLine Original first line
         * @param rawHeaders Complete header text
         * @param headers Parsed header map
         * @param bodyStream Stream for reading body
         */
        HttpRequest(String method, String path, String requestLine, String rawHeaders, Map<String, String> headers, InputStream bodyStream) {
            this.method = method;
            this.path = path;
            this.requestLine = requestLine;
            this.rawHeaders = rawHeaders;
            this.headers = headers;
            this.bodyStream = bodyStream;
        }
    }

}
