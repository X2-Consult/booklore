package org.booklore.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finds which mounted filesystem a path lives on, from /proc/self/mountinfo, so network shares (NFS,
 * SMB and the like) can be told apart from local disks per library folder. Inside a container it
 * sees the real filesystem behind a bind mount, so a NAS folder mounted into Docker is detected too.
 * On systems without /proc (macOS, Windows) every path counts as local.
 */
@Slf4j
public final class MountInfo {

    public record Mount(String mountPoint, String fsType, String source) {
        public boolean isNetwork() {
            return NETWORK_TYPES.contains(fsType);
        }
    }

    private static final Path MOUNTINFO = Path.of("/proc/self/mountinfo");
    private static final long CACHE_MILLIS = 30_000;

    // Filesystems whose files live on another machine. FUSE filesystems are listed individually,
    // since many are local (mergerfs, unRAID's shfs).
    static final Set<String> NETWORK_TYPES = Set.of(
            "nfs", "nfs4", "cifs", "smb3", "smbfs", "9p", "ceph", "glusterfs", "afs", "lustre", "beegfs", "davfs",
            "fuse.glusterfs", "fuse.sshfs", "fuse.rclone", "fuse.s3fs", "fuse.gcsfuse", "fuse.goofys",
            "fuse.davfs2", "fuse.juicefs", "fuse.cephfs");

    private static volatile List<Mount> cached;
    private static volatile long cachedAt;

    private MountInfo() {
    }

    /** The mount holding {@code path}, if it can be worked out. */
    public static Optional<Mount> find(Path path) {
        return find(path, mounts());
    }

    /** True if {@code path} is on a network filesystem. */
    public static boolean isNetwork(Path path) {
        return find(path).map(Mount::isNetwork).orElse(false);
    }

    static Optional<Mount> find(Path path, List<Mount> mounts) {
        if (path == null || mounts.isEmpty()) {
            return Optional.empty();
        }
        Path target = realPath(path);
        Mount best = null;
        for (Mount mount : mounts) {
            Path mountPoint = Path.of(mount.mountPoint());
            // Later entries win at the same mount point: they're mounted on top of earlier ones.
            if (target.startsWith(mountPoint)
                    && (best == null || mountPoint.getNameCount() >= Path.of(best.mountPoint()).getNameCount())) {
                best = mount;
            }
        }
        return Optional.ofNullable(best);
    }

    // Symlinks are followed so a link into a share is recognised. A path that doesn't exist yet
    // is judged by its nearest existing parent.
    private static Path realPath(Path path) {
        Path current = path.toAbsolutePath().normalize();
        Path missing = null;
        while (current != null) {
            try {
                Path real = current.toRealPath();
                return missing == null ? real : real.resolve(missing);
            } catch (IOException e) {
                Path name = current.getFileName();
                missing = missing == null ? name : name.resolve(missing);
                current = current.getParent();
            }
        }
        return path.toAbsolutePath().normalize();
    }

    private static List<Mount> mounts() {
        long now = System.currentTimeMillis();
        List<Mount> current = cached;
        if (current != null && now - cachedAt < CACHE_MILLIS) {
            return current;
        }
        List<Mount> parsed = List.of();
        if (Files.isReadable(MOUNTINFO)) {
            try {
                parsed = parse(Files.readAllLines(MOUNTINFO));
            } catch (IOException e) {
                log.debug("Couldn't read {}: {}", MOUNTINFO, e.getMessage());
            }
        }
        cached = parsed;
        cachedAt = now;
        return parsed;
    }

    /**
     * Parses mountinfo lines: {@code id parent major:minor root mount-point options [optional...] - type source super-options}.
     */
    static List<Mount> parse(List<String> lines) {
        List<Mount> mounts = new ArrayList<>(lines.size());
        for (String line : lines) {
            String[] fields = line.split(" ");
            int separator = -1;
            for (int i = 6; i < fields.length; i++) {
                if ("-".equals(fields[i])) {
                    separator = i;
                    break;
                }
            }
            if (fields.length < 5 || separator < 0 || separator + 2 >= fields.length) {
                continue;
            }
            mounts.add(new Mount(unescape(fields[4]), fields[separator + 1], unescape(fields[separator + 2])));
        }
        return mounts;
    }

    // The kernel writes space, tab, newline and backslash in paths as octal escapes (\040 and so on).
    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && isOctal(value, i + 1) && isOctal(value, i + 2) && isOctal(value, i + 3)) {
                out.append((char) Integer.parseInt(value.substring(i + 1, i + 4), 8));
                i += 3;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isOctal(String value, int index) {
        return index < value.length() && value.charAt(index) >= '0' && value.charAt(index) <= '7';
    }
}
