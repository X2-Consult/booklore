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
import org.booklore.util.BookFileIntegrity;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Checks every book file with {@link BookFileIntegrity}: each entry's checksum in EPUB/CBZ/CB7/CBR
 * archives, the end marker and page tree of PDFs, the box structure of M4B/M4A audiobooks and the
 * frames of MP3s - well beyond confirming the file starts with a valid header. A file can pass a
 * shallow header check while still being corrupted deeper in (see the Diddly Squat / Camping With
 * Her Step Dad incidents this task exists to catch earlier). Folder-based audiobooks have each file
 * checked. Formats with no deeper check (MOBI, AZW3) are skipped. Read-only: it only reports.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EbookIntegrityCheckTask implements Task {

    private static final List<BookFileType> CANDIDATE_TYPES = List.of(BookFileType.values());

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
                String failureReason = checkIntegrity(bookFile);
                if (failureReason == SKIPPED) {
                    continue;
                }
                checked++;
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

    private static final String SKIPPED = "skipped";

    /**
     * @return null if the file is intact, {@link #SKIPPED} if its format has no deeper check,
     * otherwise a short description of what went wrong.
     */
    private String checkIntegrity(BookFileEntity bookFile) {
        Path path;
        try {
            path = bookFile.getFullFilePath();
        } catch (Exception e) {
            return "could not resolve file path: " + e.getMessage();
        }

        if (!Files.exists(path)) {
            return "file missing on disk";
        }
        if (!Files.isDirectory(path)) {
            return BookFileIntegrity.isCheckable(path) ? BookFileIntegrity.check(path) : SKIPPED;
        }

        // Folder-based audiobook: check each file that has a deeper check.
        try (Stream<Path> files = Files.walk(path)) {
            List<Path> checkable = files.filter(Files::isRegularFile).filter(BookFileIntegrity::isCheckable).sorted().toList();
            if (checkable.isEmpty()) {
                return SKIPPED;
            }
            for (Path file : checkable) {
                String problem = BookFileIntegrity.check(file);
                if (problem != null) {
                    return path.relativize(file) + ": " + problem;
                }
            }
            return null;
        } catch (Exception e) {
            return "could not read folder: " + e.getMessage();
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
