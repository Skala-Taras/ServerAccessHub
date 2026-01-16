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
 * HTTPS Server.
 * Handles HTTP requests and WebSocket connections.
 * Uses SSL/TLS encryption via keystore.jks.
 */
public class Server {

    /** Server port (HTTPS) */
    private static final int PORT = 8080;
    
    /** Password for SSL keystore file (loaded from .env) */
    // private static final String KEYSTORE_PASS = getenv("KEYSTORE_PASSWORD");
    private static final String KEYSTORE_PASS = Config.getRequired("KEYSTORE_PASSWORD");

    private static final File ROOT_DIR = new File("cloudStorage");

    /** Start the HTTPS server. Creates SSL socket and accepts connections. */
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

    /** Handle a single client connection. Routes to HTTP or WebSocket handler. */
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

    /** Parse HTTP request from input stream. Returns headers, method, path, and body stream. */
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

    /** Container for parsed HTTP request data. */
    private static final class HttpRequest {
        final String method;
        final String path;
        final String requestLine;
        final String rawHeaders;
        final Map<String, String> headers;
        final InputStream bodyStream;

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
