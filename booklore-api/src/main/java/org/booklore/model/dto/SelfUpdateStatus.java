package org.booklore.model.dto;

public record SelfUpdateStatus(
        String currentVersion,
        String latestVersion,
        boolean updateAvailable,
        boolean selfUpdateSupported,
        boolean inProgress
) {
}
