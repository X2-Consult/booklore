package org.booklore.service.system;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Seam around OS process invocation so {@link SystemUpdateService} can be unit tested
 * without spawning real processes or requiring sudo.
 */
public interface ProcessLauncher {

    /**
     * Start {@code command} fully detached from this JVM (it must survive the JVM being
     * killed mid-update by the service restart) with stdout+stderr appended to {@code output}.
     * Does not wait for completion.
     */
    void launchDetached(List<String> command, File output) throws IOException;

    /**
     * True if this process can {@code sudo systemctl restart trove} (or {@code booklore-api} on an
     * install not yet migrated) without a password prompt, i.e. install.sh's sudoers drop-in is in place.
     */
    boolean canRestartService();
}
