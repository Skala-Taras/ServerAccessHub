package org.example.filesystem;

import java.io.File;
import java.io.IOException;
import java.util.Stack;

/**
 * FileSystemService: Sandbox filesystem operations
 * All operations restricted to cloudStorage root directory
 * Each client gets its own instance for thread-safe file operations
 */
public class FileSystemService {
    private final File root;           // Sandbox root (cloudStorage)
    private File currentDir;           // Current working directory
    private final Stack<File> history; // Directory change history for undo

    public FileSystemService(File root) {
        this.root = root;
        this.currentDir = root;
        this.history = new Stack<>();
    }

    public File getCurrentDir() { return currentDir; }

    /** List directory contents with file info (type and size in KB) */
    public String ls() {
        File[] files = currentDir.listFiles();
        if (files == null) return "(empty directory)";
        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            if (f.isDirectory()) {
                sb.append("[DIR]  ").append(f.getName()).append("\n");
            } else {
                // Show file size in KB
                sb.append("[FILE] ").append(f.getName()).append(" (").append(f.length() / 1024).append(" KB)\n");
            }
        }
        return sb.toString().trim();
    }

    /** 
     * Change directory (single level)
     * Supports ".." to go up one level, but won't escape sandbox
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

    /** Undo last directory change using navigation history */
    public boolean undo() {
        if (history.isEmpty()) return false;
        currentDir = history.pop();
        return true;
    }

    /** 
     * Jump to absolute path (e.g., /folder1/subfolder2)
     * Validates path stays within sandbox root
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
    
    /** Get current path as Unix-style string (e.g., /folder1/subfolder2) */
    public String pwd() {
        String fullPath = currentDir.getAbsolutePath();
        String rootPath = root.getAbsolutePath();
        if (fullPath.equals(rootPath)) return "/";
        
        // Get relative path and normalize to Unix forward slashes
        String relative = fullPath.substring(rootPath.length());
        return relative.replace("\\", "/");
    }

    public boolean mkdir(String name) {
        File target = new File(currentDir, name);
        return !target.exists() && target.mkdir();
    }

    /** Remove file or directory (recursive for directories) */
    public boolean rm(String name) {
        File target = new File(currentDir, name);
        if (!target.exists()) return false;
        if (target.isDirectory()) {
            return deleteDirectory(target);
        }
        return target.delete();
    }

    /** Recursively delete directory and all contents */
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