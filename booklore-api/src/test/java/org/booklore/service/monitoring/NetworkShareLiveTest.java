package org.booklore.service.monitoring;

import org.booklore.util.BookFileIntegrity;
import org.booklore.util.MountInfo;
import org.booklore.util.SafeFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the network-storage code against a real mounted share. Skipped unless a share is given:
 * <pre>
 * TROVE_SHARE_TEST_DIR=/mnt/share            # a writable folder on an NFS or SMB mount
 * TROVE_SHARE_TEST_DIR_B=/mnt/share-b        # optional: the same folder through a second connection
 * ./gradlew test --tests '*NetworkShareLiveTest'
 * </pre>
 * The second mount stands in for another machine: changes made through it are invisible to inotify on
 * the first, which is what the poller exists for. Everything is done in a temporary subfolder that is
 * removed afterwards.
 */
@EnabledIfEnvironmentVariable(named = "TROVE_SHARE_TEST_DIR", matches = ".+")
class NetworkShareLiveTest {

    private final Path share = Path.of(System.getenv("TROVE_SHARE_TEST_DIR"));
    private final String otherConnection = System.getenv("TROVE_SHARE_TEST_DIR_B");
    private final String folderName = "trove-live-test-" + UUID.randomUUID().toString().substring(0, 8);
    private Path work;
    private Path local;

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(share.resolve(folderName));
        local = Files.createTempDirectory("trove-live-local-");
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteTree(work);
        deleteTree(local);
    }

    @Test
    void theShareIsDetectedAsNetworkStorage() {
        MountInfo.Mount mount = MountInfo.find(work).orElseThrow();
        System.out.println("[live] " + work + " is on " + mount.fsType() + " (" + mount.source() + ")");
        assertThat(mount.isNetwork()).isTrue();
    }

    @Test
    void aLargeCopyIsVerifiedAndLeavesNoTempFiles() throws Exception {
        Path source = local.resolve("audiobook.bin");
        writeRandom(source, 64L * 1024 * 1024);

        long start = System.nanoTime();
        SafeFiles.copy(source, work.resolve("audiobook.bin"));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.out.println("[live] 64 MB verified copy onto the share took " + millis + " ms");

        assertThat(sha256(work.resolve("audiobook.bin"))).isEqualTo(sha256(source));
        assertThat(tempFiles(work)).isEmpty();
    }

    @Test
    void aBookIsOnlyReplacedByACheckedNewVersion() throws Exception {
        Path book = work.resolve("Book.epub");
        epub(book, "old");
        byte[] original = Files.readAllBytes(book);

        SafeFiles.replace(book, localCopy -> {
            epub(localCopy, "new");
            return true;
        });
        assertThat(BookFileIntegrity.check(book)).isNull();
        assertThat(Files.readAllBytes(book)).isNotEqualTo(original);

        byte[] good = Files.readAllBytes(book);
        assertThatThrownBy(() -> SafeFiles.replace(book, localCopy -> {
            Files.writeString(localCopy, "not a zip");
            return true;
        })).hasMessageContaining("damaged");
        assertThat(Files.readAllBytes(book)).isEqualTo(good);
        assertThat(tempFiles(work)).isEmpty();
    }

    @Test
    void movesCrossBetweenLocalDiskAndTheShareBothWays() throws Exception {
        Path upload = Files.writeString(local.resolve("upload.epub"), "uploaded book");
        SafeFiles.move(upload, work.resolve("Author/upload.epub"));
        assertThat(upload).doesNotExist();
        assertThat(Files.readString(work.resolve("Author/upload.epub"))).isEqualTo("uploaded book");

        Path folder = Files.createDirectories(work.resolve("Audiobook"));
        Files.writeString(folder.resolve("01.mp3"), "one");
        Files.writeString(Files.createDirectories(folder.resolve("cd2")).resolve("02.mp3"), "two");
        SafeFiles.move(folder, local.resolve("Audiobook"));
        assertThat(folder).doesNotExist();
        assertThat(Files.readString(local.resolve("Audiobook/cd2/02.mp3"))).isEqualTo("two");
    }

    @Test
    void aCaseOnlyRenameReallyChangesTheName() throws Exception {
        Path lower = Files.writeString(work.resolve("dune.epub"), "dune");
        boolean caseInsensitive = Files.exists(work.resolve("DUNE.epub"));
        System.out.println("[live] share is case-" + (caseInsensitive ? "insensitive" : "sensitive"));

        SafeFiles.move(lower, work.resolve("Dune.epub"));

        try (Stream<Path> files = Files.list(work)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("Dune.epub");
        }
    }

    @Test
    void thePollerSeesBooksAddedThroughAnotherConnection_whichInotifyDoesNot() throws Exception {
        assumeTrue(otherConnection != null && !otherConnection.isBlank(), "needs TROVE_SHARE_TEST_DIR_B");
        Path elsewhere = Path.of(otherConnection).resolve(folderName);
        List<String> events = new ArrayList<>();
        NetworkFolderPoller poller = new NetworkFolderPoller(
                (kind, libraryId, path, isDirectory) -> events.add(kind.name() + " " + work.relativize(path)),
                path -> path.getFileName().toString().endsWith(".epub"),
                path -> true);
        poller.register(work, 1);

        try (WatchService inotify = FileSystems.getDefault().newWatchService()) {
            WatchKey key = work.register(inotify, StandardWatchEventKinds.ENTRY_CREATE);
            Files.writeString(elsewhere.resolve("From Another Machine.epub"), "book");

            WatchKey signalled = inotify.poll(5, TimeUnit.SECONDS);
            System.out.println("[live] inotify saw the other connection's write: " + (signalled != null));
            key.cancel();
        }

        pollUntil(poller, events, "ENTRY_CREATE From Another Machine.epub");
        Files.delete(elsewhere.resolve("From Another Machine.epub"));
        pollUntil(poller, events, "ENTRY_DELETE From Another Machine.epub");
    }

    // The client caches directory attributes briefly (actimeo), so give it a few polls.
    private static void pollUntil(NetworkFolderPoller poller, List<String> events, String expected) throws InterruptedException {
        for (int attempt = 0; attempt < 10 && !events.contains(expected); attempt++) {
            Thread.sleep(1_000);
            poller.pollAll();
        }
        assertThat(events).contains(expected);
    }

    private static void epub(Path file, String chapter) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("mimetype"));
            out.write("application/epub+zip".getBytes(StandardCharsets.US_ASCII));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("chapter.xhtml"));
            out.write(chapter.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static void writeRandom(Path file, long size) throws IOException {
        Random random = new Random(7);
        byte[] buffer = new byte[1024 * 1024];
        try (OutputStream out = Files.newOutputStream(file)) {
            for (long written = 0; written < size; written += buffer.length) {
                random.nextBytes(buffer);
                out.write(buffer);
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<Path> tempFiles(Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(p -> p.getFileName().toString().startsWith(".trove-")).toList();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
