package org.example.websocket;

import org.example.filesystem.FileSystemService;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket Handler: RFC 6455 Protocol Implementation
 * Handles frame encoding/decoding and command processing
 * Commands: ls, pwd, cd, mkdir, rm, undo, goto, suggest
 */
public class WebSocketHandler {
    /**
     * Handle WebSocket connection
     * Perform RFC 6455 handshake, then read frames and process commands
     */
    public static void handle(Socket socket, InputStream in, OutputStream out, String request, FileSystemService fs) throws IOException {
        // Perform WebSocket handshake
        String handshake = WebSocketUtils.createHandshakeResponse(request);
        if (handshake == null) { socket.close(); return; }
        out.write(handshake.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Read and process incoming frames until connection closes
        while (true) {
            String msg = readTextFrame(in);
            if (msg == null) break; // Connection closed
            String response = processCommand(msg, fs);
            sendText(out, response);
        }
    }

    /**
     * Process incoming commands and return response
     * Commands: ls, pwd, cd, mkdir, rm, undo, goto, suggest
     */
    private static String processCommand(String command, FileSystemService fs) {
        try {
            String[] parts = command.trim().split(" ");
            String cmd = parts[0].toLowerCase();
            switch (cmd) {
                case "ls": return "LIST: " + fs.ls();
                case "pwd": return "PATH: " + fs.pwd();
                case "cd": return fs.cd(parts[1]) ? "RES: OK" : "ERR: Directory not found";
                case "mkdir": return fs.mkdir(parts[1]) ? "RES: OK" : "ERR: Creation failed";
                case "rm": return fs.rm(parts[1]) ? "RES: OK" : "ERR: Deletion failed";
                case "undo": return fs.undo() ? "RES: OK" : "ERR: No history";
                case "goto": 
                    // Jump to absolute path (e.g., /folder1/subfolder)
                    String path = parts.length > 1 ? parts[1] : "/";
                    return fs.gotoPath(path) ? "RES: OK" : "ERR: Invalid path";
                case "suggest":
                    // Autocomplete folder names
                    if (parts.length < 2) return "SUGGEST: ";
                    String input = parts[1].toLowerCase();
                    
                    // Extract only last part after / for matching
                    String query = input;
                    if (input.contains("/")) {
                        query = input.substring(input.lastIndexOf("/") + 1);
                    }

                    File[] folderFiles = fs.getCurrentDir().listFiles();
                    StringBuilder suggestions = new StringBuilder("SUGGEST: ");
                    if (folderFiles != null) {
                        for (File f : folderFiles) {
                            if (f.isDirectory() && f.getName().toLowerCase().startsWith(query)) {
                                suggestions.append(f.getName()).append("|");
                            }
                        }
                    }
                    return suggestions.toString();
                default: return "ERR: Unknown command";
            }
        } catch (Exception e) { return "ERR: " + e.getMessage(); }
    }

    /**
     * Read and decode WebSocket text frame (RFC 6455)
     * Frame format: [FIN+opcode][MASK+length][mask_key(4bytes)][payload]
     * Payload is XOR-masked with repeating 4-byte key
     * Returns null on CLOSE frame or connection end
     */
    private static String readTextFrame(InputStream in) throws IOException {
        // Read first byte: FIN bit + reserved bits + opcode
        int b1 = in.read(); 
        if (b1 == -1) return null;
        
        // Extract opcode (0x8 = CLOSE frame)
        int opcode = b1 & 0x0F; 
        if (opcode == 0x8) return null;
        
        // Read second byte: MASK bit + payload length
        int b2 = in.read();
        boolean masked = (b2 & 0x80) != 0;
        long len = b2 & 0x7F;
        
        // Handle extended payload length (RFC 6455)
        if (len == 126) {
            // 2-byte extended length
            len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (len == 127) {
            // 8-byte extended length (for very large payloads)
            for (int i = 0; i < 8; i++) in.read();
        }
        
        // Client messages MUST be masked (RFC 6455 requirement)
        if (!masked) return null;
        
        // Read masking key and apply XOR to decode payload
        byte[] mask = in.readNBytes(4);
        byte[] payload = in.readNBytes((int) len);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i % 4]; // XOR with repeating 4-byte key
        }
        
        return new String(payload, StandardCharsets.UTF_8);
    }

    /**
     * Encode and send WebSocket text frame (RFC 6455)
     * Frame format: [0x81 = FIN+Text][length][payload]
     * Server frames are NOT masked (only client frames are masked)
     */
    private static void sendText(OutputStream out, String msg) throws IOException {
        // Encode text frame 
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        out.write(0x81);
        
        // Write payload length (no masking for server)
        if (data.length <= 125) {
            // Short payload: write length directly
            out.write(data.length);
        } else {
            // Long payload: use extended length format
            out.write(126); // Extended 2-byte length
            out.write((data.length >> 8) & 0xFF); // High byte
            out.write(data.length & 0xFF);         // Low byte
        }
        
        // Write payload
        out.write(data);
        out.flush();
    }
}