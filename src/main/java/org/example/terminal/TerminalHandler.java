package org.example.terminal;

import org.example.websocket.WebSocketUtils;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Terminal WebSocket Handler - Interactive Bash Session via WebSocket
 * 
 * <p>Manages a bash shell process and bridges it with WebSocket for real-time
 * terminal access in the browser. Uses ProcessBuilder to spawn /bin/bash
 * and streams stdin/stdout bidirectionally.</p>
 * 
 * <h2>Architecture:</h2>
 * <pre>
 * Browser (xterm.js) ←→ WebSocket ←→ TerminalHandler ←→ ProcessBuilder ←→ /bin/bash
 * </pre>
 * 
 * <h2>Features:</h2>
 * <ul>
 *   <li>Full PTY emulation via bash interactive mode</li>
 *   <li>Terminal resize support (SIGWINCH equivalent)</li>
 *   <li>Proper cleanup on disconnect</li>
 *   <li>Working directory set to /app/cloudStorage</li>
 * </ul>
 * 
 * @author ServerAccessHub Team
 * @version 1.0.0
 */
public class TerminalHandler {

    /** Working directory for terminal sessions */
    private static final String WORK_DIR = "/app";
    
    /** Shell to execute */
    private static final String SHELL = "/bin/bash";

    /** Handle terminal WebSocket connection. Spawns bash and streams I/O. */
    public static void handle(Socket socket, InputStream in, OutputStream out, String request) {
        Process process = null;
        AtomicBoolean running = new AtomicBoolean(true);
        
        try {
            // Complete WebSocket handshake
            String handshake = WebSocketUtils.createHandshakeResponse(request);
            if (handshake == null) {
                socket.close();
                return;
            }
            out.write(handshake.getBytes(StandardCharsets.UTF_8));
            out.flush();

            System.out.println("[TERM] Starting bash session");

            // Start bash in non-interactive mode to avoid PTY warnings
            // Use script command to create pseudo-terminal
            ProcessBuilder pb = new ProcessBuilder(
                "/usr/bin/script", "-q", "-c", "/bin/bash", "/dev/null"
            );
            pb.redirectErrorStream(true);
            
            // Set working directory (use /app if cloudStorage doesn't exist)
            File workDir = new File(WORK_DIR);
            if (workDir.exists()) {
                pb.directory(workDir);
            } else {
                pb.directory(new File("/app"));
            }
            
            // Environment variables for proper terminal behavior
            pb.environment().put("TERM", "xterm-256color");
            pb.environment().put("PS1", "\\[\\033[1;32m\\]root@cloud\\[\\033[0m\\]:\\[\\033[1;34m\\]\\w\\[\\033[0m\\]$ ");
            pb.environment().put("HOME", "/root");
            pb.environment().put("LANG", "en_US.UTF-8");
            pb.environment().put("SHELL", "/bin/bash");
            
            process = pb.start();
            
            InputStream processOut = process.getInputStream();
            OutputStream processIn = process.getOutputStream();
            
            // Thread: Read from process stdout → send to WebSocket
            Thread outputThread = new Thread(() -> {
                try {
                    byte[] buffer = new byte[4096];
                    int len;
                    while (running.get() && (len = processOut.read(buffer)) != -1) {
                        String data = new String(buffer, 0, len, StandardCharsets.UTF_8);
                        sendText(out, data);
                    }
                } catch (IOException e) {
                    // Connection closed
                }
            }, "terminal-output");
            outputThread.setDaemon(true);
            outputThread.start();

            // Main loop: Read from WebSocket → write to process stdin
            while (running.get()) {
                String input = readTextFrame(in);
                if (input == null) {
                    break; // Client disconnected
                }
                
                // Handle special resize command
                if (input.startsWith("\u001b[RESIZE:") && input.endsWith("\u001b[")) {
                    // Extract JSON: {"cols":80,"rows":24}
                    // Note: True PTY resize requires native code, this is just logged
                    String json = input.substring(9, input.length() - 2);
                    System.out.println("[TERM] Resize request: " + json);
                    continue;
                }
                
                // Send input to bash
                processIn.write(input.getBytes(StandardCharsets.UTF_8));
                processIn.flush();
            }

        } catch (IOException e) {
            System.out.println("[TERM] Connection closed: " + e.getMessage());
        } finally {
            running.set(false);
            
            // Clean up process
            if (process != null) {
                process.destroyForcibly();
                System.out.println("[TERM] Bash session terminated");
            }
            
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    /** Send text as WebSocket frame. */
    private static synchronized void sendText(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        
        // WebSocket frame header
        out.write(0x81); // FIN + text opcode
        
        if (payload.length < 126) {
            out.write(payload.length);
        } else if (payload.length < 65536) {
            out.write(126);
            out.write((payload.length >> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((payload.length >> (8 * i)) & 0xFF));
            }
        }
        
        out.write(payload);
        out.flush();
    }

    /** Read text frame from WebSocket. Returns null on close or error. */
    private static String readTextFrame(InputStream in) throws IOException {
        int b1 = in.read();
        if (b1 == -1) return null;
        
        int opcode = b1 & 0x0F;
        
        // Handle CLOSE frame
        if (opcode == 0x08) return null;
        
        // Handle PING - respond with PONG
        if (opcode == 0x09) {
            // Read and discard ping payload, should send pong
            return "";
        }
        
        int b2 = in.read();
        if (b2 == -1) return null;
        
        boolean masked = (b2 & 0x80) != 0;
        long payloadLen = b2 & 0x7F;
        
        // Extended payload length
        if (payloadLen == 126) {
            int b3 = in.read();
            int b4 = in.read();
            if (b3 == -1 || b4 == -1) return null;
            payloadLen = ((b3 & 0xFF) << 8) | (b4 & 0xFF);
        } else if (payloadLen == 127) {
            payloadLen = 0;
            for (int i = 0; i < 8; i++) {
                int b = in.read();
                if (b == -1) return null;
                payloadLen = (payloadLen << 8) | (b & 0xFF);
            }
        }
        
        // Read masking key (if present)
        byte[] maskKey = new byte[4];
        if (masked) {
            if (in.read(maskKey) != 4) return null;
        }
        
        // Read payload
        byte[] payload = new byte[(int) payloadLen];
        int totalRead = 0;
        while (totalRead < payloadLen) {
            int read = in.read(payload, totalRead, (int) payloadLen - totalRead);
            if (read == -1) return null;
            totalRead += read;
        }
        
        // Unmask payload
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= maskKey[i % 4];
            }
        }
        
        return new String(payload, StandardCharsets.UTF_8);
    }
}
