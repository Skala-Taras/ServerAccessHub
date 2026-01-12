package org.example.HTTPHandler;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * HTTP Handler - Static File Serving, Uploads, and Downloads
 * 
 * <p>Handles all HTTP requests (non-WebSocket) including serving static files
 * from the web directory and managing file operations in cloudStorage.</p>
 * 
 * <h2>Supported Endpoints:</h2>
 * <table border="1">
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/</td><td>Serve index.html</td></tr>
 *   <tr><td>GET</td><td>/[file]</td><td>Serve static file from /web</td></tr>
 *   <tr><td>GET</td><td>/download?name=path</td><td>Download file from cloudStorage</td></tr>
 *   <tr><td>PUT</td><td>/upload?path=path</td><td>Upload file to cloudStorage</td></tr>
 * </table>
 * 
 * <h2>Security Features:</h2>
 * <ul>
 *   <li>Path traversal protection using canonical path validation</li>
 *   <li>Sandbox enforcement - all operations restricted to cloudStorage</li>
 *   <li>Explicit ".." rejection in all path operations</li>
 *   <li>URL decoding with validation</li>
 *   <li>Content-Length required for uploads (prevents DoS)</li>
 * </ul>
 * 
 * <h2>Upload Flow:</h2>
 * <ol>
 *   <li>Validate Content-Length header (required, max 2GB)</li>
 *   <li>Resolve and validate target path</li>
 *   <li>Create parent directories if needed</li>
 *   <li>Stream to .part temp file</li>
 *   <li>Rename to final file on success</li>
 * </ol>
 * 
 * @author ServerAccessHub Team
 * @version 2.0.0
 * @see org.example.Server
 */
public class HTTPHandler {
    
    /** Directory containing static web files (HTML, CSS, JS) */
    private static final String WEB_ROOT = "web";
    
    /** Root directory for file storage (sandboxed) */
    private static final File STORAGE_ROOT = new File("cloudStorage");

    /** Maximum upload size: 2 GiB */
    private static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024;
    
    /** Threshold for ZIP compression: 3 GiB */
    private static final long ZIP_THRESHOLD_BYTES = 3L * 1024 * 1024 * 1024;
    
    /** Buffer size for streaming I/O operations */
    private static final int IO_BUFFER_SIZE = 128 * 1024; // 128 KB

    /**
     * Main HTTP request router.
     * 
     * <p>Routes incoming HTTP requests to appropriate handler based on method and path.
     * This method is called by Server after parsing HTTP headers.</p>
     * 
     * <h3>Why This Signature:</h3>
     * <p>We receive pre-parsed components because:</p>
     * <ul>
     *   <li>Request body must be streamed (not buffered) for large uploads</li>
     *   <li>Headers are already parsed with lowercase keys</li>
     *   <li>Method and path extracted from request line</li>
     * </ul>
     * 
     * @param bodyStream Input stream positioned at request body start
     * @param out Output stream for sending response
     * @param method HTTP method (GET, PUT, etc.)
     * @param rawPath Request path with query string (e.g., "/download?name=file.txt")
     * @param headers Map of headers with lowercase keys
     * @throws IOException if I/O operation fails
     */
    public static void handleHttpRequest(InputStream bodyStream, OutputStream out, String method, String rawPath, Map<String, String> headers) throws IOException {
        if (method == null || rawPath == null) {
            sendText(out, 400, "Bad Request", "Missing method/path");
            return;
        }

        // Split path and query string
        String pathOnly = rawPath;
        String query = "";
        int q = rawPath.indexOf('?');
        if (q >= 0) {
            pathOnly = rawPath.substring(0, q);
            query = rawPath.substring(q + 1);
        }

        // Route GET requests
        if ("GET".equals(method)) {
            if ("/".equals(pathOnly)) {
                serveStaticFile(out, "index.html");
                return;
            }
            if ("/terminal".equals(pathOnly)) {
                serveStaticFile(out, "terminal.html");
                return;
            }
            if ("/download".equals(pathOnly)) {
                handleDownload(out, query);
                return;
            }
            if ("/downloadFolder".equals(pathOnly)) {
                handleFolderDownload(out, query);
                return;
            }
            if ("/downloadZip".equals(pathOnly)) {
                handleZipDownload(out, query);
                return;
            }

            // Serve static assets from /web directory
            String fileName = pathOnly.startsWith("/") ? pathOnly.substring(1) : pathOnly;
            serveStaticFile(out, fileName);
            return;
        }

        // Route PUT requests
        if ("PUT".equals(method)) {
            if ("/upload".equals(pathOnly)) {
                handleUploadPut(bodyStream, out, query, headers);
                return;
            }
            sendText(out, 404, "Not Found", "Unknown PUT route");
            return;
        }

        sendText(out, 405, "Method Not Allowed", "Unsupported method");
    }

