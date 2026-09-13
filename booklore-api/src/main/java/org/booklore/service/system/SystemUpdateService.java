package org.booklore.service.system;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.SelfUpdateStatus;
import org.booklore.model.dto.VersionInfo;
import org.booklore.model.enums.AuditAction;
import org.booklore.service.VersionService;
import org.booklore.service.audit.AuditService;
import org.booklore.util.EnvVars;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Runs an in-place update of a native (non-Docker) install by shelling out to
 * {@code scripts/self-update.sh}, which pulls the current branch, rebuilds, and restarts
 * the systemd service. The API process is killed by that restart, so the script is spawned
 * fully detached and progress is observed by the client polling {@code /api/v1/healthcheck}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemUpdateService {

    private static final Duration LOCK_STALE_AFTER = Duration.ofMinutes(15);
    private static final Path DOCKER_MARKER = Path.of("/.dockerenv");
    private static final Path SELF_UPDATE_SCRIPT = Path.of("scripts", "self-update.sh");
    // Checkout locations to try when TROVE_REPO_DIR isn't set and the working directory doesn't tell.
    private static final List<String> DEFAULT_REPO_DIRS = List.of("/opt/trove", "/opt/booklore");

    private final AppProperties appProperties;
    private final VersionService versionService;
    private final AuditService auditService;
    private final ProcessLauncher processLauncher;

    public SelfUpdateStatus getStatus() {
        VersionInfo info = versionService.getVersionInfo();
        return new SelfUpdateStatus(
                info.getCurrent(),
                info.getLatest(),
                versionService.isNewerVersionAvailable(),
                isSelfUpdateSupported(),
                isInProgress());
    }

    public synchronized void triggerUpdate() {
        if (!isSelfUpdateSupported()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("In-app updates are not available on this deployment.");
        }
        if (!versionService.isNewerVersionAvailable()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Trove is already up to date.");
        }
        if (isInProgress()) {
            throw ApiError.CONFLICT.createException("An update is already in progress.");
        }

        Path lock = lockFile();
        try {
            Files.createDirectories(lock.getParent());
            Files.writeString(lock, Instant.now().toString(), StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            throw ApiError.CONFLICT.createException("An update is already in progress.");
        } catch (IOException e) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Could not start the update: " + e.getMessage());
        }

        try {
            Path script = Path.of(repoDir()).resolve(SELF_UPDATE_SCRIPT);
            processLauncher.launchDetached(
                    List.of("setsid", "bash", script.toString(), lock.toString()),
                    lock.getParent().resolve("update.log").toFile());
        } catch (Exception e) {
            deleteQuietly(lock);
            throw ApiError.GENERIC_BAD_REQUEST.createException("Could not start the update: " + e.getMessage());
        }

        VersionInfo info = versionService.getVersionInfo();
        auditService.log(AuditAction.SYSTEM_UPDATE_TRIGGERED,
                "In-app update started (" + info.getCurrent() + " -> " + info.getLatest() + ")");
        log.info("In-app update started: {} -> {}", info.getCurrent(), info.getLatest());
    }

    boolean isSelfUpdateSupported() {
        if (Files.exists(DOCKER_MARKER)) {
            return false;
        }
        if ("false".equalsIgnoreCase(EnvVars.get("SELF_UPDATE"))) {
            return false;
        }
        String repoDir = repoDir();
        if (repoDir == null || !Files.isReadable(Path.of(repoDir).resolve(SELF_UPDATE_SCRIPT))) {
            return false;
        }
        return processLauncher.canRestartService();
    }

    /**
     * The checkout to update: TROVE_REPO_DIR (or BOOKLORE_REPO_DIR) if set; otherwise the parent of the
     * working directory when that is the checkout's booklore-api folder, as the systemd units set it;
     * otherwise /opt/trove, then /opt/booklore for installs not yet migrated. Null if none has the script.
     */
    String repoDir() {
        String configured = appProperties.getRepoDir();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        Path workingDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path parent = workingDir.getParent();
        if (parent != null && "booklore-api".equals(String.valueOf(workingDir.getFileName()))
                && Files.isReadable(parent.resolve(SELF_UPDATE_SCRIPT))) {
            return parent.toString();
        }
        return DEFAULT_REPO_DIRS.stream()
                .filter(dir -> Files.isReadable(Path.of(dir).resolve(SELF_UPDATE_SCRIPT)))
                .findFirst()
                .orElse(null);
    }

    private boolean isInProgress() {
        Path lock = lockFile();
        if (!Files.exists(lock)) {
            return false;
        }
        try {
            Instant modified = Files.getLastModifiedTime(lock).toInstant();
            if (Instant.now().isAfter(modified.plus(LOCK_STALE_AFTER))) {
                deleteQuietly(lock);
                return false;
            }
            return true;
        } catch (IOException e) {
            return true;
        }
    }

    private Path lockFile() {
        return Path.of(appProperties.getPathConfig(), "update", "update.lock");
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
