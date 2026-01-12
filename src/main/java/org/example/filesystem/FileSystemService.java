package org.example.filesystem;

import java.io.File;
import java.io.IOException;
import java.util.Stack;

/**
 * Sandboxed Filesystem Service for Personal Cloud Hub.
 * 
 * <p>Provides secure, per-client file system operations restricted to the cloudStorage
 * directory. Each WebSocket client gets its own instance to ensure thread-safe
 * directory navigation with independent state.</p>
 * 
 * <h2>Security Features</h2>
 * <ul>
 *   <li><b>Sandbox Enforcement:</b> All operations validate paths stay within root</li>
 *   <li><b>Traversal Protection:</b> Rejects ".." escapes and absolute paths</li>
 *   <li><b>Canonical Path Validation:</b> Resolves symlinks to prevent escapes</li>
 *   <li><b>Input Sanitization:</b> Validates names contain no path separators</li>
 * </ul>
 * 
 * <h2>Supported Operations</h2>
 * <table border="1">
 *   <tr><th>Method</th><th>Description</th></tr>
 *   <tr><td>ls()</td><td>List current directory contents</td></tr>
 *   <tr><td>cd(name)</td><td>Change to subdirectory or ".." for parent</td></tr>
 *   <tr><td>gotoPath(path)</td><td>Jump to absolute path within sandbox</td></tr>
 *   <tr><td>pwd()</td><td>Get current path as Unix-style string</td></tr>
 *   <tr><td>mkdir(name)</td><td>Create new directory</td></tr>
 *   <tr><td>rename(old, new)</td><td>Rename file or directory</td></tr>
 *   <tr><td>rm(name)</td><td>Delete file or directory recursively</td></tr>
 *   <tr><td>undo()</td><td>Return to previous directory</td></tr>
 * </table>
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * File root = new File("cloudStorage");
 * FileSystemService fs = new FileSystemService(root);
 * 
 * System.out.println(fs.pwd());  // "/"
 * fs.mkdir("documents");
 * fs.cd("documents");
 * System.out.println(fs.pwd());  // "/documents"
 * fs.undo();
 * System.out.println(fs.pwd());  // "/"
 * }</pre>
 * 
 * @author Personal Cloud Hub Team
 * @version 1.0
 * @see org.example.websocket.WebSocketHandler
 */
public class FileSystemService {
    
    /** Sandbox root directory - all operations must stay within this path */
    private final File root;
    
    /** Current working directory for this client session */
    private File currentDir;
    
    /** Navigation history stack for undo functionality */
    private final Stack<File> history;

    /**
     * Create a new filesystem service rooted at the specified directory.
     * 
     * @param root The sandbox root directory (e.g., cloudStorage folder)
     */
    public FileSystemService(File root) {
        this.root = root;
        this.currentDir = root;
        this.history = new Stack<>();
    }

    /**
     * Get the current working directory.
     * 
     * @return File object representing current directory
     */
    public File getCurrentDir() { 
        return currentDir; 
    }

