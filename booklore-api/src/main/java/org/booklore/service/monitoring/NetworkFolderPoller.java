package org.booklore.service.monitoring;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Watches library folders on network shares by polling, because the kernel's change notifications
 * (inotify) only report changes made on this machine - a book copied onto the NAS from anywhere else
 * would never be seen. Each poll reads a directory's listing again only when its modified time has
 * changed, so an unchanged library costs one attribute read per folder. The events it sends are the
 * same create/delete events the notification-based watcher sends.
 * <p>
 * Guarded against a share dropping out: nothing happens while the folder isn't on its network mount,
 * a folder that can't be read keeps its last known contents, and a poll that would delete half of
 * what it knows is held back until the next poll sees the same.
 */
@Slf4j
class NetworkFolderPoller {

    @FunctionalInterface
    interface EventSink {
        void emit(WatchEvent.Kind<?> kind, long libraryId, Path path, boolean isDirectory);
    }

    /** A folder's contents as last read: subfolder names and book file names. */
    private record DirState(long modifiedMillis, long listedAtMillis, Set<String> subdirs, Set<String> files) {
    }

    private record Event(WatchEvent.Kind<?> kind, Path path, boolean isDirectory) {
    }

    private static final class Root {
        final long libraryId;
        final Map<Path, DirState> dirs;
        boolean deletionHeldBack;

        Root(long libraryId, Map<Path, DirState> dirs) {
            this.libraryId = libraryId;
            this.dirs = dirs;
        }
    }

    // Every tenth poll re-reads every folder, in case a filesystem doesn't update a folder's
    // modified time when its contents change.
    static final int FULL_RESCAN_EVERY = 10;
    // A folder modified within this long of being read may have changed again in the same clock tick
    // (some NAS filesystems keep whole seconds), so it's read again next time.
    private static final long MTIME_SETTLE_MILLIS = 2_000;
    private static final int MASS_DELETE_MIN_ENTRIES = 20;
    private static final Set<String> IGNORED_DIRS = Set.of("@eaDir", "#recycle", "#snapshot", "$RECYCLE.BIN", "lost+found");

    private final EventSink sink;
    private final Predicate<Path> isBookFile;
    private final Predicate<Path> isStillMounted;
    private final Map<Path, Root> roots = new ConcurrentHashMap<>();
    private int pollCount;

    NetworkFolderPoller(EventSink sink, Predicate<Path> isBookFile, Predicate<Path> isStillMounted) {
        this.sink = sink;
        this.isBookFile = isBookFile;
        this.isStillMounted = isStillMounted;
    }

    /** Starts polling {@code root} for {@code libraryId}, taking its current contents as the baseline. */
    synchronized void register(Path root, long libraryId) {
        Path normalized = root.toAbsolutePath().normalize();
        Map<Path, DirState> dirs = new HashMap<>();
        snapshotTree(normalized, dirs);
        roots.put(normalized, new Root(libraryId, dirs));
        log.info("Polling {} for library {} ({} folders): it's on a network share", normalized, libraryId, dirs.size());
    }

    synchronized void unregisterLibrary(long libraryId) {
        roots.values().removeIf(root -> root.libraryId == libraryId);
    }

    synchronized void unregister(Path root) {
        roots.remove(root.toAbsolutePath().normalize());
    }

    boolean isPolled(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return roots.keySet().stream().anyMatch(normalized::startsWith);
    }

    boolean isLibraryPolled(long libraryId) {
        return roots.values().stream().anyMatch(root -> root.libraryId == libraryId);
    }

    Set<Path> foldersForLibraries(Set<Long> libraryIds) {
        Set<Path> folders = new HashSet<>();
        roots.forEach((path, root) -> {
            if (libraryIds.contains(root.libraryId)) {
                synchronized (this) {
                    folders.addAll(root.dirs.keySet());
                }
            }
        });
        return folders;
    }

    /** Checks every registered root once and sends events for what changed. */
    synchronized void pollAll() {
        boolean full = ++pollCount % FULL_RESCAN_EVERY == 0;
        roots.forEach((path, root) -> {
            try {
                poll(path, root, full);
            } catch (RuntimeException e) {
                log.warn("Polling {} failed: {}", path, e.getMessage());
            }
        });
    }