    /**
     * Serve a static file from the web directory.
     * 
     * <p>Uses streaming to avoid loading large files into memory.
     * Determines Content-Type based on file extension.</p>
     * 
     * @param out Output stream for response
     * @param name File name relative to WEB_ROOT (e.g., "script.js")
     * @throws IOException if file cannot be read
     */
    private static void serveStaticFile(OutputStream out, String name) throws IOException {
        // Validate filename
        if (name == null || name.isEmpty() || name.contains("..") || name.contains("\\") || name.contains("\u0000")) {
            sendText(out, 400, "Bad Request", "Invalid file name");
            return;
        }

        // Determine MIME type from extension
        String type = "application/octet-stream";
        if (name.endsWith(".js")) type = "text/javascript";
        else if (name.endsWith(".css")) type = "text/css";
        else if (name.endsWith(".html")) type = "text/html";

        File file = new File(WEB_ROOT, name);
        if (!file.exists() || file.isDirectory()) {
            sendText(out, 404, "Not Found", "Not found");
            return;
        }

        // Stream file to response
        long len = file.length();
        writeHeaders(out, 200, "OK", type, len, null);
        try (InputStream fin = new BufferedInputStream(new FileInputStream(file), IO_BUFFER_SIZE)) {
            pipe(fin, out, len);
        }
    }

    /**
     * Handle GET /download?name=relative/path endpoint.
     * 
     * <p>Streams file from cloudStorage with Content-Disposition header
     * for browser download prompt. Uses canonical path validation to prevent traversal.</p>
     * 
     * @param out Output stream for response
     * @param query Query string containing "name" parameter
     * @throws IOException if file cannot be read
     */
    private static void handleDownload(OutputStream out, String query) throws IOException {
        Map<String, String> params = parseQuery(query);
        String nameEnc = params.get("name");
        if (nameEnc == null) {
            sendText(out, 400, "Bad Request", "Missing name parameter");
            return;
        }

        // Resolve path with security validation
        File file;
        try {
            file = resolveStoragePath(nameEnc);
        } catch (SecurityException se) {
            sendText(out, 403, "Forbidden", "Invalid path");
            return;
        }

        if (!file.exists() || !file.isFile()) {
            sendText(out, 404, "Not Found", "File not found");
            return;
        }

        // Check if inline preview requested (for PDF, images, etc.)
        boolean inline = "true".equalsIgnoreCase(params.get("inline"));
        
        // Use proper content type for inline, octet-stream for download
        String contentType = inline ? getContentType(file.getName()) : "application/octet-stream";
        String disposition = inline ? "inline" : "attachment";
        
        long len = file.length();
        String extraHeader = "Content-Disposition: " + disposition + "; filename=\"" + file.getName() + "\"";
        writeHeaders(out, 200, "OK", contentType, len, extraHeader);
        try (InputStream fin = new BufferedInputStream(new FileInputStream(file), IO_BUFFER_SIZE)) {
            pipe(fin, out, len);
        }
    }

