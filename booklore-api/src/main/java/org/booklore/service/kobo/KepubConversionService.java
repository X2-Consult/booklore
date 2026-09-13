package org.booklore.service.kobo;

import org.booklore.util.FileService;
import org.booklore.util.ToolBinaries;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KepubConversionService {

    @Autowired
    private FileService fileService;

    // kepubify's own releases (github.com/pgaskin/kepubify). Booklore fetched byte-identical copies
    // from booklore-app/booklore-tools, so binaries already cached under these names are kept.
    private static final String KEPUBIFY_RELEASE_URL = "https://github.com/pgaskin/kepubify/releases/download/v4.0.4/";

    private static final String BIN_DARWIN_ARM64 = "kepubify-darwin-arm64";
    private static final String BIN_DARWIN_X64 = "kepubify-darwin-64bit";
    private static final String BIN_LINUX_X64 = "kepubify-linux-64bit";
    private static final String BIN_LINUX_X86 = "kepubify-linux-32bit";
    private static final String BIN_LINUX_ARM = "kepubify-linux-arm";
    private static final String BIN_LINUX_ARM64 = "kepubify-linux-arm64";

    private static final Map<String, String> SHA256 = Map.of(
            BIN_DARWIN_ARM64, "6467d44439ee899113c7f710b509ef9e5ce65e8df711c85192b9ea5b683594b7",
            BIN_DARWIN_X64, "851afab0b83ecaf11f6965c901483eed3e74a6b41a3ab0a68f7321bc48bac4a3",
            BIN_LINUX_X64, "37d7628d26c5c906f607f24b36f781f306075e7073a6fe7820a751bb60431fc5",
            BIN_LINUX_X86, "3365a848ce06d43fca8f1999eb69c6c8e0e20a56b6b8658a8466b9726adef0f5",
            BIN_LINUX_ARM, "07f23275c4e674093443f01a591aa0980b0b87dbb0a10986d5001e9d56b0e1e7",
            BIN_LINUX_ARM64, "5a15b8f6f6a96216c69330601bca29638cfee50f7bf48712795cff88ae2d03a3");

    public File convertEpubToKepub(File epubFile, File tempDir, boolean forceEnableHyphenation) throws IOException, InterruptedException {
        validateInputs(epubFile);

        Path kepubifyBinary = setupKepubifyBinary();
        File outputFile = executeKepubifyConversion(epubFile, tempDir, kepubifyBinary, forceEnableHyphenation);

        log.info("Successfully converted {} to {} (size: {} bytes)", epubFile.getName(), outputFile.getName(), outputFile.length());
        return outputFile;
    }

    private void validateInputs(File epubFile) {
        if (epubFile == null || !epubFile.isFile() || !epubFile.getName().endsWith(".epub")) {
            throw new IllegalArgumentException("Invalid EPUB file: " + epubFile);
        }
    }

    private Path setupKepubifyBinary() throws IOException {
        try {
            String binaryName = getKepubifyBinaryName();
            String url = KEPUBIFY_RELEASE_URL + binaryName;
            return ToolBinaries.ensureInstalled(Paths.get(fileService.getToolsKepubifyPath()), binaryName,
                    new ToolBinaries.Asset(url, SHA256.get(binaryName), null));
        } catch (IllegalStateException | IOException e) {
            Path onPath = ToolBinaries.findOnPath("kepubify");
            if (onPath == null) {
                throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
            }
            log.warn("Couldn't install the pinned kepubify ({}), using {} from the PATH", e.getMessage(), onPath);
            return onPath;
        }
    }

    private String getKepubifyBinaryName() {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();

        log.debug("Detected OS: {} ({})", osName, osArch);

        if (osName.contains("mac") || osName.contains("darwin")) {
            if (osArch.contains("arm") || osArch.contains("aarch64")) {
                return BIN_DARWIN_ARM64;
            } else {
                return BIN_DARWIN_X64;
            }
        } else if (osName.contains("linux")) {
            if (osArch.contains("arm64") || osArch.contains("aarch64")) {
                return BIN_LINUX_ARM64;
            } else if (osArch.contains("arm")) {
                return BIN_LINUX_ARM;
            } else if (osArch.contains("64")) {
                return BIN_LINUX_X64;
            } else if (osArch.contains("86")) {
                return BIN_LINUX_X86;
            }
        }
        throw new IllegalStateException("Unsupported operating system or architecture: " + osName + " / " + osArch);
    }

    private File executeKepubifyConversion(File epubFile, File tempDir, Path kepubifyBinary, boolean forceEnableHyphenation) throws IOException, InterruptedException {
        ProcessBuilder pb;

        if (forceEnableHyphenation)
            pb = new ProcessBuilder(kepubifyBinary.toAbsolutePath().toString(), "--hyphenate", "-o", tempDir.getAbsolutePath(), epubFile.getAbsolutePath());
        else
            pb = new ProcessBuilder(kepubifyBinary.toAbsolutePath().toString(), "-o", tempDir.getAbsolutePath(), epubFile.getAbsolutePath());

        pb.directory(tempDir);

        log.info("Starting kepubify conversion for {} -> output dir: {}", epubFile.getAbsolutePath(), tempDir.getAbsolutePath());

        Process process = pb.start();

        String output = readProcessOutput(process.getInputStream());
        String error = readProcessOutput(process.getErrorStream());

        int exitCode = process.waitFor();
        logProcessResults(exitCode, output, error);

        if (exitCode != 0) {
            throw new IOException(String.format("Kepubify conversion failed with exit code: %d. Error: %s", exitCode, error));
        }

        return findOutputFile(tempDir);
    }

    private String readProcessOutput(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("Error reading process output: {}", e.getMessage());
            return "";
        }
    }

    private void logProcessResults(int exitCode, String output, String error) {
        log.debug("Kepubify process exited with code {}", exitCode);
        if (!output.isEmpty()) {
            log.debug("Kepubify stdout: {}", output);
        }
        if (!error.isEmpty()) {
            log.error("Kepubify stderr: {}", error);
        }
    }

    private File findOutputFile(File tempDir) throws IOException {
        File[] kepubFiles = tempDir.listFiles((dir, name) -> name.endsWith(".kepub.epub"));
        if (kepubFiles == null || kepubFiles.length == 0) {
            throw new IOException("Kepubify conversion completed but no .kepub.epub file was created in: " + tempDir.getAbsolutePath());
        }
        return kepubFiles[0];
    }
}
