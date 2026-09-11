package org.booklore.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {
    private String pathConfig;
    private String bookdropFolder;
    private String version;
    private String repoDir;
    private RemoteAuth remoteAuth;
    private Boolean forceDisableOidc = false;
    private MetadataBrowser metadataBrowser = new MetadataBrowser();

    /**
     * Type of disk storage where library files are stored.
     * Defaults to LOCAL. Set to NETWORK if using NFS, SMB/CIFS, or other network-mounted storage.
     * Some features like file move/reorganization are disabled on network storage due to
     * unreliable atomic operations that can cause data corruption or loss.
     */
    private String diskType = "LOCAL";

    public boolean isLocalStorage() {
        return "LOCAL".equalsIgnoreCase(diskType);
    }

    @Getter
    @Setter
    public static class RemoteAuth {
        private boolean enabled;
        private boolean createNewUsers;
        private String headerName;
        private String headerUser;
        private String headerEmail;
        private String headerGroups;
        private String adminGroup;
        private String groupsDelimiter = "\\s+";  // Default to whitespace for backward compatibility
    }

    /**
     * Headless Chromium for Amazon/GoodReads pages that bot-check plain HTTP clients. When it
     * can't start (e.g. the musl-based Docker image) the parsers fall back to plain HTTP anyway;
     * disabling it just skips the attempt.
     */
    @Getter
    @Setter
    public static class MetadataBrowser {
        private boolean enabled = true;
    }
}
