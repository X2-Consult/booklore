package org.booklore.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;

import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zips a folder (e.g. a multi-file audiobook) for download without holding the archive in memory.
 * Building it in a ByteArrayOutputStream needed the whole zip on the heap, plus a second copy from
 * toByteArray(), so a 1.5 GB audiobook took ~3 GB of heap and anything over 2 GB couldn't be built
 * at all. The zip goes to a temp file instead and is served from disk.
 */
@Slf4j
public final class FolderZip {

    private FolderZip() {
    }

    /**
     * Zips the folder's regular files, sorted by name, into a temp file. The returned resource streams
     * that file and deletes it once the stream is closed, which Spring does after writing the response.
     */
    public static FileSystemResource zipToTempFile(Path folder) throws IOException {
        Path zip = Files.createTempFile("booklore-folder-", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zip)));
             Stream<Path> entries = Files.list(folder)) {
            List<Path> files = entries.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path file : files) {
                zos.putNextEntry(new ZipEntry(file.getFileName().toString()));
                Files.copy(file, zos);
                zos.closeEntry();
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(zip);
            throw e;
        }
        // Backstop for a response that never reads the body (e.g. the client disconnects first).
        zip.toFile().deleteOnExit();
        return new DeleteOnCloseResource(zip);
    }

    private static final class DeleteOnCloseResource extends FileSystemResource {

        private final Path zip;

        private DeleteOnCloseResource(Path zip) {
            super(zip);
            this.zip = zip;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new FilterInputStream(super.getInputStream()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        try {
                            Files.deleteIfExists(zip);
                        } catch (IOException e) {
                            log.warn("Could not delete temporary download zip {}: {}", zip, e.getMessage());
                        }
                    }
                }
            };
        }
    }
}