    /**
     * Handle GET /downloadFolder?name=relative/path endpoint.
     * 
     * <p>For folders under 3GB: streams as uncompressed ZIP (fast).
     * For folders 3GB+: streams as compressed ZIP (saves bandwidth).</p>
     * 
     * @param out Output stream for response
     * @param query Query string containing "name" parameter (folder path)
     * @throws IOException if folder cannot be read or ZIP creation fails
     */
    private static void handleFolderDownload(OutputStream out, String query) throws IOException {
        Map<String, String> params = parseQuery(query);
        String nameEnc = params.get("name");
        if (nameEnc == null) {
            sendText(out, 400, "Bad Request", "Missing name parameter");
            return;
        }

        // Resolve path with security validation
        File folder;
        try {
            folder = resolveStoragePathAllowRoot(nameEnc);
        } catch (SecurityException se) {
            sendText(out, 403, "Forbidden", "Invalid path");
            return;
        }

        if (!folder.exists() || !folder.isDirectory()) {
            sendText(out, 404, "Not Found", "Folder not found");
            return;
        }

        // Calculate folder size to decide compression level
        long folderSize = calculateFolderSize(folder);
        boolean useCompression = folderSize >= ZIP_THRESHOLD_BYTES;

        // Create temp ZIP file
        File tempZip = File.createTempFile("folder_download_", ".zip");
        try {
            // Create ZIP archive of the folder (compressed only for large folders)
            createZipFromFolder(folder, tempZip, useCompression);
            
            // Stream the ZIP file to client
            long len = tempZip.length();
            String zipName = folder.getName() + ".zip";
            String extraHeader = "Content-Disposition: attachment; filename=\"" + zipName + "\"";
            writeHeaders(out, 200, "OK", "application/zip", len, extraHeader);
            
            try (InputStream fin = new BufferedInputStream(new FileInputStream(tempZip), IO_BUFFER_SIZE)) {
                pipe(fin, out, len);
            }
        } finally {
            // Clean up temp file
            tempZip.delete();
        }
    }

