package org.booklore.service.koreader;

import org.booklore.config.security.userdetails.KoreaderUserDetails;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.progress.KoreaderProgress;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.KoreaderUserRepository;
import org.booklore.repository.UserBookFileProgressRepository;
import org.booklore.repository.UserBookProgressRepository;
import org.booklore.repository.UserRepository;
import org.booklore.service.hardcover.HardcoverSyncService;
import org.booklore.util.koreader.EpubCfiService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Slf4j
@AllArgsConstructor
@Service
public class KoreaderService {

    private static final Duration RECENCY_BUFFER = Duration.ofSeconds(5);

    private final UserBookProgressRepository progressRepository;
    private final UserBookFileProgressRepository fileProgressRepository;
    private final BookFileRepository bookFileRepository;
    private final UserRepository userRepository;
    private final KoreaderUserRepository koreaderUserRepository;
    private final HardcoverSyncService hardcoverSyncService;
    private final EpubCfiService epubCfiService;

    public ResponseEntity<Map<String, String>> authorizeUser() {
        // Consistent with getProgress/saveProgress: a KOReader client with sync
        // administratively disabled shouldn't be able to log in successfully and only then
        // discover every subsequent sync silently fails.
        KoreaderUserDetails authDetails = getAuthDetailsWithSyncCheck();
        KoreaderUserEntity koreaderUser = findKoreaderUser(authDetails.getUsername());
        validatePassword(koreaderUser, authDetails);

        log.info("User '{}' authorized", authDetails.getUsername());
        return ResponseEntity.ok(Map.of("username", authDetails.getUsername()));
    }

    public KoreaderProgress getProgress(String bookHash) {
        KoreaderUserDetails authDetails = getAuthDetailsWithSyncCheck();
        BookFileEntity bookFile = findBookFileByHash(bookHash);
        UserBookProgressEntity progress = findUserProgress(authDetails.getBookLoreUserId(), bookFile.getBook().getId());

        KoreaderProgress fromBookloreReader = authDetails.isSyncWithBookloreReader()
                ? tryProgressFromBookloreReader(bookHash, authDetails.getBookLoreUserId(), bookFile, progress)
                : null;
        if (fromBookloreReader != null) {
            log.info("getProgress: serving progress from the Trove reader (newer than last KOReader sync) for userId={} bookHash={}",
                    authDetails.getBookLoreUserId(), bookHash);
            return fromBookloreReader;
        }

        log.info("getProgress: fetched progress='{}' percentage={} for userId={} bookHash={}",
                progress.getKoreaderProgress(), progress.getKoreaderProgressPercent(),
                authDetails.getBookLoreUserId(), bookHash);

        Long timestamp = progress.getKoreaderLastSyncTime() != null
                ? progress.getKoreaderLastSyncTime().getEpochSecond()
                : null;

        return KoreaderProgress.builder()
                .timestamp(timestamp)
                .document(bookHash)
                .progress(progress.getKoreaderProgress())
                .percentage(progress.getKoreaderProgressPercent())
                .device("Trove")  // the name KOReader shows; it only compares device_id with its own, so the old value is harmless
                .device_id("BookLore")
                .build();
    }

