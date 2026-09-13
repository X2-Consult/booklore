package org.booklore.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@UtilityClass
public class UnrarHelper {

    private static final String DEFAULT_UNRAR_BIN = "unrar";
    private static final int PROCESS_TIMEOUT_SECONDS = 120;
    private static volatile Boolean cachedAvailability;

    public static boolean isAvailable() {
        if (cachedAvailability != null) {
            return cachedAvailability;
        }
        synchronized (UnrarHelper.class) {
            if (cachedAvailability != null) {
                return cachedAvailability;
            }
            try {
                Process process = new ProcessBuilder(getUnrarBin())
                        .redirectErrorStream(true)
                        .start();
                try (InputStream is = process.getInputStream()) {
                    is.readAllBytes();
                }
                boolean finished = process.waitFor(5, TimeUnit.SECONDS);
                cachedAvailability = finished;
            } catch (Exception e) {
                log.debug("unrar binary not available: {}", e.getMessage());
                cachedAvailability = false;
            }
            if (cachedAvailability) {
                log.info("unrar CLI detected, RAR5 fallback enabled");
            }
            return cachedAvailability;
        }
    }

    public static List<String> listEntries(Path rarPath) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(getUnrarBin(), "lb", rarPath.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (InputStream is = process.getInputStream()) {
            output = new String(is.readAllBytes());
        }
        try {
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("unrar list timed out for: " + rarPath);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("unrar list interrupted", e);
        }
        if (process.exitValue() != 0) {
            throw new IOException("unrar list failed (exit " + process.exitValue() + ") for: " + rarPath);
        }
        return Arrays.stream(output.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public static void extractEntry(Path rarPath, String entryName, OutputStream out) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                getUnrarBin(), "p", "-inul", rarPath.toAbsolutePath().toString(), entryName
        );
        pb.redirectErrorStream(false);
        Process process = pb.start();
        try (InputStream is = process.getInputStream()) {
            is.transferTo(out);
        }
        try {
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("unrar extract timed out for entry: " + entryName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("unrar extract interrupted", e);
        }
        if (process.exitValue() != 0) {
            throw new IOException("unrar extract failed (exit " + process.exitValue() + ") for entry: " + entryName);
        }
    }

    public static byte[] extractEntryBytes(Path rarPath, String entryName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        extractEntry(rarPath, entryName, baos);
        return baos.toByteArray();
    }

    public static void extractAll(Path rarPath, Path targetDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                getUnrarBin(), "x", "-o+", rarPath.toAbsolutePath().toString(), targetDir.toAbsolutePath() + "/"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (InputStream is = process.getInputStream()) {
            is.readAllBytes();
        }
        try {
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("unrar extractAll timed out for: " + rarPath);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("unrar extractAll interrupted", e);
        }
        if (process.exitValue() != 0) {
            throw new IOException("unrar extractAll failed (exit " + process.exitValue() + ") for: " + rarPath);
        }
    }

    /** Runs {@code unrar t}, which reads every entry and checks its CRC. True if the archive is intact. */
    public static boolean testArchive(Path rarPath) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(getUnrarBin(), "t", "-idq", rarPath.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (InputStream is = process.getInputStream()) {
            is.readAllBytes();
        }
        try {
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("unrar test timed out for: " + rarPath);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("unrar test interrupted", e);
        }
        return process.exitValue() == 0;
    }

    private static String getUnrarBin() {
        return EnvVars.getOrDefault("UNRAR_BIN", DEFAULT_UNRAR_BIN);
    }
}
