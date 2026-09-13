package org.booklore.service.system;

import org.booklore.config.AppProperties;
import org.booklore.exception.APIException;
import org.booklore.model.dto.SelfUpdateStatus;
import org.booklore.model.dto.VersionInfo;
import org.booklore.model.enums.AuditAction;
import org.booklore.service.VersionService;
import org.booklore.service.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemUpdateServiceTest {

    @Mock private VersionService versionService;
    @Mock private AuditService auditService;
    @Mock private ProcessLauncher processLauncher;

    @TempDir Path repoDir;
    @TempDir Path configDir;

    private AppProperties appProperties;
    private SystemUpdateService service;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(repoDir.resolve("scripts"));
        Files.writeString(repoDir.resolve("scripts/self-update.sh"), "#!/usr/bin/env bash\n");

        appProperties = new AppProperties();
        appProperties.setRepoDir(repoDir.toString());
        appProperties.setPathConfig(configDir.toString());

        service = new SystemUpdateService(appProperties, versionService, auditService, processLauncher);
    }

    private void stubUpdateAvailable(boolean available) {
        lenient().when(versionService.getVersionInfo()).thenReturn(new VersionInfo("v1.0.0", "v1.1.0"));
        lenient().when(versionService.isNewerVersionAvailable()).thenReturn(available);
    }

    @Test
    void getStatus_reflectsVersionServiceAndSupport() {
        stubUpdateAvailable(true);
        when(processLauncher.canRestartService()).thenReturn(true);

        SelfUpdateStatus status = service.getStatus();

        assertThat(status.currentVersion()).isEqualTo("v1.0.0");
        assertThat(status.latestVersion()).isEqualTo("v1.1.0");
        assertThat(status.updateAvailable()).isTrue();
        assertThat(status.selfUpdateSupported()).isTrue();
        assertThat(status.inProgress()).isFalse();
    }

    @Test
    void triggerUpdate_happyPath_writesLockLaunchesAndAudits() throws Exception {
        stubUpdateAvailable(true);
        when(processLauncher.canRestartService()).thenReturn(true);

        service.triggerUpdate();

        Path lock = configDir.resolve("update/update.lock");
        assertThat(lock).exists();
        verify(processLauncher).launchDetached(
                argThat(cmd -> cmd.get(0).equals("setsid") && cmd.get(cmd.size() - 1).equals(lock.toString())),
                any(File.class));
        verify(auditService).log(eq(AuditAction.SYSTEM_UPDATE_TRIGGERED), anyString());
    }

    @Test
    void triggerUpdate_throwsWhenNotSupported() {
        stubUpdateAvailable(true);
        when(processLauncher.canRestartService()).thenReturn(false);

        assertThatThrownBy(() -> service.triggerUpdate())
                .isInstanceOf(APIException.class)
                .hasMessageContaining("not available");
        verifyNoInteractions(auditService);
    }

    @Test
    void triggerUpdate_throwsWhenNoUpdateAvailable() {
        stubUpdateAvailable(false);
        when(processLauncher.canRestartService()).thenReturn(true);

        assertThatThrownBy(() -> service.triggerUpdate())
                .isInstanceOf(APIException.class)
                .hasMessageContaining("up to date");
    }

    @Test
    void triggerUpdate_throwsConflictWhenFreshLockExists() throws Exception {
        stubUpdateAvailable(true);
        when(processLauncher.canRestartService()).thenReturn(true);
        Path lock = configDir.resolve("update/update.lock");
        Files.createDirectories(lock.getParent());
        Files.writeString(lock, Instant.now().toString());

        assertThatThrownBy(() -> service.triggerUpdate())
                .isInstanceOf(APIException.class)
                .hasMessageContaining("already in progress");
        verify(processLauncher, never()).launchDetached(any(), any());
    }

    @Test
    void triggerUpdate_ignoresStaleLock() throws Exception {
        stubUpdateAvailable(true);
        when(processLauncher.canRestartService()).thenReturn(true);
        Path lock = configDir.resolve("update/update.lock");
        Files.createDirectories(lock.getParent());
        Files.writeString(lock, "old");
        Files.setLastModifiedTime(lock, java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(3600)));

        service.triggerUpdate();

        verify(processLauncher).launchDetached(any(), any());
    }

    @Test
    void repoDir_prefersTheConfiguredCheckout() {
        assertThat(service.repoDir()).isEqualTo(repoDir.toString());
    }

    @Test
    void repoDir_unset_usesTheCheckoutTheServiceRunsIn() {
        // Tests run from <checkout>/booklore-api, the same working directory the systemd units use.
        appProperties.setRepoDir("");
        Path workingDir = Path.of(System.getProperty("user.dir")).toAbsolutePath();

        assertThat(workingDir.getFileName().toString()).isEqualTo("booklore-api");
        assertThat(service.repoDir()).isEqualTo(workingDir.getParent().toString());
    }
}
