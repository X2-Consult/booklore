package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.AuditAction;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.audit.AuditService;
import org.booklore.task.TaskStatus;
import org.booklore.util.ArchiveUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Verifies every EPUB/CBZ file is a structurally sound zip archive by reading each entry
 * fully and letting java.util.zip validate its CRC as it goes - the same depth of check as
 * `unzip -t`, well beyond just confirming the file starts with a valid zip header. A file can
 * pass a shallow header check while still being corrupted deeper in the archive (see the
 * Diddly Squat / Camping With Her Step Dad incidents this task exists to catch earlier).
 * Read-only: never modifies a file, only reports.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EbookIntegrityCheckTask implements Task {

    // CBX covers cbz/cbr/cb7, but only cbz is actually a zip archive - filtered on archiveType below.
    private static final List<BookFileType> CANDIDATE_TYPES = List.of(BookFileType.EPUB, BookFileType.CBX);
    private static final int BUFFER_SIZE = 64 * 1024;

    private final BookFileRepository bookFileRepository;
    private final AuditService auditService;

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        if (!UserPermission.CAN_ACCESS_TASK_MANAGER.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_ACCESS_TASK_MANAGER);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        TaskCreateResponse.TaskCreateResponseBuilder builder = TaskCreateResponse.builder()
                .taskId(UUID.randomUUID().toString())
                .taskType(getTaskType());

        long startTime = System.currentTimeMillis();
        log.info("{}: Task started", getTaskType());

        int checked = 0;
        int bad = 0;

        try {
            List<BookFileEntity> files = bookFileRepository.findAllWithBookAndLibraryPathByBookTypeIn(CANDIDATE_TYPES);
            for (BookFileEntity bookFile : files) {
                if (bookFile.getBookType() == BookFileType.CBX && bookFile.getArchiveType() != ArchiveUtils.ArchiveType.ZIP) {
                    continue;
                }
                checked++;
                String failureReason = checkIntegrity(bookFile);
                if (failureReason != null) {
                    bad++;
                    reportBadFile(bookFile, failureReason);
                }
            }
            log.info("{}: Checked {} file(s), found {} corrupt", getTaskType(), checked, bad);
            builder.status(TaskStatus.COMPLETED);
        } catch (Exception e) {
            log.error("{}: Error during integrity check (checked {} before failing)", getTaskType(), checked, e);
            builder.status(TaskStatus.FAILED);
        }

        long endTime = System.currentTimeMillis();
        log.info("{}: Task completed. Duration: {} ms", getTaskType(), endTime - startTime);

        return builder.build();
    }

    /**
     * @return null if the file is intact, otherwise a short description of what went wrong.
     */
    private String checkIntegrity(BookFileEntity bookFile) {
        File file;
        try {
            file = bookFile.getFullFilePath().toFile();
        } catch (Exception e) {
            return "could not resolve file path: " + e.getMessage();
        }

        if (!file.exists()) {
            return "file missing on disk";
        }

        try (ZipFile zipFile = new ZipFile(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                try (var in = zipFile.getInputStream(entry)) {
                    while (in.read(buffer) != -1) {
                        // Reading each entry fully forces CRC validation; java.util.zip
                        // throws a ZipException on mismatch as the bytes are consumed.
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
    }

    private void reportBadFile(BookFileEntity bookFile, String reason) {
        Long bookId = bookFile.getBook() != null ? bookFile.getBook().getId() : null;
        String path;
        try {
            path = bookFile.getFullFilePath().toString();
        } catch (Exception e) {
            path = bookFile.getFileName();
        }

        log.warn("{}: Corrupt file detected - bookId={} bookFileId={} path={} reason={}",
                getTaskType(), bookId, bookFile.getId(), path, reason);

        auditService.log(AuditAction.BOOK_FILE_INTEGRITY_FAILED, "Book", bookId,
                "Corrupt " + bookFile.getBookType() + " file, needs re-import: " + path + " (" + reason + ")");
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.CHECK_EBOOK_INTEGRITY;
    }
}
