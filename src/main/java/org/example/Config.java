package org.example;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Configuration loader.
 * Reads from environment variables or .env file.
 */
public class Config {

    private static final Properties properties = new Properties();
    private static boolean isFileLoaded = false;

    /** Get config value by key. Checks env vars first, then .env file. */
    public static String get(String key) {
        // 1. PRIORITY: Check System/Docker Environment Variable
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            return value;
        }

        // 2. FALLBACK: Load from .env file
        loadEnvFileOnce();
        return properties.getProperty(key);
    }

    /** Get required config value. Throws error if missing. */
    public static String getRequired(String key) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            throw new RuntimeException("CRITICAL: Missing required configuration for '" + key + "'. " +
                                       "Set it as an environment variable or in a .env file.");
        }
        return value;
    }

    private static synchronized void loadEnvFileOnce() {
        if (isFileLoaded) return;

        if (Files.exists(Paths.get(".env"))) {
            try (FileInputStream fis = new FileInputStream(".env")) {
                properties.load(fis);
                System.out.println("INFO: Loaded configuration from local .env file.");
            } catch (IOException e) {
                System.err.println("WARN: Could not read .env file: " + e.getMessage());
            }
        }
        isFileLoaded = true;
    }
}