    private void poll(Path rootPath, Root root, boolean full) {
        if (!isStillMounted.test(rootPath)) {
            log.warn("Skipping {}: it's no longer on its network share (unmounted?)", rootPath);
            return;
        }
        if (!Files.isDirectory(rootPath)) {
            return;
        }

        Map<Path, DirState> next = new HashMap<>(root.dirs);
        List<Event> events = new ArrayList<>();
        int deletedEntries = 0;
        Deque<Path> queue = new ArrayDeque<>();
        queue.add(rootPath);

        while (!queue.isEmpty()) {
            Path dir = queue.poll();
            DirState old = next.get(dir);
            DirState current = old;
            long modified = modifiedMillis(dir);
            boolean changed = old == null || modified != old.modifiedMillis()
                    || Math.abs(old.listedAtMillis() - old.modifiedMillis()) < MTIME_SETTLE_MILLIS;
            if (modified >= 0 && (full || changed)) {
                DirState listed = list(dir, modified);
                if (listed != null) {
                    current = listed;
                    next.put(dir, current);
                    if (old != null) {
                        deletedEntries += diff(dir, old, current, next, events);
                    }
                }
            }
            if (current == null) {
                continue;
            }
            for (String sub : current.subdirs()) {
                Path child = dir.resolve(sub);
                if (root.dirs.containsKey(child)) {
                    queue.add(child); // new folders were snapshotted by diff and announced as a whole
                }
            }
        }

        int known = root.dirs.values().stream().mapToInt(s -> s.subdirs().size() + s.files().size()).sum();
        if (deletedEntries >= MASS_DELETE_MIN_ENTRIES && deletedEntries * 2 >= known && !root.deletionHeldBack) {
            root.deletionHeldBack = true;
            log.warn("{} of {} entries under {} disappeared at once; waiting for the next check before treating them as deleted",
                    deletedEntries, known, rootPath);
            return;
        }
        root.deletionHeldBack = false;
        root.dirs.clear();
        root.dirs.putAll(next);
        for (Event event : events) {
            sink.emit(event.kind(), root.libraryId, event.path(), event.isDirectory());
        }
    }

    /** Records the differences between two listings of {@code dir}; returns how many entries went away. */
    private int diff(Path dir, DirState old, DirState current, Map<Path, DirState> next, List<Event> events) {
        int deleted = 0;
        for (String file : current.files()) {
            if (!old.files().contains(file)) {
                events.add(new Event(StandardWatchEventKinds.ENTRY_CREATE, dir.resolve(file), false));
            }
        }
        for (String file : old.files()) {
            if (!current.files().contains(file)) {
                events.add(new Event(StandardWatchEventKinds.ENTRY_DELETE, dir.resolve(file), false));
                deleted++;
            }
        }
        for (String sub : current.subdirs()) {
            if (!old.subdirs().contains(sub)) {
                Path child = dir.resolve(sub);
                snapshotTree(child, next);
                // One folder event, as the notification watcher sends: the processor scans it whole.
                events.add(new Event(StandardWatchEventKinds.ENTRY_CREATE, child, true));
            }
        }
        for (String sub : old.subdirs()) {
            if (!current.subdirs().contains(sub)) {
                Path child = dir.resolve(sub);
                deleted += 1 + countEntriesUnder(child, next);
                next.keySet().removeIf(path -> path.startsWith(child));
                events.add(new Event(StandardWatchEventKinds.ENTRY_DELETE, child, true));
            }
        }
        return deleted;
    }

    private static int countEntriesUnder(Path folder, Map<Path, DirState> dirs) {
        return dirs.entrySet().stream()
                .filter(e -> e.getKey().startsWith(folder))
                .mapToInt(e -> e.getValue().subdirs().size() + e.getValue().files().size())
                .sum();
    }

    private void snapshotTree(Path root, Map<Path, DirState> dirs) {
        Deque<Path> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Path dir = queue.poll();
            DirState state = list(dir, modifiedMillis(dir));
            if (state == null) {
                continue;
            }
            dirs.put(dir, state);
            state.subdirs().forEach(sub -> queue.add(dir.resolve(sub)));
        }
    }

    /** Reads a folder's listing; null if it can't be read right now. */
    private DirState list(Path dir, long modifiedMillis) {
        Set<String> subdirs = new HashSet<>();
        Set<String> files = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    if (!IGNORED_DIRS.contains(name)) {
                        subdirs.add(name);
                    }
                } else if (isBookFile.test(entry)) {
                    files.add(name);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Couldn't list {}: {}", dir, e.getMessage());
            return null;
        }
        return new DirState(modifiedMillis, System.currentTimeMillis(), Set.copyOf(subdirs), Set.copyOf(files));
    }

    private static long modifiedMillis(Path dir) {
        try {
            return Files.readAttributes(dir, BasicFileAttributes.class).lastModifiedTime().toMillis();
        } catch (IOException e) {
            return -1;
        }
    }
}
