package org.example.websocket;

import org.example.filesystem.FileSystemService;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket Handler.
 * Processes WebSocket frames and routes file system commands.
 * 
 * Commands: ls, pwd, cd, mkdir, rm, rename, goto, suggest, undo
 */
public class WebSocketHandler {

    /** Handle WebSocket connection. Completes handshake and processes commands. */
    public static void handle(Socket socket, InputStream in, OutputStream out, String request, FileSystemService fs) throws IOException {
        // Complete WebSocket handshake
        String handshake = WebSocketUtils.createHandshakeResponse(request);
        if (handshake == null) { 
            socket.close(); 
            return; 
        }
        out.write(handshake.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Message loop: read frames, process commands, send responses
        while (true) {
            String msg = readTextFrame(in);
            if (msg == null) break; // CLOSE frame or connection error
            
            // Special handling for ls command - stream results in chunks
            if ("ls".equalsIgnoreCase(msg.trim())) {
                streamDirectoryListing(out, fs);
            } else {
                String response = processCommand(msg, fs);
                sendText(out, response);
            }
        }
    }

    /** Stream directory listing in chunks for responsive UI. */
    private static final int CHUNK_SIZE = 30;
    
    private static void streamDirectoryListing(OutputStream out, FileSystemService fs) throws IOException {
        File[] files = fs.getCurrentDir().listFiles();
        
        System.out.println("[WS] streamDirectoryListing called, files count: " + (files != null ? files.length : 0));
        
        if (files == null || files.length == 0) {
            sendText(out, "LIST: (empty directory)");
            return;
        }
        
        StringBuilder chunk = new StringBuilder();
        int count = 0;
        int chunksSent = 0;
        
        for (File f : files) {
            if (f.isDirectory()) {
                chunk.append("[DIR]  ").append(f.getName()).append("\n");
            } else {
                chunk.append("[FILE] ").append(f.getName()).append(" (").append(f.length() / 1024).append(" KB)\n");
            }
            count++;
            
            // Send chunk when we hit the limit
            if (count % CHUNK_SIZE == 0) {
                String chunkData = "LIST_CHUNK: " + chunk.toString().trim();
                System.out.println("[WS] Sending chunk #" + (++chunksSent) + ", size: " + chunkData.length() + " bytes");
                sendText(out, chunkData);
                chunk.setLength(0); // Clear buffer
            }
        }
        
        // Send remaining items
        if (chunk.length() > 0) {
            String chunkData = "LIST_CHUNK: " + chunk.toString().trim();
            System.out.println("[WS] Sending final chunk, size: " + chunkData.length() + " bytes");
            sendText(out, chunkData);
        }
        
        // Signal end of listing
        System.out.println("[WS] Sending LIST_END: " + files.length);
        sendText(out, "LIST_END: " + files.length);
    }

    /** Process a command and return the response. */
    private static String processCommand(String command, FileSystemService fs) {
        try {
            // Split only on first space to preserve spaces in file/folder names
            String[] parts = command.trim().split(" ", 2);
            String cmd = parts[0].toLowerCase();
            String arg = parts.length > 1 ? parts[1] : "";
            
            switch (cmd) {
                case "ls": 
                    return "LIST: " + fs.ls();
                    
                case "pwd": 
                    return "PATH: " + fs.pwd();
                    
                case "cd": 
                    return fs.cd(arg) ? "RES: OK" : "ERR: Directory not found";
                    
                case "mkdir": 
                    return fs.mkdir(arg) ? "RES: OK" : "ERR: Creation failed";
                    
                case "rm": 
                    return fs.rm(arg) ? "RES: OK" : "ERR: Deletion failed";
                    
                case "undo": 
                    return fs.undo() ? "RES: OK" : "ERR: No history";
                    
                case "rename":
                    // Format: rename oldName\tnewName (TAB-separated for spaces in names)
                    String renameArgs = command.substring(7).trim();
                    String[] names = renameArgs.split("\t");
                    if (names.length != 2) return "ERR: Usage: rename oldName<TAB>newName";
                    return fs.rename(names[0].trim(), names[1].trim()) ? "RES: OK" : "ERR: Rename failed";
                    
                case "goto": 
                    // Jump to absolute path (e.g., /folder1/subfolder)
                    String path = arg.isEmpty() ? "/" : arg;
                    return fs.gotoPath(path) ? "RES: OK" : "ERR: Invalid path";
                    
                case "suggest":
                    // Autocomplete folder names matching query prefix
                    if (arg.isEmpty()) return "SUGGEST: ";
                    String input = arg.toLowerCase();
                    
                    // Extract last path component for matching
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
                    
                default: 
                    return "ERR: Unknown command";
            }
        } catch (Exception e) { 
            return "ERR: " + e.getMessage(); 
        }
    }

    /** Read and decode a WebSocket text frame. Returns null on close or error. */
    private static String readTextFrame(InputStream in) throws IOException {
        // Byte 1: FIN (bit 7) + RSV1-3 (bits 4-6) + Opcode (bits 0-3)
        int b1 = in.read(); 
        if (b1 == -1) return null;
        
        int opcode = b1 & 0x0F;
        if (opcode == 0x8) return null; // CLOSE frame
        
        // Byte 2: MASK (bit 7) + Payload length (bits 0-6)
        int b2 = in.read();
        boolean masked = (b2 & 0x80) != 0;
        long len = b2 & 0x7F;
        
        // Extended payload length handling
        if (len == 126) {
            // 2-byte extended length (16-bit unsigned)
            len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (len == 127) {
            // 8-byte extended length (64-bit unsigned, but we skip for simplicity)
            for (int i = 0; i < 8; i++) in.read();
        }
        
        // Client frames MUST be masked (RFC 6455 requirement)
        if (!masked) return null;
        
        // Read 4-byte masking key
        byte[] mask = in.readNBytes(4);
        
        // Read and unmask payload
        byte[] payload = in.readNBytes((int) len);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i % 4]; // XOR with repeating 4-byte mask
        }
        
        return new String(payload, StandardCharsets.UTF_8);
    }

    /** Send text message as WebSocket frame. */
    private static void sendText(OutputStream out, String msg) throws IOException {
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        
        // Byte 1: FIN=1, Opcode=1 (text frame)
        out.write(0x81);
        
        // Payload length (no mask bit for server frames)
        if (data.length <= 125) {
            out.write(data.length);
        } else if (data.length <= 65535) {
            out.write(126); // Extended 2-byte length marker
            out.write((data.length >> 8) & 0xFF); // High byte
            out.write(data.length & 0xFF);         // Low byte
        } else {
            // 8-byte length for very large messages (>64KB)
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((data.length >> (i * 8)) & 0xFF);
            }
        }
        
        // Write payload and flush
        out.write(data);
        out.flush();
    }
}