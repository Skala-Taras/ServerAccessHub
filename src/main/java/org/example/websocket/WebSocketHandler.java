package org.example.websocket;

import org.example.filesystem.FileSystemService;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket Handler - RFC 6455 Frame Processing and Command Routing
 * 
 * <p>Implements the WebSocket protocol for real-time bidirectional communication.
 * Handles frame encoding/decoding and routes commands to FileSystemService.</p>
 * 
 * <h2>Supported Commands:</h2>
 * <table border="1">
 *   <tr><th>Command</th><th>Description</th><th>Response</th></tr>
 *   <tr><td>ls</td><td>List current directory</td><td>LIST: [files]</td></tr>
 *   <tr><td>pwd</td><td>Print working directory</td><td>PATH: /current/path</td></tr>
 *   <tr><td>cd [dir]</td><td>Change directory</td><td>RES: OK or ERR</td></tr>
 *   <tr><td>mkdir [name]</td><td>Create folder</td><td>RES: OK or ERR</td></tr>
 *   <tr><td>rm [name]</td><td>Delete file/folder</td><td>RES: OK or ERR</td></tr>
 *   <tr><td>rename [old]\t[new]</td><td>Rename item</td><td>RES: OK or ERR</td></tr>
 *   <tr><td>goto [path]</td><td>Jump to absolute path</td><td>RES: OK or ERR</td></tr>
 *   <tr><td>suggest [query]</td><td>Autocomplete folders</td><td>SUGGEST: folder1|folder2|</td></tr>
 *   <tr><td>undo</td><td>Undo last cd</td><td>RES: OK or ERR</td></tr>
 * </table>
 * 
 * <h2>WebSocket Frame Format (RFC 6455):</h2>
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 * |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 * | |1|2|3|       |K|             |                               |
 * +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 * |     Extended payload length continued,// Find closeAllContextMenus and change from:

function closeAllContextMenus() {
    let activeCtxMenu = null;  // ❌ REMOVE THIS LINE - causes the redeclaration error
    
    if (activeCtxMenu) {
        activeCtxMenu.classList.remove('active');
        activeCtxMenu = null;
    }
}

// Change to:

function closeAllContextMenus() {
    if (activeCtxMenu) {
        activeCtxMenu.classList.remove('active');
        activeCtxMenu = null;
    }
} if payload len == 127  |
 * + - - - - - - - - - - - - - - - +-------------------------------+
 * |                               |Masking-key, if MASK set to 1  |
 * +-------------------------------+-------------------------------+
 * | Masking-key (continued)       |          Payload Data         |
 * +-------------------------------- - - - - - - - - - - - - - - - +
 * :                     Payload Data continued ...                :
 * +---------------------------------------------------------------+
 * </pre>
 * 
 * @author ServerAccessHub Team
 * @version 2.0.0
 * @see WebSocketUtils
 * @see FileSystemService
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5">RFC 6455 Section 5 - Data Framing</a>
 */
public class WebSocketHandler {

    /**
     * Handle a WebSocket connection from handshake through message exchange.
     * 
     * <p>This method:</p>
     * <ol>
     *   <li>Completes the WebSocket handshake (HTTP 101 response)</li>
     *   <li>Enters loop reading frames from client</li>
     *   <li>Processes each command and sends response</li>
     *   <li>Exits on CLOSE frame or connection error</li>
     * </ol>
     * 
     * @param socket The client socket (for closing on error)
     * @param in Input stream (positioned after HTTP headers)
     * @param out Output stream for responses
     * @param request Original HTTP request (for extracting Sec-WebSocket-Key)
     * @param fs FileSystemService instance for this client
     * @throws IOException if handshake fails or connection drops
     */
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

    /**
     * Stream directory listing in chunks for responsive UI.
     * Sends files in batches of CHUNK_SIZE so UI can start rendering immediately.
     */
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

    /**
     * Process a command string and return the response.
     * 
     * <p>Commands are simple text strings with space-separated arguments.
     * The rename command uses TAB separator to allow spaces in filenames.</p>
     * 
     * @param command The command string from client (e.g., "cd Documents")
     * @param fs FileSystemService for executing file operations
     * @return Response string prefixed with type (LIST:, PATH:, RES:, ERR:, SUGGEST:)
     */
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

    /**
     * Read and decode a WebSocket text frame from input stream.
     * 
     * <p>Implements RFC 6455 frame decoding:</p>
     * <ol>
     *   <li>Read first byte: FIN bit (bit 7) + opcode (bits 0-3)</li>
     *   <li>Read second byte: MASK bit (bit 7) + payload length (bits 0-6)</li>
     *   <li>Handle extended length if needed (126 = 2 bytes, 127 = 8 bytes)</li>
     *   <li>Read 4-byte masking key (required for client frames)</li>
     *   <li>Read and XOR-unmask payload bytes</li>
     *   <li>Return decoded UTF-8 string</li>
     * </ol>
     * 
     * <h3>Opcodes:</h3>
     * <ul>
     *   <li>0x1 = Text frame (what we process)</li>
     *   <li>0x2 = Binary frame</li>
     *   <li>0x8 = Close frame (triggers return null)</li>
     *   <li>0x9 = Ping frame</li>
     *   <li>0xA = Pong frame</li>
     * </ul>
     * 
     * @param in Input stream to read from
     * @return Decoded text message, or null on CLOSE frame or connection end
     * @throws IOException if read fails
     */
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

    /**
     * Encode and send a WebSocket text frame to client.
     * 
     * <p>Server-to-client frames are NOT masked (per RFC 6455).</p>
     * 
     * <h3>Frame Structure:</h3>
     * <ul>
     *   <li>Byte 1: 0x81 = FIN=1, RSV=0, Opcode=1 (text)</li>
     *   <li>Byte 2+: Payload length (1, 3, or 9 bytes)</li>
     *   <li>Remaining: Payload data (UTF-8)</li>
     * </ul>
     * 
     * @param out Output stream to write to
     * @param msg Text message to send
     * @throws IOException if write fails
     */
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