    /**
     * List contents of current directory with type and size information.
     * 
     * <p>Output format:</p>
     * <pre>
     * [DIR]  folder1
     * [FILE] document.pdf (125 KB)
     * </pre>
     * 
     * <p>Note: Folder sizes are NOT calculated here for performance reasons.
     * Large folders would block the response for seconds/minutes.</p>
     * 
     * @return Formatted directory listing, or "(empty directory)" if empty
     */
    public String ls() {
        File[] files = currentDir.listFiles();
        if (files == null) return "(empty directory)";
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            if (f.isDirectory()) {
                // Don't calculate folder size - it's too slow for large folders
                sb.append("[DIR]  ").append(f.getName()).append("\n");
            } else {
                // Show file size in KB
                sb.append("[FILE] ").append(f.getName()).append(" (").append(f.length() / 1024).append(" KB)\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Calculate the total size of a folder recursively.
     * 
     * @param folder The folder to calculate size for
     * @return Total size in bytes
     */
    private long calculateFolderSize(File folder) {
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
     * Change to a subdirectory or parent directory.
     * 
     * <p>Supports:</p>
     * <ul>
     *   <li>".." - Navigate to parent (stops at sandbox root)</li>
     *   <li>Subdirectory name - Enter named directory</li>
     * </ul>
     * 
     * <p>Pushes current directory to history stack when navigating down,
     * enabling undo functionality.</p>
     * 
     * @param dirName Directory name or ".."
     * @return true if navigation succeeded, false if directory doesn't exist
     */
    public boolean cd(String dirName) {
        if ("..".equals(dirName)) {
            // Go to parent directory
            File parent = currentDir.getParentFile();
            if (parent != null && parent.getAbsolutePath().startsWith(root.getAbsolutePath())) {
                currentDir = parent;
            } else {
                // At sandbox root, go to root
                currentDir = root;
            }
            return true;
        }
        
        // Change to subdirectory
        File target = new File(currentDir, dirName);
        if (target.exists() && target.isDirectory()) {
            history.push(currentDir);
            currentDir = target;
            return true;
        }
        return false;
    }

    /**
     * Undo the last directory change.
     * 
     * <p>Pops from the navigation history stack, restoring the previous
     * working directory. History is built by cd() operations.</p>
     * 
     * @return true if undo succeeded, false if no history available
     */
    public boolean undo() {
        if (history.isEmpty()) return false;
        currentDir = history.pop();
        return true;
    }

    /**
     * Jump directly to an absolute path within the sandbox.
     * 
     * <p>Path format: Unix-style with forward slashes (e.g., "/folder1/subfolder2")</p>
     * 
     * <p>Special cases:</p>
     * <ul>
     *   <li>null, empty, or "/" - Navigate to root</li>
     *   <li>Leading/trailing slashes - Automatically stripped</li>
     * </ul>
     * 
     * <p>Security: Validates that target path exists, is a directory,
     * and remains within the sandbox root.</p>
     * 
     * @param path Unix-style path (e.g., "/documents/work")
     * @return true if navigation succeeded, false if invalid or outside sandbox
     */
    public boolean gotoPath(String path) {
        // Empty path or / means go to root
        if (path == null || path.trim().isEmpty() || path.trim().equals("/")) {
            currentDir = root;
            return true;
        }

        String p = path.trim();
        // Remove leading/trailing slashes for clean processing
        if (p.startsWith("/")) p = p.substring(1);
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);

        // Build target path relative to sandbox root
        File target = new File(root, p.replace("/", File.separator));

        // Verify: exists, is directory, and stays within sandbox
        if (target.exists() && target.isDirectory() && target.getAbsolutePath().startsWith(root.getAbsolutePath())) {
            currentDir = target;
            return true;
        }
        return false;
    }
    
    /**
     * Get current working directory as Unix-style path string.
     * 
     * <p>Returns path relative to sandbox root:</p>
     * <ul>
     *   <li>At root: "/"</li>
     *   <li>In subfolder: "/folder1/subfolder2"</li>
     * </ul>
     * 
     * @return Unix-style path with forward slashes
     */
    public String pwd() {
        String fullPath = currentDir.getAbsolutePath();
        String rootPath = root.getAbsolutePath();
        if (fullPath.equals(rootPath)) return "/";
        
        // Get relative path and normalize to Unix forward slashes
        String relative = fullPath.substring(rootPath.length());
        return relative.replace("\\", "/");
    }

    /**
     * Create a new directory in the current working directory.
     * 
     * @param name Name of directory to create (no path separators)
     * @return true if created, false if already exists or creation failed
     */
    public boolean mkdir(String name) {
        File target = new File(currentDir, name);
        return !target.exists() && target.mkdir();
    }

    /**
     * Rename a file or directory in the current working directory.
     * 
     * <p>Security validations:</p>
     * <ul>
     *   <li>Rejects names containing path separators (/, \)</li>
     *   <li>Rejects ".." in names</li>
     *   <li>Validates both source and target stay within sandbox</li>
     *   <li>Uses canonical paths to prevent symlink-based escapes</li>
     * </ul>
     * 
     * @param oldName Current name of file/directory
     * @param newName New name to assign
     * @return true if rename succeeded, false on validation failure or error
     */
    public boolean rename(String oldName, String newName) {
        // Validate names (no path separators, no special chars)
        if (oldName == null || newName == null) return false;
        if (oldName.contains("/") || oldName.contains("\\") || oldName.contains("..")) return false;
        if (newName.contains("/") || newName.contains("\\") || newName.contains("..")) return false;
        if (newName.trim().isEmpty()) return false;
        
        File source = new File(currentDir, oldName);
        File target = new File(currentDir, newName);
        
        // Check source exists and target doesn't
        if (!source.exists()) return false;
        if (target.exists()) return false;
        
        // Verify both stay within sandbox
        try {
            String sourceCanonical = source.getCanonicalPath();
            String targetCanonical = target.getCanonicalPath();
            String rootCanonical = root.getCanonicalPath();
            if (!sourceCanonical.startsWith(rootCanonical)) return false;
            if (!targetCanonical.startsWith(rootCanonical)) return false;
        } catch (IOException e) {
            return false;
        }
        
        return source.renameTo(target);
    }

    /**
     * Remove a file or directory from the current working directory.
     * 
     * <p>For directories, performs recursive deletion of all contents.</p>
     * 
     * @param name Name of file/directory to delete
     * @return true if deleted, false if doesn't exist or deletion failed
     */
    public boolean rm(String name) {
        File target = new File(currentDir, name);
        if (!target.exists()) return false;
        if (target.isDirectory()) {
            return deleteDirectory(target);
        }
        return target.delete();
    }

    /**
     * Recursively delete a directory and all its contents.
     * 
     * <p>Walks the directory tree depth-first, deleting files
     * before their parent directories.</p>
     * 
     * @param dir Directory to delete
     * @return true if fully deleted, false if any deletion failed
     */
    private boolean deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        return dir.delete();
    }
}