    /**
     * When "sync with the Trove reader" is on, KOReader should pull whichever position is
     * more recent: its own last push, or progress made through Trove's own web reader
     * for this exact file. The two directions write into different columns (koreaderProgress
     * vs the file-level position table) with no shared source of truth, so without this,
     * GET /syncs/progress could only ever echo back what KOReader itself last pushed - a
     * user reading further in the web reader would never see that reflected on their device.
     *
     * A small recency buffer avoids a same-request round-trip: saveProgress() dual-writes
     * every KOReader push into the file-level table too, so immediately after a push the two
     * timestamps are only microseconds apart and a naive "most recent wins" comparison could
     * flip either way, sending KOReader back its own xpointer re-encoded as a CFI for no
     * reason.
     */
    private KoreaderProgress tryProgressFromBookloreReader(String bookHash, Long userId, BookFileEntity bookFile, UserBookProgressEntity progress) {
        UserBookFileProgressEntity fileProgress = fileProgressRepository
                .findByUserIdAndBookFileId(userId, bookFile.getId())
                .orElse(null);
        if (fileProgress == null || fileProgress.getLastReadTime() == null) {
            return null;
        }

        Instant koreaderLastSync = progress.getKoreaderLastSyncTime();
        boolean bookloreReaderIsNewer = koreaderLastSync == null
                || fileProgress.getLastReadTime().isAfter(koreaderLastSync.plus(RECENCY_BUFFER));
        if (!bookloreReaderIsNewer) {
            return null;
        }

        String koreaderStyleProgress = convertFileProgressToKoreaderFormat(bookFile, fileProgress);
        if (koreaderStyleProgress == null) {
            return null;
        }

        return KoreaderProgress.builder()
                .timestamp(fileProgress.getLastReadTime().getEpochSecond())
                .document(bookHash)
                .progress(koreaderStyleProgress)
                .percentage(fractionOf(fileProgress.getProgressPercent()))
                .device("Trove")  // the name KOReader shows; it only compares device_id with its own, so the old value is harmless
                .device_id("BookLore")
                .build();
    }

    /**
     * Mirror image of the conversion saveProgress() does on the way in: EPUB-family formats
     * store a CFI in the file-progress table, which needs converting back to KOReader's own
     * xpointer format; PDF/CBX already store the bare page number KOReader itself sends, so
     * no conversion is needed.
     */
    private String convertFileProgressToKoreaderFormat(BookFileEntity bookFile, UserBookFileProgressEntity fileProgress) {
        if (fileProgress.getPositionData() == null) {
            return null;
        }
        try {
            return switch (bookFile.getBookType()) {
                case EPUB, FB2, MOBI, AZW3 ->
                        epubCfiService.convertCfiToProgressXPointer(bookFile.getFullFilePath(), fileProgress.getPositionData());
                case PDF, CBX -> fileProgress.getPositionData();
                default -> null;
            };
        } catch (Exception e) {
            log.warn("Failed to convert CFI to xpointer for KOReader pull (file {}): {}", bookFile.getId(), e.getMessage());
            return null;
        }
    }

    private Float fractionOf(Float percent) {
        return percent != null ? percent / 100f : null;
    }

    /**
     * @return the resulting koreaderLastSyncTime after this call - either newly set, or the
     * pre-existing one if the push was ignored as stale - so the controller can echo it back
     * the way the reference KOSync server's PUT /syncs/progress response does.
     */
    public Instant saveProgress(String bookHash, KoreaderProgress koProgress) {
        KoreaderUserDetails authDetails = getAuthDetailsWithSyncCheck();
        // Resolve the specific file the hash matched - a book can have multiple formats
        // (e.g. a primary EPUB plus an alternate PDF), and the hash may belong to any of
        // them, not necessarily the book's primary file.
        BookFileEntity bookFile = findBookFileByHash(bookHash);
        BookEntity book = bookFile.getBook();
        BookLoreUserEntity user = findBookLoreUser(authDetails.getBookLoreUserId());

        UserBookProgressEntity userProgress = getOrCreateUserProgress(user, book);

        if (isStalePush(koProgress, userProgress)) {
            // A push whose own clock predates the last recorded sync is most likely a
            // second device catching up after being offline - honor whichever position was
            // recorded later instead of blindly overwriting it with stale data.
            log.info("saveProgress: ignoring stale push (client timestamp={} predates last sync={}) for userId={} bookHash={}",
                    koProgress.getTimestamp(), userProgress.getKoreaderLastSyncTime(), authDetails.getBookLoreUserId(), bookHash);
            return userProgress.getKoreaderLastSyncTime();
        }

        Float previousProgressPercent = userProgress.getKoreaderProgressPercent();
        ReadStatus previousReadStatus = userProgress.getReadStatus();
        updateProgressData(userProgress, koProgress, authDetails.isSyncWithBookloreReader(), bookFile);

        progressRepository.save(userProgress);

        // Also save to file-level progress table (dual-write) - against the file that
        // actually matched the pushed hash, not just the book's primary file.
        saveToFileProgress(user, bookFile, userProgress, koProgress);

        log.info("saveProgress: saved progress='{}' percentage={} for userId={} bookHash={}", koProgress.getProgress(), koProgress.getPercentage(), authDetails.getBookLoreUserId(), bookHash);

        // Sync progress to Hardcover asynchronously (if enabled for this user)
        // But only if the progress percentage has changed from last time, or the read status has changed
        if (koProgress.getPercentage() != null && (!koProgress.getPercentage().equals(previousProgressPercent)
                || userProgress.getReadStatus() != previousReadStatus)) {
            Float progressPercent = normalizeProgressPercent(koProgress.getPercentage());
            hardcoverSyncService.syncProgressToHardcover(book.getId(), progressPercent, authDetails.getBookLoreUserId());
        }

        return userProgress.getKoreaderLastSyncTime();
    }

