package org.booklore.service.reader;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.util.FileService;
import org.booklore.util.ToolBinaries;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@Slf4j
@Service
@AllArgsConstructor
public class FfprobeService {

    // Static builds from ffbinaries (github.com/ffbinaries/ffbinaries-prebuilt), zipped. Booklore fetched
    // the same ffprobe, unzipped, from booklore-app/booklore-tools, so binaries already cached under
    // these names are kept. There is no macOS arm64 build; Apple silicon runs the x64 one.
    private static final String FFPROBE_RELEASE_URL = "https://github.com/ffbinaries/ffbinaries-prebuilt/releases/download/v6.1/";

    private static final String BIN_DARWIN_X64 = "ffprobe-darwin-64";
    private static final String BIN_LINUX_X64 = "ffprobe-linux-64";
    private static final String BIN_LINUX_ARM64 = "ffprobe-linux-arm64";

    private static final Map<String, ToolBinaries.Asset> ASSETS = Map.of(
            BIN_DARWIN_X64, new ToolBinaries.Asset(FFPROBE_RELEASE_URL + "ffprobe-6.1-macos-64.zip",
                    "878ab8787ca6c48a11cb668c01d544be4c1bf655637d719cdf3b3179841545f2", "ffprobe"),
            BIN_LINUX_X64, new ToolBinaries.Asset(FFPROBE_RELEASE_URL + "ffprobe-6.1-linux-64.zip",
                    "cb690c360042b51d9e901db2b0185c585330c1067b5c5edf0b6a5e26e0375e2a", "ffprobe"),
            BIN_LINUX_ARM64, new ToolBinaries.Asset(FFPROBE_RELEASE_URL + "ffprobe-6.1-linux-arm-64.zip",
                    "c99358a90d93ada454e2f4dd26cd25718d36b4032b0ad5539425eac6e66349d8", "ffprobe"));

    private final FileService fileService;

    public Path getFfprobeBinary() {
        try {
            return setupFfprobeBinary();
        } catch (Exception e) {
            log.warn("Failed to set up ffprobe binary: {}", e.getMessage());
            return null;
        }
    }

    private Path setupFfprobeBinary() throws Exception {
        try {
            String binaryName = getFfprobeBinaryName();
            return ToolBinaries.ensureInstalled(Paths.get(fileService.getToolsFfprobePath()), binaryName, ASSETS.get(binaryName));
        } catch (IllegalStateException | IOException e) {
            Path onPath = ToolBinaries.findOnPath("ffprobe");
            if (onPath == null) {
                throw e;
            }
            log.warn("Couldn't install the pinned ffprobe ({}), using {} from the PATH", e.getMessage(), onPath);
            return onPath;
        }
    }

    private String getFfprobeBinaryName() {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();

        log.debug("Detected OS: {} ({})", osName, osArch);

        if (osName.contains("mac") || osName.contains("darwin")) {
            return BIN_DARWIN_X64;
        } else if (osName.contains("linux")) {
            if (osArch.contains("arm64") || osArch.contains("aarch64")) {
                return BIN_LINUX_ARM64;
            } else if (osArch.contains("64")) {
                return BIN_LINUX_X64;
            }
        }
        throw new IllegalStateException("Unsupported operating system or architecture: " + osName + " / " + osArch);
    }
}
