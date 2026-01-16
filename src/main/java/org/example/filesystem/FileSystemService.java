package org.example.filesystem;

import java.io.File;
import java.io.IOException;
import java.util.Stack;

/**
 * File System Service.
 * Provides sandboxed file operations for each client.
 * All paths are restricted to cloudStorage directory.
 */
public class FileSystemService {
    
    private final File root;
    private File currentDir;
    private final Stack<File> history;

    /** Create filesystem service rooted at specified directory. */
    public FileSystemService(File root) {
        this.root = root;
        this.currentDir = root;
        this.history = new Stack<>();
    }

    /** Get current working directory. */
    public File getCurrentDir() { 
        return currentDir; 
    }

    /** List contents of current directory with type and size. */
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

    /** Calculate total size of a folder recursively. */
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

    /** Change to subdirectory or parent (".."): */
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

    /** Undo last directory change. */
    public boolean undo() {
        if (history.isEmpty()) return false;
        currentDir = history.pop();
        return true;
    }

    /** Jump to absolute path within sandbox (e.g., "/folder1/subfolder"). */
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
    
    /** Get current path as Unix-style string (e.g., "/folder1"). */
    public String pwd() {
        String fullPath = currentDir.getAbsolutePath();
        String rootPath = root.getAbsolutePath();
        if (fullPath.equals(rootPath)) return "/";
        
        // Get relative path and normalize to Unix forward slashes
        String relative = fullPath.substring(rootPath.length());
        return relative.replace("\\", "/");
    }

    /** Create new directory in current folder. */
    public boolean mkdir(String name) {
        File target = new File(currentDir, name);
        return !target.exists() && target.mkdir();
    }

    /** Rename file or directory. Validates paths stay within sandbox. */
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

    /** Delete file or directory (recursive for folders). */
    public boolean rm(String name) {
        File target = new File(currentDir, name);
        if (!target.exists()) return false;
        if (target.isDirectory()) {
            return deleteDirectory(target);
        }
        return target.delete();
    }

    /** Recursively delete directory and all contents. */
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