    private boolean isStalePush(KoreaderProgress koProgress, UserBookProgressEntity userProgress) {
        if (koProgress.getTimestamp() == null || userProgress.getKoreaderLastSyncTime() == null) {
            return false;
        }
        return koProgress.getTimestamp() < userProgress.getKoreaderLastSyncTime().getEpochSecond();
    }

    private void saveToFileProgress(BookLoreUserEntity user, BookFileEntity bookFile, UserBookProgressEntity progress, KoreaderProgress koProgress) {
        try {
            UserBookFileProgressEntity fileProgress = fileProgressRepository
                    .findByUserIdAndBookFileId(user.getId(), bookFile.getId())
                    .orElseGet(UserBookFileProgressEntity::new);

            fileProgress.setUser(user);
            fileProgress.setBookFile(bookFile);
            fileProgress.setLastReadTime(progress.getLastReadTime());
            fileProgress.setProgressPercent(normalizeProgressPercent(koProgress.getPercentage()));

            // Map position data based on the matched file's own type, not the book's primary
            // file. KOReader's own position format differs per type, so each is handled on
            // its own terms rather than reusing legacy book-level fields that KOReader never
            // populates:
            switch (bookFile.getBookType()) {
                case EPUB, FB2, MOBI, AZW3 -> {
                    // Only a converted CFI (set above in updateProgressData, when "sync with
                    // BookLore reader" is on) is safe to store here - BookLore's web reader
                    // expects a CFI, and KOReader's own xpointer isn't one. When no
                    // conversion happened, leave any existing position/href untouched rather
                    // than clobbering it with null.
                    if (progress.getEpubProgress() != null) {
                        fileProgress.setPositionData(progress.getEpubProgress());
                        fileProgress.setPositionHref(progress.getEpubProgressHref());
                    }
                }
                case PDF, CBX ->
                        // KOReader sends the page number itself as the "progress" string for
                        // these page-based formats - no conversion needed.
                        fileProgress.setPositionData(koProgress.getProgress());
            }

            fileProgressRepository.save(fileProgress);
        } catch (Exception e) {
            log.warn("Failed to save file-level progress for book file {}: {}", bookFile.getId(), e.getMessage());
        }
    }

    private void updateProgressData(UserBookProgressEntity userProgress, KoreaderProgress koProgress, boolean syncWithBookloreReader, BookFileEntity bookFile) {
        userProgress.setKoreaderProgress(koProgress.getProgress());
        userProgress.setKoreaderProgressPercent(koProgress.getPercentage());
        userProgress.setKoreaderDevice(koProgress.getDevice());
        userProgress.setKoreaderDeviceId(koProgress.getDevice_id());
        userProgress.setKoreaderLastSyncTime(Instant.now());
        userProgress.setLastReadTime(Instant.now());
        if (syncWithBookloreReader && koProgress.getProgress() != null && isCfiEligible(bookFile.getBookType())) {
            try {
                String cfi = epubCfiService.convertXPointerToCfi(bookFile.getFullFilePath(), koProgress.getProgress());

                float percent = koProgress.getPercentage() * 100f;
                float rounded = BigDecimal
                        .valueOf(percent)
                        .setScale(1, RoundingMode.HALF_UP)
                        .floatValue();

                userProgress.setEpubProgress(cfi);
                userProgress.setEpubProgressPercent(rounded);

                log.info("Converted xpointer to CFI for Trove reader sync: {}", cfi);
            } catch (Exception e) {
                log.warn("Failed to convert xpointer to CFI: {}", e.getMessage());
            }
        }

        updateReadStatus(userProgress, koProgress.getPercentage());
    }

