package org.booklore.service.monitoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkFolderPollerTest {

    private record Event(WatchEvent.Kind<?> kind, long libraryId, Path path, boolean isDirectory) {
    }

    @TempDir
    Path library;

    private final List<Event> events = new ArrayList<>();
    private final AtomicBoolean mounted = new AtomicBoolean(true);
    private NetworkFolderPoller poller;

    @BeforeEach
    void setUp() {
        poller = new NetworkFolderPoller(
                (kind, libraryId, path, isDirectory) -> events.add(new Event(kind, libraryId, path, isDirectory)),
                path -> path.getFileName().toString().endsWith(".epub"),
                path -> mounted.get());
    }

    @Test
    void theExistingContentsAreTheBaseline() throws IOException {
        book("Author/Existing.epub");
        poller.register(library, 7);

        poller.pollAll();

        assertThat(events).isEmpty();
    }

    @Test
    void reportsBooksAddedAndRemovedByOtherMachines() throws IOException {
        Path existing = book("Author/Existing.epub");
        poller.register(library, 7);

        Path added = book("Author/Added.epub");
        Files.delete(existing);
        poller.pollAll();

        assertThat(events).containsExactlyInAnyOrder(
                new Event(StandardWatchEventKinds.ENTRY_CREATE, 7, added, false),
                new Event(StandardWatchEventKinds.ENTRY_DELETE, 7, existing, false));
    }

    @Test
    void aNewFolderIsAnnouncedOnceAsAFolder() throws IOException {
        poller.register(library, 7);

        book("New Author/Series/One.epub");
        book("New Author/Series/Two.epub");
        poller.pollAll();

        assertThat(events).containsExactly(new Event(StandardWatchEventKinds.ENTRY_CREATE, 7, library.resolve("New Author"), true));

        events.clear();
        Path three = book("New Author/Series/Three.epub");
        poller.pollAll();
        assertThat(events).containsExactly(new Event(StandardWatchEventKinds.ENTRY_CREATE, 7, three, false));
    }

    @Test
    void aRemovedFolderIsAnnouncedAsAFolder() throws IOException {
        book("Gone/One.epub");
        book("Kept/Two.epub");
        poller.register(library, 7);

        deleteTree(library.resolve("Gone"));
        poller.pollAll();

        assertThat(events).containsExactly(new Event(StandardWatchEventKinds.ENTRY_DELETE, 7, library.resolve("Gone"), true));
    }

    @Test
    void nothingChangedMeansNoEvents_acrossAFullRescan() throws IOException {
        book("Author/Book.epub");
        poller.register(library, 7);

        for (int i = 0; i < NetworkFolderPoller.FULL_RESCAN_EVERY + 1; i++) {
            poller.pollAll();
        }

        assertThat(events).isEmpty();
    }

    @Test
    void housekeepingFoldersHiddenFilesAndNonBooksAreIgnored() throws IOException {
        poller.register(library, 7);

        book("@eaDir/Book.epub/SYNOFILE_THUMB_M.jpg");
        book(".trove-1234abcd.tmp");
        book("Author/notes.txt");
        poller.pollAll();

        assertThat(events).containsExactly(new Event(StandardWatchEventKinds.ENTRY_CREATE, 7, library.resolve("Author"), true));
    }

    @Test
    void nothingHappensWhileTheShareIsUnmounted() throws IOException {
        Path existing = book("Author/Existing.epub");
        poller.register(library, 7);

        mounted.set(false);
        Files.delete(existing);
        poller.pollAll();
        assertThat(events).isEmpty();

        mounted.set(true);
        poller.pollAll();
        assertThat(events).containsExactly(new Event(StandardWatchEventKinds.ENTRY_DELETE, 7, existing, false));
    }

    @Test
    void aMassDisappearanceWaitsForASecondLookBeforeCountingAsDeleted() throws IOException {
        for (int i = 0; i < 25; i++) {
            book("Author/Book " + i + ".epub");
        }
        poller.register(library, 7);

        deleteTree(library.resolve("Author"));
        poller.pollAll();
        assertThat(events).isEmpty();

        poller.pollAll();
        assertThat(events).containsExactly(new Event(StandardWatchEventKinds.ENTRY_DELETE, 7, library.resolve("Author"), true));
    }

    @Test
    void aMassDisappearanceThatComesBackIsIgnored() throws IOException {
        for (int i = 0; i < 25; i++) {
            book("Author/Book " + i + ".epub");
        }
        poller.register(library, 7);
        Path author = library.resolve("Author");
        Path hidden = library.getParent().resolve(library.getFileName() + "-away");

        Files.move(author, hidden); // the share hiccups...
        poller.pollAll();
        Files.move(hidden, author); // ...and comes back
        poller.pollAll();

        assertThat(events).isEmpty();
    }

    @Test
    void tracksWhichFoldersItCovers() throws IOException {
        book("Author/Book.epub");
        poller.register(library, 7);

        assertThat(poller.isPolled(library.resolve("Author/Book.epub"))).isTrue();
        assertThat(poller.isLibraryPolled(7)).isTrue();
        assertThat(poller.foldersForLibraries(Set.of(7L))).contains(library, library.resolve("Author"));

        poller.unregisterLibrary(7);
        assertThat(poller.isPolled(library)).isFalse();
        assertThat(poller.foldersForLibraries(Set.of(7L))).isEmpty();
    }

    private Path book(String relative) throws IOException {
        Path file = library.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "book");
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
