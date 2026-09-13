package org.booklore.util;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BookFileIntegrityTest {

    @TempDir
    Path dir;

    @Test
    void intactEpubPasses_andATruncatedOneFails() throws IOException {
        Path epub = zip(dir.resolve("book.epub"));
        assertThat(BookFileIntegrity.check(epub)).isNull();

        truncate(epub, Files.size(epub) / 2);
        assertThat(BookFileIntegrity.check(epub)).isNotNull();
    }

    @Test
    void zipWithACorruptEntryFails() throws IOException {
        Path cbz = zip(dir.resolve("comic.cbz"));
        byte[] bytes = Files.readAllBytes(cbz);
        bytes[bytes.length / 2] ^= 0x5A; // inside the large chapter entry's data
        Files.write(cbz, bytes);

        assertThat(BookFileIntegrity.check(cbz)).isNotNull();
    }

    @Test
    void intactPdfPasses_andATruncatedOneFails() throws IOException {
        Path pdf = dir.resolve("book.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdf.toFile());
        }
        assertThat(BookFileIntegrity.check(pdf)).isNull();

        truncate(pdf, Files.size(pdf) - 40);
        assertThat(BookFileIntegrity.check(pdf)).contains("%%EOF");
    }

    @Test
    void intact7zPasses_andACorruptOneFails() throws IOException {
        Path cb7 = dir.resolve("comic.cb7");
        byte[] page = randomBytes(64 * 1024);
        try (SevenZOutputFile out = new SevenZOutputFile(cb7.toFile())) {
            SevenZArchiveEntry entry = out.createArchiveEntry(dir.toFile(), "page1.jpg");
            entry.setDirectory(false);
            out.putArchiveEntry(entry);
            out.write(page);
            out.closeArchiveEntry();
        }
        assertThat(BookFileIntegrity.check(cb7)).isNull();

        byte[] bytes = Files.readAllBytes(cb7);
        bytes[bytes.length / 2] ^= 0x5A;
        Files.write(cb7, bytes);
        assertThat(BookFileIntegrity.check(cb7)).isNotNull();
    }

    @Test
    void mp4BoxesAreWalked() throws IOException {
        Path m4b = dir.resolve("book.m4b");
        Files.write(m4b, concat(box("ftyp", 16), box("moov", 64), box("mdat", 4096)));
        assertThat(BookFileIntegrity.check(m4b)).isNull();

        truncate(m4b, Files.size(m4b) - 1000);
        assertThat(BookFileIntegrity.check(m4b)).contains("'mdat'").contains("cut short");

        Files.write(m4b, concat(box("ftyp", 16), box("mdat", 4096)));
        assertThat(BookFileIntegrity.check(m4b)).contains("moov");
    }

    @Test
    void jsonIsParsed() throws IOException {
        Path good = Files.writeString(dir.resolve("book.json"), "{\"title\":\"Dune\"}");
        Path bad = Files.writeString(dir.resolve("bad.json"), "{\"title\":\"Du");
        assertThat(BookFileIntegrity.check(good)).isNull();
        assertThat(BookFileIntegrity.check(bad)).isNotNull();
    }

    @Test
    void emptyAndMissingFilesFail_uncheckedFormatsPassWhenNotEmpty() throws IOException {
        assertThat(BookFileIntegrity.check(Files.createFile(dir.resolve("empty.mobi")))).isEqualTo("file is empty");
        assertThat(BookFileIntegrity.check(dir.resolve("missing.epub"))).isEqualTo("file missing");
        assertThat(BookFileIntegrity.check(Files.writeString(dir.resolve("book.mobi"), "BOOKMOBI"))).isNull();
        assertThat(BookFileIntegrity.isCheckable(dir.resolve("book.mobi"))).isFalse();
        assertThat(BookFileIntegrity.isCheckable(dir.resolve("book.EPUB"))).isTrue();
    }

    static Path zip(Path file) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("mimetype"));
            out.write("application/epub+zip".getBytes(StandardCharsets.US_ASCII));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("OEBPS/chapter1.xhtml"));
            out.write(randomBytes(32 * 1024));
            out.closeEntry();
        }
        return file;
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    private static byte[] box(String type, int size) {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.putInt(size).put(type.getBytes(StandardCharsets.ISO_8859_1));
        return buffer.array();
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part);
        }
        return out.toByteArray();
    }

    private static void truncate(Path file, long length) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(length);
        }
    }
}
