package org.booklore.service.book;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.repository.BookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collapses book rows that point at the <em>identical file on disk</em>
 * ({@code library_path_id} + {@code file_sub_path} + {@code file_name} of the primary file).
 * These are unambiguously the same book - produced by a scan/watcher race - so they are merged
 * automatically at the end of every library scan, preserving reading data via
 * {@link BookMergeService}. Books with distinct filenames/paths are never touched.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExactFileDeduplicator {

    private final BookRepository bookRepository;
    private final BookMergeService bookMergeService;
    private final EntityManager entityManager;

    private record FileIdentity(Long libraryPathId, String fileSubPath, String fileName) {}

    /**
     * Promotes the non-unique {@code book_file__idx_identity} (Flyway V7) to a UNIQUE index once
     * duplicates have been collapsed. Own transaction so a failure here doesn't poison the
     * caller's (the one-time migration records itself regardless). Unquoted identifiers so the
     * folded case matches whichever engine built the schema.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createIdentityUniqueIndex() {
        entityManager.createNativeQuery(
                "CREATE UNIQUE INDEX IF NOT EXISTS book_file__uq_identity "
                + "ON book_file (library_path_id, file_sub_path, file_name)").executeUpdate();
        entityManager.createNativeQuery("DROP INDEX IF EXISTS book_file__idx_identity").executeUpdate();
    }

    /**
     * Runs in its own transaction so callers (post-scan hook, one-time migration) that loop
     * over libraries commit each library's cleanup independently.
     *
     * @return number of duplicate groups collapsed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int collapseExactFileDuplicates(long libraryId) {
        List<BookEntity> books = bookRepository.findAllWithFilesByLibraryId(libraryId);

        Map<FileIdentity, List<BookEntity>> byIdentity = new LinkedHashMap<>();
        for (BookEntity book : books) {
            BookFileEntity primary = book.getPrimaryBookFile();
            if (primary == null || primary.getLibraryPathId() == null
                    || primary.getFileSubPath() == null || primary.getFileName() == null) {
                continue;
            }
            byIdentity.computeIfAbsent(
                    new FileIdentity(primary.getLibraryPathId(), primary.getFileSubPath(), primary.getFileName()),
                    k -> new ArrayList<>()).add(book);
        }

        int collapsed = 0;
        for (Map.Entry<FileIdentity, List<BookEntity>> entry : byIdentity.entrySet()) {
            List<BookEntity> group = entry.getValue();
            if (group.size() < 2) {
                continue;
            }
            mergeGroup(entry.getKey(), group);
            collapsed++;
        }
        return collapsed;
    }

    private void mergeGroup(FileIdentity identity, List<BookEntity> group) {
        Set<Long> withProgress = booksWithReadingProgress(group.stream().map(BookEntity::getId).toList());
        BookEntity survivor = group.stream()
                .min(Comparator
                        .comparing((BookEntity b) -> !withProgress.contains(b.getId())) // has-progress first
                        .thenComparingLong(BookEntity::getId))
                .orElseThrow();

        List<Long> sourceIds = group.stream()
                .map(BookEntity::getId)
                .filter(id -> !id.equals(survivor.getId()))
                .toList();

        bookMergeService.transferUserData(survivor.getId(), sourceIds);

        entityManager.createNativeQuery(
                        "UPDATE book_file SET book_id = :target, library_path_id = :lp WHERE book_id IN (:sources)")
                .setParameter("target", survivor.getId())
                .setParameter("lp", identity.libraryPathId())
                .setParameter("sources", sourceIds)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        bookRepository.deleteAll(bookRepository.findAllById(sourceIds));

        log.info("Collapsed {} duplicate book row(s) for '{}/{}' into book {}",
                sourceIds.size(), identity.fileSubPath(), identity.fileName(), survivor.getId());
    }

    @SuppressWarnings("unchecked")
    private Set<Long> booksWithReadingProgress(List<Long> bookIds) {
        List<Number> rows = entityManager.createNativeQuery(
                        "SELECT DISTINCT book_id FROM user_book_progress WHERE book_id IN (:ids)")
                .setParameter("ids", bookIds)
                .getResultList();
        Set<Long> result = new HashSet<>();
        for (Number n : rows) {
            result.add(n.longValue());
        }
        return result;
    }
}
