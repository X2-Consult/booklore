package org.booklore.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FolderZipTest {

    @TempDir
    Path folder;

    @Test
    void zipsTheFolderFromDisk_andDeletesTheTempFileOnceStreamed() throws Exception {
        Files.writeString(folder.resolve("02 - Chapter Two.mp3"), "two");
        Files.writeString(folder.resolve("01 - Chapter One.mp3"), "one");
        Files.createDirectory(folder.resolve("artwork"));

        FileSystemResource zip = FolderZip.zipToTempFile(folder);
        Path zipPath = zip.getFile().toPath();
        assertThat(zip.contentLength()).isEqualTo(Files.size(zipPath)).isPositive();

        List<String> names = new ArrayList<>();
        try (InputStream in = zip.getInputStream(); ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName() + "=" + new String(zis.readAllBytes()));
            }
        }

        assertThat(names).containsExactly("01 - Chapter One.mp3=one", "02 - Chapter Two.mp3=two");
        assertThat(zipPath).doesNotExist();
    }
}
