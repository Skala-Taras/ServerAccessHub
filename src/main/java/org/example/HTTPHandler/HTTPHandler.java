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
 * HTTP Handler.
 * Serves static files and handles file uploads/downloads.
 * All file operations are sandboxed to cloudStorage directory.
 */
public class HTTPHandler {
    
    private static final String WEB_ROOT = "web";
    private static final File STORAGE_ROOT = new File("cloudStorage");

    private static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024;  // 2GB
    private static final long ZIP_THRESHOLD_BYTES = 3L * 1024 * 1024 * 1024; // 3GB
    private static final int IO_BUFFER_SIZE = 128 * 1024; // 128KB

    /** Route HTTP request to appropriate handler based on method and path. */
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

    /** Serve a static file from the web directory. */
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

    /** Handle file download from cloudStorage. */
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

    /** Handle folder download as ZIP archive. */
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

    /** Handle single file or folder ZIP download. */
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

    /** Calculate total size of a folder recursively. */
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

    /** Create ZIP archive from folder. */
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

    /** Recursively add folder contents to ZIP stream. */
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

    /** Resolve storage path, allowing root folder. Used for folder downloads. */
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

    /** Handle file upload. Streams to .part temp file, then renames on success. */
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

    /** Parse Content-Length header value. Returns -1 if missing or invalid. */
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

    /** Resolve and validate path within cloudStorage sandbox. Prevents directory traversal. */
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

    /** Parse query string into key-value map. */
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

    /** Stream exact number of bytes from input to output. */
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

    /** Write HTTP response headers. */
    private static void writeHeaders(OutputStream out, int code, String reason, String contentType, long contentLength, String extraHeader) throws IOException {
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(("Connection: close\r\n").getBytes(StandardCharsets.ISO_8859_1));
        if (contentType != null) out.write(("Content-Type: " + contentType + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(("Content-Length: " + contentLength + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        if (extraHeader != null) out.write((extraHeader + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(("\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** Send plain text HTTP response. */
    private static void sendText(OutputStream out, int code, String reason, String text) throws IOException {
        byte[] body = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        writeHeaders(out, code, reason, "text/plain; charset=UTF-8", body.length, null);
        out.write(body);
        out.flush();
    }
}