package org.booklore.service.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
class SystemProcessLauncher implements ProcessLauncher {

    @Override
    public void launchDetached(List<String> command, File output) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(output));
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        // No waitFor(): the command is prefixed with `setsid`, so it runs in its own session
        // and outlives this JVM when self-update.sh restarts the service.
        pb.start();
        log.info("Launched detached update process: {}", String.join(" ", command));
    }

    @Override
    public boolean canRestartService() {
        try {
            Process p = new ProcessBuilder("sudo", "-n", "-l")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && out.contains("booklore-api");
        } catch (Exception e) {
            log.debug("sudo self-update probe failed: {}", e.getMessage());
            return false;
        }
    }
}
