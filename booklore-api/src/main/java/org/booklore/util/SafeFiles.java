package org.booklore.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Writes into the library so that a book is never left half-written, on local disks and on network
 * shares (NFS, SMB) alike:
 * <ul>
 *   <li>A file is never written in place. New content goes to a hidden temp file beside the target
 *       (".trove-…", which the folder watchers ignore) and is renamed over the target in one step,
 *       so readers see either the old file or the new one.</li>
 *   <li>Every copy is read back and compared with the source by SHA-256, and retried if they differ.
 *       Data is copied through a plain buffer rather than the kernel's copy offload, which has
 *       produced empty files on some network mounts.</li>
 *   <li>Rewritten books are built in a local temp folder, checked with {@link BookFileIntegrity},
 *       and only then copied into the library. If anything fails, the original stays untouched.</li>
 * </ul>
 */
@Slf4j
public final class SafeFiles {

    /** Builds the new version of a file at {@code local}; returns false if there's nothing to change. */
    @FunctionalInterface
    public interface LocalWriter {
        boolean write(Path local) throws Exception;
    }

    static final String TEMP_PREFIX = ".trove-";
    private static final int COPY_ATTEMPTS = 3;
    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final long RETRY_PAUSE_MILLIS = 2_000;
    private static final long STALE_TEMP_MILLIS = 60 * 60 * 1000;
    private static final int CASE_RENAME_ATTEMPTS = 5;
    private static final long CASE_RENAME_RETRY_MILLIS = 1_200;

    private SafeFiles() {
    }

