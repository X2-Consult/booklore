package org.booklore.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fetches the helper programs Trove runs (kepubify, ffprobe) from their upstream releases.
 * Downloads are pinned to a SHA-256 checksum, so a replaced or corrupted file is never executed.
 */
@Slf4j
public final class ToolBinaries {

    /**
     * One pinned release file. {@code zipEntry} is the program's name inside the archive, or null when
     * the download is the program itself.
     */
    public record Asset(String url, String sha256, String zipEntry) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private ToolBinaries() {
    }

    /**
     * Returns {@code toolsDir/fileName}, downloading and verifying it first if it isn't there yet.
     */
    public static synchronized Path ensureInstalled(Path toolsDir, String fileName, Asset asset) throws IOException {
        Files.createDirectories(toolsDir);
        Path binary = toolsDir.resolve(fileName);
        if (!Files.exists(binary)) {
            log.info("Downloading {} from {}", fileName, asset.url());
            Path download = Files.createTempFile(toolsDir, fileName, ".download");
            try {
                fetch(asset.url(), download);
                String actual = sha256(download);
                if (!actual.equalsIgnoreCase(asset.sha256())) {
                    throw new IOException("Checksum mismatch for " + asset.url() + ": expected " + asset.sha256() + ", got " + actual);
                }
                Path program = asset.zipEntry() == null ? download : extract(download, asset.zipEntry(), toolsDir, fileName);
                Files.move(program, binary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(download);
            }
            log.info("Installed {} at {}", fileName, binary.toAbsolutePath());
        }
        if (!binary.toFile().setExecutable(true)) {
            log.warn("Failed to set executable permission for '{}'", binary.toAbsolutePath());
        }
        return binary;
    }

    /**
     * Finds an executable called {@code command} on the PATH, for platforms with no pinned download
     * or when the download fails. Returns null if there isn't one.
     */
    public static Path findOnPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void fetch(String url, Path target) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
        try {
            HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() != 200) {
                throw new IOException("Download of " + url + " failed with HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download of " + url + " was interrupted", e);
        }
    }

    private static Path extract(Path zip, String entryName, Path toolsDir, String fileName) throws IOException {
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("'" + entryName + "' not found in downloaded archive");
            }
            Path extracted = Files.createTempFile(toolsDir, fileName, ".part");
            try (InputStream in = zipFile.getInputStream(entry)) {
                Files.copy(in, extracted, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(extracted);
                throw e;
            }
            return extracted;
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
