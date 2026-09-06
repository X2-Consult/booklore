package org.booklore.service.migration.migrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.book.ExactFileDeduplicator;
import org.booklore.service.migration.Migration;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-time cleanup of book rows that already point at the identical file on disk (produced by
 * a pre-fix scan/watcher race), then promotes the book_file identity index (added non-unique
 * in Flyway V7) to a UNIQUE index so the race can never create them again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollapseExactFileDuplicatesMigration implements Migration {

    private final LibraryRepository libraryRepository;
    private final ExactFileDeduplicator exactFileDeduplicator;

    @Override
    public String getKey() {
        return "collapseExactFileDuplicatesAndUniqueIndexV1";
    }

    @Override
    public String getDescription() {
        return "Merge book rows sharing an identical file, then add the book_file identity UNIQUE index";
    }

    @Override
    public void execute() {
        List<Long> libraryIds = libraryRepository.findAll().stream().map(l -> l.getId()).toList();
        int totalGroups = 0;
        for (Long libraryId : libraryIds) {
            totalGroups += exactFileDeduplicator.collapseExactFileDuplicates(libraryId);
        }
        log.info("{}: collapsed {} exact-file duplicate group(s) across {} librar{}",
                getKey(), totalGroups, libraryIds.size(), libraryIds.size() == 1 ? "y" : "ies");

        // Best-effort in its own transaction: if it fails (residual duplicates, or a path
        // length past the btree limit) the migration still records as done - the end-of-scan
        // de-duplication and the insert-time constraint rollback keep duplicates out without it.
        // Re-run by deleting this row from app_migration.
        try {
            exactFileDeduplicator.createIdentityUniqueIndex();
            log.info("{}: book_file identity UNIQUE index is in place", getKey());
        } catch (RuntimeException e) {
            log.error("{}: could not create the book_file identity UNIQUE index - {}. "
                    + "Duplicates are still prevented by the scan-time checks; investigate and "
                    + "re-run by clearing this migration from app_migration.", getKey(), e.getMessage());
        }
    }
}