    /**
     * Replaces {@code target} with a new version built by {@code writer} in a local temp folder. The
     * writer gets a path that doesn't exist yet, with the target's extension. The result must pass
     * {@link BookFileIntegrity#check} before it's copied over the target.
     *
     * @return true if the target was replaced, false if the writer reported nothing to change
     * @throws IOException if the writer fails, its output is damaged, or it can't be copied into place;
     *                     the target is unchanged in all of these cases
     */
    public static boolean replace(Path target, LocalWriter writer) throws IOException {
        Path workDir = Files.createTempDirectory("trove-write-");
        try {
            Path local = workDir.resolve("new" + extension(target));
            boolean changed;
            try {
                changed = writer.write(local);
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
            }
            if (!changed) {
                return false;
            }
            String problem = BookFileIntegrity.check(local);
            if (problem != null) {
                throw new IOException("The new version of " + target.getFileName() + " is damaged (" + problem
                        + "); the original was left as it was");
            }
            copy(local, target);
            return true;
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * Copies {@code source} to {@code target} (replacing it), verified by SHA-256 and retried up to
     * three times. The source is left as it is.
     */
    public static void copy(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new NoSuchFileException(source.toString());
        }
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        removeStaleTempFiles(parent);
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= COPY_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                pause(RETRY_PAUSE_MILLIS * (attempt - 1));
            }
            Path temp = parent.resolve(TEMP_PREFIX + UUID.randomUUID().toString().substring(0, 8) + ".tmp");
            try {
                String sourceHash = copyAndHash(source, temp);
                String writtenHash = sha256(temp);
                if (!writtenHash.equals(sourceHash)) {
                    throw new IOException("the copy in " + parent + " doesn't match " + source.getFileName()
                            + " (read back " + Files.size(temp) + " of " + Files.size(source) + " bytes)");
                }
                renameIntoPlace(temp, target);
                if (attempt > 1) {
                    log.info("Copied {} to {} on attempt {}", source, target, attempt);
                }
                return;
            } catch (NoSuchFileException e) {
                throw e;
            } catch (IOException e) {
                lastFailure = e;
                log.warn("Copying {} to {} failed on attempt {}/{}: {}", source, target, attempt, COPY_ATTEMPTS, e.getMessage());
            } finally {
                deleteTempQuietly(temp);
            }
        }
        throw new IOException("Couldn't copy " + source + " to " + target + " after " + COPY_ATTEMPTS
                + " attempts: " + lastFailure.getMessage(), lastFailure);
    }

    /** Moves {@code source} to {@code target}, replacing it. See {@link #move(Path, Path, boolean)}. */
    public static void move(Path source, Path target) throws IOException {
        move(source, target, true);
    }

    /**
     * Moves a file or folder. On one filesystem that's a single rename. Across filesystems (for
     * example from /tmp or the BookDrop folder onto a network share) each file is copied with
     * {@link #copy} and the source is deleted only once everything has arrived intact.
     */
    public static void move(Path source, Path target, boolean replaceExisting) throws IOException {
        if (differsOnlyInCase(source, target)) {
            if (!replaceExisting && Files.exists(target) && !Files.isSameFile(source, target)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            renameCase(source, target);
            return;
        }
        if (!replaceExisting && Files.exists(target)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            if (replaceExisting) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
            return;
        } catch (AtomicMoveNotSupportedException e) {
            // different filesystems: fall through to a verified copy
        }
        if (Files.isDirectory(source)) {
            copyTree(source, target);
            deleteRecursively(source);
        } else {
            copy(source, target);
            Files.delete(source);
        }
        log.debug("Moved {} to {} across filesystems", source, target);
    }

    /**
     * Plain copy through a buffer into a new file, for making a local working copy (for example of a
     * book that a tool edits in place). Not verified; use {@link #copy} to write into the library.
     */
    public static void copyContents(Path source, Path dest) throws IOException {
        copyAndHash(source, dest);
    }

    private static boolean differsOnlyInCase(Path source, Path target) {
        Path from = source.toAbsolutePath().normalize();
        Path to = target.toAbsolutePath().normalize();
        return !from.equals(to) && from.getParent() != null && from.getParent().equals(to.getParent())
                && from.getFileName().toString().equalsIgnoreCase(to.getFileName().toString());
    }

    /**
     * Changes only the case of a name. On a case-insensitive filesystem (SMB shares, macOS) the new
     * name already resolves to the file itself, so a plain rename is treated as a no-op - by Java and
     * by the kernel. It goes via a temporary name instead, and is checked against the directory
     * listing: a lookup of the new name cached by the client (about a second on SMB) can still turn
     * the second step into a no-op, in which case it waits and tries again. The file is never left
     * under the temporary name: if it can't be renamed, it's put back.
     */
    private static void renameCase(Path source, Path target) throws IOException {
        Path temp = source.resolveSibling(TEMP_PREFIX + UUID.randomUUID().toString().substring(0, 8) + ".rename");
        Files.move(source, temp, StandardCopyOption.ATOMIC_MOVE);
        for (int attempt = 1; attempt <= CASE_RENAME_ATTEMPTS; attempt++) {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            if (isListed(target) && !isListed(temp)) {
                return;
            }
            log.debug("Renaming {} to {} didn't take (cached lookup?), attempt {}/{}", temp, target, attempt, CASE_RENAME_ATTEMPTS);
            try {
                Thread.sleep(CASE_RENAME_RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Files.move(temp, source, StandardCopyOption.ATOMIC_MOVE);
        throw new IOException("Couldn't rename " + source.getFileName() + " to " + target.getFileName() + "; left as it was");
    }

    // Whether the directory listing has exactly this name - the listing comes from the server, unlike
    // lookups by name, which the client may answer from its cache.
    private static boolean isListed(Path file) throws IOException {
        String name = file.getFileName().toString();
        try (Stream<Path> entries = Files.list(file.toAbsolutePath().getParent())) {
            return entries.anyMatch(entry -> entry.getFileName().toString().equals(name));
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path dest = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    copy(path, dest);
                }
            }
        }
    }

    // Copies through a heap buffer, hashing what was read, then forces the data to disk.
    private static String copyAndHash(Path source, Path temp) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream in = Files.newInputStream(source);
             FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            OutputStream out = Channels.newOutputStream(channel);
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
            out.flush();
            channel.force(true);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // If the share is unreachable the temp file can't be removed now; it's swept up the next time
    // something is written to that folder (removeStaleTempFiles). Never lets a cleanup failure hide
    // the error that matters.
    private static void deleteTempQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("Couldn't remove the partial copy {} ({}); it will be cleaned up later", temp, e.getMessage());
        }
    }

    /** Removes temp files that an interrupted write left in {@code folder} more than an hour ago. */
    static void removeStaleTempFiles(Path folder) {
        long cutoff = System.currentTimeMillis() - STALE_TEMP_MILLIS;
        try (Stream<Path> entries = Files.list(folder)) {
            entries.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(TEMP_PREFIX) && (name.endsWith(".tmp") || name.endsWith(".rename"));
                    })
                    .forEach(path -> {
                        try {
                            if (Files.getLastModifiedTime(path).toMillis() < cutoff && Files.isRegularFile(path)) {
                                Files.delete(path);
                                log.info("Removed {} left behind by an interrupted write", path);
                            }
                        } catch (IOException e) {
                            log.debug("Couldn't remove stale temp file {}: {}", path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("Couldn't check {} for stale temp files: {}", folder, e.getMessage());
        }
    }

    private static void pause(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while retrying a copy", e);
        }
    }

    private static void renameIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        syncDirectory(target.toAbsolutePath().getParent());
    }

    // Makes the rename itself durable. Not every filesystem allows opening a directory for this
    // (SMB mounts often don't), and the data is already on disk, so failures are ignored.
    private static void syncDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            log.trace("Couldn't sync directory {}: {}", dir, e.getMessage());
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Couldn't delete {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Couldn't clean up {}: {}", root, e.getMessage());
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
