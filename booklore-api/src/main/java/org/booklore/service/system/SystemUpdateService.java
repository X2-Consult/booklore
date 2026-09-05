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
            throw ApiError.GENERIC_BAD_REQUEST.createException("BookLore is already up to date.");
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
            Path script = Path.of(appProperties.getRepoDir(), "scripts", "self-update.sh");
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
        if ("false".equalsIgnoreCase(System.getenv("BOOKLORE_SELF_UPDATE"))) {
            return false;
        }
        String repoDir = appProperties.getRepoDir();
        if (repoDir == null || repoDir.isBlank()) {
            return false;
        }
        if (!Files.isReadable(Path.of(repoDir, "scripts", "self-update.sh"))) {
            return false;
        }
        return processLauncher.canRestartService();
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
