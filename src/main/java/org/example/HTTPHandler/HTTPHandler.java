package org.example.HTTPHandler;

import java.io.*;
import java.nio.file.Files;

/**
 * HTTP Handler: Serves static files and handles downloads
 * Supports: HTML, JavaScript, CSS, and binary file downloads
 */
public class HTTPHandler {
    private static final String WEB_ROOT = "web";

    /**
     * Handle HTTP GET requests
     * Routes: / -> homepage, /download -> file download, other -> static assets
     */
    public static void handleHttpRequest(OutputStream out, String firstLine) throws IOException {
        String[] parts = firstLine.split(" ");
        if (parts.length < 2) return;
        String method = parts[0];
        String path = parts[1];

        if (method.equals("GET")) {
            if (path.equals("/")) {
                // Serve homepage
                serveStaticFile(out, "index.html", "text/html");
            } else if (path.startsWith("/download")) {
                // Handle file downloads from cloudStorage
                handleDownload(out, path);
            } else {
                // Serve static assets with appropriate MIME type
                String fileName = path.substring(1);
                String type = "application/octet-stream";
                if (fileName.endsWith(".js")) type = "text/javascript";
                else if (fileName.endsWith(".css")) type = "text/css";
                else if (fileName.endsWith(".html")) type = "text/html";
                serveStaticFile(out, fileName, type);
            }
        }
    }

    /** Load and serve a file from web directory */
    private static void serveStaticFile(OutputStream out, String name, String type) throws IOException {
        File file = new File(WEB_ROOT, name);
        if (file.exists() && !file.isDirectory()) {
            byte[] content = Files.readAllBytes(file.toPath());
            sendResponse(out, "200 OK", type, content, null);
        } else {
            sendResponse(out, "404 Not Found", "text/plain", "Not found".getBytes(), null);
        }
    }

    /** Handle file downloads from cloudStorage directory */
    private static void handleDownload(OutputStream out, String path) throws IOException {
        // Parse /download?name=path/to/file.txt
        String fileName = path.substring(path.indexOf("name=") + 5);
        File file = new File("cloudStorage", fileName);
        if (file.exists() && file.isFile()) {
            byte[] content = Files.readAllBytes(file.toPath());
            // Instruct browser to download file as attachment
            String header = "Content-Disposition: attachment; filename=\"" + file.getName() + "\"";
            sendResponse(out, "200 OK", "application/octet-stream", content, header);
        }
    }

    /** Send HTTP response with status, headers, and body */
    private static void sendResponse(OutputStream out, String status, String type, byte[] body, String extraHeader) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\n").getBytes());
        out.write(("Content-Type: " + type + "; charset=UTF-8\r\n").getBytes());
        out.write(("Content-Length: " + body.length + "\r\n").getBytes());
        if (extraHeader != null) out.write((extraHeader + "\r\n").getBytes());
        out.write(("\r\n").getBytes());
        out.write(body);
        out.flush();
    }
}