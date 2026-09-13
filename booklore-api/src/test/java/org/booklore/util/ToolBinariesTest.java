package org.booklore.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolBinariesTest {

    private static final byte[] PROGRAM = "#!/bin/sh\necho tool\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/tool", exchange -> respond(exchange, PROGRAM));
        server.createContext("/tool.zip", exchange -> respond(exchange, zip("ffprobe", PROGRAM)));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void downloadsVerifiesAndMarksExecutable() throws IOException {
        Path toolsDir = tempDir.resolve("tools");

        Path binary = ToolBinaries.ensureInstalled(toolsDir, "tool-linux-64", asset("/tool", sha256(PROGRAM), null));

        assertThat(binary).isEqualTo(toolsDir.resolve("tool-linux-64"));
        assertThat(Files.readAllBytes(binary)).isEqualTo(PROGRAM);
        assertThat(binary.toFile().canExecute()).isTrue();
        assertThat(toolsDir.toFile().list()).containsExactly("tool-linux-64");
    }

    @Test
    void extractsTheProgramFromAZip() throws IOException {
        byte[] zip = zip("ffprobe", PROGRAM);

        Path binary = ToolBinaries.ensureInstalled(tempDir, "ffprobe-linux-64", asset("/tool.zip", sha256(zip), "ffprobe"));

        assertThat(Files.readAllBytes(binary)).isEqualTo(PROGRAM);
        assertThat(tempDir.toFile().list()).containsExactly("ffprobe-linux-64");
    }

    @Test
    void rejectsADownloadWithTheWrongChecksum() {
        String wrong = "0".repeat(64);

        assertThatThrownBy(() -> ToolBinaries.ensureInstalled(tempDir, "tool", asset("/tool", wrong, null)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Checksum mismatch");
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void keepsAnAlreadyInstalledBinaryWithoutDownloading() throws IOException {
        Path existing = tempDir.resolve("tool");
        Files.writeString(existing, "cached");

        Path binary = ToolBinaries.ensureInstalled(tempDir, "tool", asset("/tool", "0".repeat(64), null));

        assertThat(binary).isEqualTo(existing);
        assertThat(Files.readString(binary)).isEqualTo("cached");
        assertThat(requests.get()).isZero();
    }

    @Test
    void failsOnHttpErrors() {
        assertThatThrownBy(() -> ToolBinaries.ensureInstalled(tempDir, "tool", asset("/missing", sha256(PROGRAM), null)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 404");
        assertThat(tempDir).isEmptyDirectory();
    }

    private ToolBinaries.Asset asset(String path, String sha256, String zipEntry) {
        return new ToolBinaries.Asset("http://127.0.0.1:" + server.getAddress().getPort() + path, sha256, zipEntry);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws IOException {
        requests.incrementAndGet();
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] zip(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] content) throws IOException {
        Path file = Files.createTempFile("sha", ".bin");
        try {
            Files.write(file, content);
            return ToolBinaries.sha256(file);
        } finally {
            Files.delete(file);
        }
    }
}