    private boolean isCfiEligible(BookFileType type) {
        return type == BookFileType.EPUB || type == BookFileType.FB2
                || type == BookFileType.MOBI || type == BookFileType.AZW3;
    }

    private void updateReadStatus(UserBookProgressEntity userProgress, Float progressFraction) {
        if (progressFraction == null) {
            return;
        }
        double progressPercent = progressFraction * 100.0;
        if (progressPercent >= 99.5) {
            userProgress.setReadStatus(ReadStatus.READ);
            userProgress.setDateFinished(Instant.now());
        } else if (progressPercent >= 0.25) {
            userProgress.setReadStatus(ReadStatus.READING);
        } else {
            userProgress.setReadStatus(ReadStatus.UNREAD);
        }
    }

    private Float normalizeProgressPercent(Float progress) {
        if (progress == null) {
            return null;
        }
        if (progress <= 1.0f) {
            return progress * 100.0f;
        }
        return progress;
    }

    private KoreaderUserDetails getAuthDetails() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof KoreaderUserDetails details)) {
            log.warn("Authentication failed: invalid principal type");
            throw ApiError.GENERIC_UNAUTHORIZED.createException("User not authenticated");
        }
        return details;
    }

    private KoreaderUserDetails getAuthDetailsWithSyncCheck() {
        KoreaderUserDetails authDetails = getAuthDetails();
        ensureSyncEnabled(authDetails);
        return authDetails;
    }

    private KoreaderUserEntity findKoreaderUser(String username) {
        return koreaderUserRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.warn("KOReader user '{}' not found", username);
                    return ApiError.GENERIC_NOT_FOUND.createException("KOReader user not found");
                });
    }

    private void validatePassword(KoreaderUserEntity koreaderUser, KoreaderUserDetails authDetails) {
        if (!KoreaderPasswords.md5Matches(koreaderUser.getPasswordMD5(), authDetails.getPassword())) {
            log.warn("Password mismatch for user '{}'", authDetails.getUsername());
            throw ApiError.GENERIC_UNAUTHORIZED.createException("Invalid credentials");
        }
    }

    private BookFileEntity findBookFileByHash(String bookHash) {
        return bookFileRepository.findByCurrentHashAndIsBookFormatTrue(bookHash)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Book not found for hash " + bookHash));
    }

    private BookLoreUserEntity findBookLoreUser(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("User not found with id " + userId));
    }

    private UserBookProgressEntity findUserProgress(long userId, Long bookId) {
        return progressRepository.findByUserIdAndBookId(userId, bookId)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("No progress found for user and book"));
    }

    private UserBookProgressEntity getOrCreateUserProgress(BookLoreUserEntity user, BookEntity book) {
        return progressRepository.findByUserIdAndBookId(user.getId(), book.getId())
                .orElseGet(() -> {
                    UserBookProgressEntity newProgress = new UserBookProgressEntity();
                    newProgress.setUser(user);
                    newProgress.setBook(book);
                    return newProgress;
                });
    }

    private void ensureSyncEnabled(KoreaderUserDetails details) {
        if (!details.isSyncEnabled()) {
            log.warn("Sync is disabled for user '{}'", details.getUsername());
            throw ApiError.GENERIC_UNAUTHORIZED.createException("Sync is disabled for this user");
        }
    }
}
