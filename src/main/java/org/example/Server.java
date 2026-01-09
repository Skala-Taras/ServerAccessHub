package org.example;

import org.example.websocket.WebSocketHandler;
import org.example.websocket.WebSocketUtils;
import org.example.HTTPHandler.HTTPHandler;
import org.example.filesystem.FileSystemService;

import javax.net.ssl.*;
import java.io.*;
import java.net.Socket;

import static org.example.HTTPHandler.HTTPHandler.*;

/**
 * HTTPS Server with SSL/TLS
 * Accepts HTTP and WebSocket connections
 * Routes HTTP -> static files, WebSocket -> live connection handler
 * Each client gets isolated FileSystemService for thread safety
 */
public class Server {

    private static final int PORT = 8080;
    private static final String KEYSTORE_PASS = "skala123";
    private static final File ROOT_DIR = new File("cloudStorage");

    /**
     * Start HTTPS server and accept incoming connections
     * Configures SSL/TLS with keystore, listens on port 8080
     * Creates new thread for each client connection
     */
    public static void start() {
        try {
            if (!ROOT_DIR.exists()) ROOT_DIR.mkdirs();

            // Configure SSL/TLS with keystore
            System.setProperty("javax.net.ssl.keyStore", "keystore.jks");
            System.setProperty("javax.net.ssl.keyStorePassword", KEYSTORE_PASS);

            SSLServerSocketFactory ssf =
                    (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
            SSLServerSocket serverSocket =
                    (SSLServerSocket) ssf.createServerSocket(PORT);

            System.out.println("Server running on https://localhost:" + PORT);

            // Accept connections indefinitely
            while (true) {
                SSLSocket client = (SSLSocket) serverSocket.accept();
                new Thread(() -> handleClient(client)).start();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Handle single client connection
     * Parse HTTP headers and route to WebSocket or HTTP handler
     * Each client gets its own FileSystemService (prevents concurrent file conflicts)
     */
    private static void handleClient(Socket socket) {
        try {
            // Create isolated FileSystemService for this client (thread-safe)
            FileSystemService fs = new FileSystemService(ROOT_DIR);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                    
            // Read HTTP request line
            String firstLine = reader.readLine();
            if (firstLine == null) return;

            // Read HTTP headers
            StringBuilder headers = new StringBuilder();
            headers.append(firstLine).append("\r\n");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) break; // End of headers
                headers.append(line).append("\r\n");
            }

            String request = headers.toString();

            // Route to WebSocket or HTTP handler
            if (WebSocketUtils.isWebSocketUpgrade(request)) {
                System.out.println("⚡ WebSocket upgrade");
                WebSocketHandler.handle(socket, in, out, request, fs);
            } else {
                // Serve static files for regular HTTP requests
                HTTPHandler.handleHttpRequest(out, firstLine);
                socket.close();
            }

        } catch (IOException e) {
            System.out.println("Client disconnected");
        }
    }

}
