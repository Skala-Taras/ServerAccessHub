package org.example;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Configuration Utility
 * <p>Provides a single, reliable way to get configuration values.
 * It prioritizes system environment variables (used by Docker)
 * and falls back to a local .env file (used for local development).</p>
 */
public class Config {

    private static final Properties properties = new Properties();
    private static boolean isFileLoaded = false;

    /**
     * Gets a configuration value.
     *
     * @param key The name of the configuration variable (e.g., "KEYSTORE_PASSWORD").
     * @return The value, or null if not found.
     */
    public static String get(String key) {
        // 1. PRIORITY: Check System/Docker Environment Variable
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            return value;
        }

        // 2. FALLBACK: Load and check local .env file (for IDE development)
        loadEnvFileOnce();
        return properties.getProperty(key);
    }

    /**
     * Gets a required configuration value. Throws a clear error if it's missing.
     * This prevents the server from starting in a broken state.
     *
     * @param key The name of the required configuration variable.
     * @return The non-null, non-blank value.
     * @throws RuntimeException if the configuration is missing.
     */
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