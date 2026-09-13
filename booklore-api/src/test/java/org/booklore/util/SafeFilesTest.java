package org.booklore.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SafeFilesTest {

    @TempDir
    Path dir;

    @Test
    void copyReplacesTheTarget_andLeavesNoTempFiles() throws IOException {
        Path source = Files.writeString(dir.resolve("source.epub"), "new content");
        Path target = Files.writeString(Files.createDirectories(dir.resolve("library/Author")).resolve("book.epub"), "old");

        SafeFiles.copy(source, target);

        assertThat(Files.readString(target)).isEqualTo("new content");
        assertThat(Files.readString(source)).isEqualTo("new content");
        assertThat(tempFilesIn(target.getParent())).isZero();
    }

    @Test
    void copyOfAMissingSourceFailsWithoutTouchingTheTarget() throws IOException {
        Path target = Files.writeString(dir.resolve("book.epub"), "old");

        assertThatThrownBy(() -> SafeFiles.copy(dir.resolve("missing.epub"), target)).isInstanceOf(NoSuchFileException.class);
        assertThat(Files.readString(target)).isEqualTo("old");
    }

    @Test
    void replacePublishesACheckedNewVersion() throws IOException {
        Path target = Files.writeString(dir.resolve("book.epub"), "old");

        boolean replaced = SafeFiles.replace(target, local -> {
            BookFileIntegrityTest.zip(local);
            return true;
        });

        assertThat(replaced).isTrue();
        assertThat(BookFileIntegrity.check(target)).isNull();
        assertThat(tempFilesIn(dir)).isZero();
    }

    @Test
    void replaceWithNothingToChangeLeavesTheTargetAlone() throws IOException {
        Path target = Files.writeString(dir.resolve("book.epub"), "old");

        assertThat(SafeFiles.replace(target, local -> false)).isFalse();
        assertThat(Files.readString(target)).isEqualTo("old");
    }

    @Test
    void aDamagedNewVersionNeverReplacesTheOriginal() throws IOException {
        Path target = BookFileIntegrityTest.zip(dir.resolve("book.epub"));
        byte[] original = Files.readAllBytes(target);

        assertThatThrownBy(() -> SafeFiles.replace(target, local -> {
            Files.writeString(local, "not a zip");
            return true;
        })).isInstanceOf(IOException.class).hasMessageContaining("damaged").hasMessageContaining("left as it was");

        assertThat(Files.readAllBytes(target)).isEqualTo(original);
        assertThat(tempFilesIn(dir)).isZero();
    }

    @Test
    void aFailingWriterLeavesTheOriginal() throws IOException {
        Path target = Files.writeString(dir.resolve("book.pdf"), "old");

        assertThatThrownBy(() -> SafeFiles.replace(target, local -> {
            throw new IllegalStateException("PDF library blew up");
        })).isInstanceOf(IOException.class).hasMessageContaining("PDF library blew up");

        assertThat(Files.readString(target)).isEqualTo("old");
    }

    @Test
    void moveOnOneFilesystemIsARename() throws IOException {
        Path source = Files.writeString(dir.resolve("book.epub"), "content");
        Path target = dir.resolve("Author/Title/book.epub");

        SafeFiles.move(source, target);

        assertThat(source).doesNotExist();
        assertThat(Files.readString(target)).isEqualTo("content");
    }

    @Test
    void moveWithoutReplaceRefusesAnExistingTarget() throws IOException {
        Path source = Files.writeString(dir.resolve("a.epub"), "a");
        Path target = Files.writeString(dir.resolve("b.epub"), "b");

        assertThatThrownBy(() -> SafeFiles.move(source, target, false)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(source)).isEqualTo("a");
        assertThat(Files.readString(target)).isEqualTo("b");
    }

    @Test
    void moveAcrossFilesystemsCopiesVerifiesAndDeletesTheSource() throws IOException {
        Path shm = Path.of("/dev/shm");
        assumeTrue(Files.isDirectory(shm) && Files.isWritable(shm)
                && !Files.getFileStore(shm).equals(Files.getFileStore(dir)), "needs a second filesystem (/dev/shm)");
        Path other = Files.createTempDirectory(shm, "safefiles-test-");
        try {
            Path file = Files.writeString(other.resolve("book.epub"), "across");
            Path folder = Files.createDirectories(other.resolve("Audiobook"));
            Files.writeString(folder.resolve("01.mp3"), "one");
            Files.writeString(Files.createDirectories(folder.resolve("cd2")).resolve("02.mp3"), "two");

            SafeFiles.move(file, dir.resolve("book.epub"));
            SafeFiles.move(folder, dir.resolve("Audiobook"));

            assertThat(file).doesNotExist();
            assertThat(folder).doesNotExist();
            assertThat(Files.readString(dir.resolve("book.epub"))).isEqualTo("across");
            assertThat(Files.readString(dir.resolve("Audiobook/cd2/02.mp3"))).isEqualTo("two");
            assertThat(tempFilesIn(dir)).isZero();
        } finally {
            try (Stream<Path> paths = Files.walk(other)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static long tempFilesIn(Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(p -> p.getFileName().toString().startsWith(SafeFiles.TEMP_PREFIX)).count();
        }
    }
}