    /**
     * Handle GET /downloadZip?name=path&type=file|folder endpoint.
     * 
     * <p>Creates a ZIP archive of a single file or folder and streams it.
     * For files: wraps the single file in a ZIP archive.
     * For folders: creates ZIP with all contents (same as downloadFolder).</p>
     * 
     * @param out Output stream for response
     * @param query Query string containing "name" and "type" parameters
     * @throws IOException if file cannot be read or ZIP creation fails
     */
    private static void handleZipDownload(OutputStream out, String query) throws IOException {
        Map<String, String> params = parseQuery(query);
        String nameEnc = params.get("name");
        String type = params.get("type");
        
        if (nameEnc == null) {
            sendText(out, 400, "Bad Request", "Missing name parameter");
            return;
        }

        // Resolve path with security validation
        File target;
        try {
            target = resolveStoragePathAllowRoot(nameEnc);
        } catch (SecurityException se) {
            sendText(out, 403, "Forbidden", "Invalid path");
            return;
        }

        if (!target.exists()) {
            sendText(out, 404, "Not Found", "File or folder not found");
            return;
        }

        // Create temp ZIP file
        File tempZip = File.createTempFile("zip_download_", ".zip");
        try {
            String baseName = target.getName();
            
            // Determine compression based on size
            long totalSize = target.isDirectory() ? calculateFolderSize(target) : target.length();
            boolean useCompression = totalSize >= ZIP_THRESHOLD_BYTES;
            
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tempZip), IO_BUFFER_SIZE))) {
                if (!useCompression) {
                    zos.setLevel(0);
                }
                
                if (target.isDirectory()) {
                    // Add folder contents to ZIP
                    addFolderToZip(target, baseName, zos);
                } else {
                    // Add single file to ZIP
                    zos.putNextEntry(new ZipEntry(baseName));
                    try (InputStream fis = new BufferedInputStream(new FileInputStream(target), IO_BUFFER_SIZE)) {
                        byte[] buffer = new byte[IO_BUFFER_SIZE];
                        int len;
                        while ((len = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                    }
                    zos.closeEntry();
                }
            }
            
            // Stream the ZIP file to client
            long len = tempZip.length();
            String zipName = baseName + ".zip";
            String extraHeader = "Content-Disposition: attachment; filename=\"" + zipName + "\"";
            writeHeaders(out, 200, "OK", "application/zip", len, extraHeader);
            
            try (InputStream fin = new BufferedInputStream(new FileInputStream(tempZip), IO_BUFFER_SIZE)) {
                pipe(fin, out, len);
            }
        } finally {
            // Clean up temp file
            tempZip.delete();
        }
    }

    /**
     * Calculate the total size of a folder recursively.
     */
    private static long calculateFolderSize(File folder) {
        long size = 0;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    size += f.length();
                } else if (f.isDirectory()) {
                    size += calculateFolderSize(f);
                }
            }
        }
        return size;
    }

    /**
     * Create a ZIP archive from a folder.
     * 
     * @param folder Source folder to compress
     * @param zipFile Destination ZIP file
     * @param compress If true, use DEFLATE compression; if false, use STORED (faster)
     * @throws IOException if compression fails
     */
    private static void createZipFromFolder(File folder, File zipFile, boolean compress) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile), IO_BUFFER_SIZE))) {
            // Set compression level: 0 = no compression (fast), default = compressed
            if (!compress) {
                zos.setLevel(0);
            }
            String basePath = folder.getName();
            addFolderToZip(folder, basePath, zos);
        }
    }

    /**
     * Recursively add a folder and its contents to a ZIP output stream.
     * 
     * @param folder Current folder to add
     * @param basePath Path prefix for ZIP entries
     * @param zos ZIP output stream
     * @throws IOException if file cannot be read
     */
    private static void addFolderToZip(File folder, String basePath, ZipOutputStream zos) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            String entryPath = basePath + "/" + file.getName();
            
            if (file.isDirectory()) {
                // Add directory entry (must end with /)
                zos.putNextEntry(new ZipEntry(entryPath + "/"));
                zos.closeEntry();
                // Recursively add contents
                addFolderToZip(file, entryPath, zos);
            } else {
                // Add file entry
                zos.putNextEntry(new ZipEntry(entryPath));
                try (InputStream fis = new BufferedInputStream(new FileInputStream(file), IO_BUFFER_SIZE)) {
                    byte[] buffer = new byte[IO_BUFFER_SIZE];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    /**
     * Resolve a storage path, allowing root folder itself (for folder downloads).
     * Similar to resolveStoragePath but allows downloading the entire cloudStorage.
     * 
     * @param urlEncodedPath URL-encoded relative path from request
     * @return Canonical File object within sandbox
     * @throws IOException if canonical path cannot be resolved
     * @throws SecurityException if path escapes sandbox or contains traversal
     */
    private static File resolveStoragePathAllowRoot(String urlEncodedPath) throws IOException {
        // Decode URL encoding
        String decoded = URLDecoder.decode(urlEncodedPath, StandardCharsets.UTF_8);
        decoded = decoded.replace('\\', '/');

        // Treat as relative to STORAGE_ROOT, strip leading slash
        while (decoded.startsWith("/")) decoded = decoded.substring(1);

        // Quick rejection of dangerous patterns
        if (decoded.contains("\u0000")) throw new SecurityException("Invalid path");
        if (decoded.matches("^[A-Za-z]:.*")) throw new SecurityException("Invalid path");

        // Explicit ".." rejection
        String[] parts = decoded.split("/");
        for (String p : parts) {
            if (p.equals("..")) throw new SecurityException("Traversal");
        }

        // Resolve canonical paths and validate containment
        File root = STORAGE_ROOT.getCanonicalFile();
        
        // Empty path means root folder itself
        if (decoded.isEmpty()) {
            return root;
        }
        
        File target = new File(root, decoded.replace('/', File.separatorChar)).getCanonicalFile();

        String rootPath = root.getAbsolutePath();
        String targetPath = target.getAbsolutePath();
        
        // Must be under or equal to root
        if (!targetPath.startsWith(rootPath)) throw new SecurityException("Escapes sandbox");
        
        return target;
    }

    // Get MIME type for inline preview
    private static String getContentType(String fileName) {
        String name = fileName.toLowerCase();
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".ogg")) return "video/ogg";
        if (name.endsWith(".mov")) return "video/quicktime";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".flac")) return "audio/flac";
        if (name.endsWith(".aac")) return "audio/aac";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".txt") || name.endsWith(".log")) return "text/plain; charset=UTF-8";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (name.endsWith(".css")) return "text/css; charset=UTF-8";
        if (name.endsWith(".js")) return "text/javascript; charset=UTF-8";
        if (name.endsWith(".json")) return "application/json; charset=UTF-8";
        if (name.endsWith(".xml")) return "application/xml; charset=UTF-8";
        if (name.endsWith(".md")) return "text/markdown; charset=UTF-8";
        // LaTeX files
        if (name.endsWith(".tex") || name.endsWith(".cls") || name.endsWith(".sty")) return "text/plain; charset=UTF-8";
        // Code files
        if (name.endsWith(".py") || name.endsWith(".java") || name.endsWith(".c") || name.endsWith(".cpp")) return "text/plain; charset=UTF-8";
        if (name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".ini") || name.endsWith(".cfg")) return "text/plain; charset=UTF-8";
        return "application/octet-stream";
    }

    /**
     * Handle PUT /upload?path=relative/path/to/file endpoint.
     * 
     * <p>Receives file data from request body and stores in cloudStorage.
     * Uses .part temp file to prevent partial/corrupted files.</p>
     * 
     * <h3>Requirements:</h3>
     * <ul>
     *   <li>Content-Length header required (HTTP 411 if missing)</li>
     *   <li>Max size: 2GB (HTTP 413 if exceeded)</li>
     *   <li>Path must stay within cloudStorage sandbox</li>
     * </ul>
     * 
     * <h3>Upload Process:</h3>
     * <ol>
     *   <li>Validate headers and path</li>
     *   <li>Create parent directories if needed</li>
     *   <li>Stream to filename.part temp file</li>
     *   <li>On success, rename .part to final name</li>
     *   <li>On error, delete .part file</li>
     * </ol>
     * 
     * @param bodyStream Input stream with file data
     * @param out Output stream for response
     * @param query Query string containing "path" parameter
     * @param headers Request headers for Content-Length
     * @throws IOException if upload fails
     */
    private static void handleUploadPut(InputStream bodyStream, OutputStream out, String query, Map<String, String> headers) throws IOException {
        Map<String, String> params = parseQuery(query);
        String pathEnc = params.get("path");
        if (pathEnc == null) {
            sendText(out, 400, "Bad Request", "Missing path parameter");
            return;
        }

        // Require Content-Length for clean body boundary
        long contentLength = parseContentLength(headers);
        if (contentLength < 0) {
            writeHeaders(out, 411, "Length Required", "text/plain; charset=UTF-8", 0, null);
            out.flush();
            return;
        }
        if (contentLength > MAX_UPLOAD_BYTES) {
            sendText(out, 413, "Payload Too Large", "Upload too large");
            return;
        }

        // Resolve and validate target path
        File target;
        try {
            target = resolveStoragePath(pathEnc);
        } catch (SecurityException se) {
            sendText(out, 403, "Forbidden", "Invalid path");
            return;
        }

        // Create parent directories
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            sendText(out, 500, "Internal Server Error", "Failed to create directories");
            return;
        }

        // Use temp file to prevent partial uploads appearing as complete
        File temp = new File(target.getAbsolutePath() + ".part");
        if (temp.exists() && !temp.delete()) {
            sendText(out, 500, "Internal Server Error", "Cannot overwrite temp file");
            return;
        }

        // Stream body to temp file
        try (OutputStream fout = new BufferedOutputStream(new FileOutputStream(temp), IO_BUFFER_SIZE)) {
            pipe(bodyStream, fout, contentLength);
        } catch (EOFException eof) {
            temp.delete();
            sendText(out, 400, "Bad Request", "Unexpected end of upload body");
            return;
        } catch (IOException io) {
            temp.delete();
            sendText(out, 500, "Internal Server Error", "Upload failed: " + io.getMessage());
            return;
        }

        // Finalize: rename .part to target
        if (target.exists() && !target.delete()) {
            temp.delete();
            sendText(out, 500, "Internal Server Error", "Failed to replace existing file");
            return;
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            sendText(out, 500, "Internal Server Error", "Failed to finalize upload");
            return;
        }

        sendText(out, 200, "OK", "Uploaded");
    }

    /**
     * Parse Content-Length header value.
     * 
     * @param headers Map of headers with lowercase keys
     * @return Content length as long, or -1 if missing/invalid
     */
    private static long parseContentLength(Map<String, String> headers) {
        if (headers == null) return -1;
        String v = headers.get("content-length");
        if (v == null) return -1;
        try {
            long n = Long.parseLong(v.trim());
            return n < 0 ? -1 : n;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Resolve a user-provided path within the cloudStorage sandbox.
     * 
     * <p>Performs extensive validation to prevent directory traversal attacks:</p>
     * <ul>
     *   <li>URL-decodes the path</li>
     *   <li>Rejects null bytes and Windows drive letters</li>
     *   <li>Explicitly rejects ".." in any path segment</li>
     *   <li>Uses canonical paths to resolve symlinks</li>
     *   <li>Verifies final path is under STORAGE_ROOT</li>
     * </ul>
     * 
     * @param urlEncodedPath URL-encoded relative path from request
     * @return Canonical File object within sandbox
     * @throws IOException if canonical path cannot be resolved
     * @throws SecurityException if path escapes sandbox or contains traversal
     */
    private static File resolveStoragePath(String urlEncodedPath) throws IOException {
        // Decode URL encoding
        String decoded = URLDecoder.decode(urlEncodedPath, StandardCharsets.UTF_8);
        decoded = decoded.replace('\\', '/');

        // Treat as relative to STORAGE_ROOT, strip leading slash
        while (decoded.startsWith("/")) decoded = decoded.substring(1);

        // Quick rejection of dangerous patterns
        if (decoded.isEmpty() || decoded.contains("\u0000")) throw new SecurityException("Invalid path");
        if (decoded.matches("^[A-Za-z]:.*")) throw new SecurityException("Invalid path");

        // Explicit ".." rejection
        String[] parts = decoded.split("/");
        for (String p : parts) {
            if (p.equals("..")) throw new SecurityException("Traversal");
        }

        // Resolve canonical paths and validate containment
        File root = STORAGE_ROOT.getCanonicalFile();
        File target = new File(root, decoded.replace('/', File.separatorChar)).getCanonicalFile();

        String rootPath = root.getAbsolutePath();
        String targetPath = target.getAbsolutePath();
        
        // Must be strictly under root (not equal to root)
        if (targetPath.equals(rootPath)) throw new SecurityException("Invalid path");
        if (!targetPath.startsWith(rootPath + File.separator)) throw new SecurityException("Escapes sandbox");
        
        return target;
    }

    /**
     * Parse query string into parameter map.
     * 
     * @param query Query string (e.g., "name=value&foo=bar")
     * @return Map of parameter names to values (not URL-decoded)
     */
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> m = new HashMap<>();
        if (query == null || query.isEmpty()) return m;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx <= 0) continue;
            String k = pair.substring(0, idx);
            String v = pair.substring(idx + 1);
            m.put(k, v);
        }
        return m;
    }

    /**
     * Stream exactly expectedBytes from input to output.
     * 
     * <p>Critical for HTTP body handling - must read exactly Content-Length bytes
     * so connection can be properly reused or closed.</p>
     * 
     * @param in Input stream to read from
     * @param out Output stream to write to
     * @param expectedBytes Exact number of bytes to transfer
     * @throws IOException if read/write fails
     * @throws EOFException if input ends before expected bytes read
     */
    private static void pipe(InputStream in, OutputStream out, long expectedBytes) throws IOException {
        byte[] buf = new byte[IO_BUFFER_SIZE];
        long remaining = expectedBytes;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int n = in.read(buf, 0, toRead);
            if (n == -1) throw new EOFException("Unexpected EOF");
            out.write(buf, 0, n);
            remaining -= n;
        }
        out.flush();
    }

    /**
     * Write HTTP response headers.
     * 
     * @param out Output stream
     * @param code HTTP status code (e.g., 200, 404)
     * @param reason HTTP reason phrase (e.g., "OK", "Not Found")
     * @param contentType MIME type for Content-Type header
     * @param contentLength Body length for Content-Length header
     * @param extraHeader Optional additional header line (null to skip)
     * @throws IOException if write fails
     */
    private static void writeHeaders(OutputStream out, int code, String reason, String contentType, long contentLength, String extraHeader) throws IOException {
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(("Connection: close\r\n").getBytes(StandardCharsets.ISO_8859_1));
        if (contentType != null) out.write(("Content-Type: " + contentType + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(("Content-Length: " + contentLength + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        if (extraHeader != null) out.write((extraHeader + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(("\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /**
     * Send a plain text HTTP response.
     * 
     * @param out Output stream
     * @param code HTTP status code
     * @param reason HTTP reason phrase
     * @param text Response body text
     * @throws IOException if write fails
     */
    private static void sendText(OutputStream out, int code, String reason, String text) throws IOException {
        byte[] body = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        writeHeaders(out, code, reason, "text/plain; charset=UTF-8", body.length, null);
        out.write(body);
        out.flush();
    }
}