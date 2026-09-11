package org.booklore.service.book;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * Moves per-user reading data (progress, read status, ratings, bookmarks, notes, annotations,
 * viewer preferences) and shelf memberships from source books onto a target book, so that a
 * merge / duplicate-collapse no longer silently loses that data when the source rows are
 * cascade-deleted with the source book.
 *
 * <p>This only re-points the child rows - the caller is responsible for re-pointing
 * {@code book_file} rows and deleting the source books (both trigger the ON DELETE CASCADE
 * that cleans up whatever wasn't moved). Runs in the caller's transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookMergeService {

    private final EntityManager entityManager;

    /** Per-user child tables and the extra column (beyond user_id) that makes a row unique per book. */
    private record UserScopedTable(String table, String extraKeyColumn) {}

    private static final List<UserScopedTable> USER_SCOPED_TABLES = List.of(
            new UserScopedTable("user_book_progress", null),          // unique (user_id, book_id)
            new UserScopedTable("pdf_annotations", null),             // unique (user_id, book_id)
            new UserScopedTable("ebook_viewer_preference", null),     // unique (user_id, book_id)
            new UserScopedTable("cbx_viewer_preference", null),       // unique (user_id, book_id)
            new UserScopedTable("new_pdf_viewer_preference", null),   // unique (user_id, book_id)
            new UserScopedTable("pdf_viewer_preference", null),       // unique (user_id, book_id)
            new UserScopedTable("book_marks", "cfi"),                 // unique (user_id, book_id, cfi)
            new UserScopedTable("book_notes_v2", "cfi"),              // unique (user_id, book_id, cfi)
            new UserScopedTable("annotations", "cfi")                 // unique (user_id, book_id, cfi)
    );

    /** Book-scoped tables with no per-book uniqueness - re-point every row. */
    private static final List<String> REPOINT_ALL_TABLES = List.of("reading_sessions", "book_notes");

    public void transferUserData(long targetBookId, Collection<Long> sourceBookIds) {
        List<Long> sources = sourceBookIds.stream()
                .filter(id -> id != targetBookId)
                .distinct()
                .toList();
        if (sources.isEmpty()) {
            return;
        }

        for (UserScopedTable t : USER_SCOPED_TABLES) {
            String collisionCheck = "SELECT 1 FROM " + t.table() + " x"
                    + " WHERE x.book_id = :target"
                    + " AND x.user_id = " + t.table() + ".user_id"
                    + (t.extraKeyColumn() != null
                        ? " AND x." + t.extraKeyColumn() + " = " + t.table() + "." + t.extraKeyColumn()
                        : "");
            int moved = entityManager.createNativeQuery(
                            "UPDATE " + t.table() + " SET book_id = :target"
                            + " WHERE book_id IN (:sources) AND NOT EXISTS (" + collisionCheck + ")")
                    .setParameter("target", targetBookId)
                    .setParameter("sources", sources)
                    .executeUpdate();
            if (moved > 0) {
                log.debug("Merge into book {}: moved {} row(s) from {}", targetBookId, moved, t.table());
            }
        }

        for (String table : REPOINT_ALL_TABLES) {
            entityManager.createNativeQuery(
                            "UPDATE " + table + " SET book_id = :target WHERE book_id IN (:sources)")
                    .setParameter("target", targetBookId)
                    .setParameter("sources", sources)
                    .executeUpdate();
        }

        entityManager.createNativeQuery(
                        "INSERT INTO book_shelf_mapping (book_id, shelf_id) "
                        + "SELECT :target, shelf_id FROM book_shelf_mapping WHERE book_id IN (:sources) "
                        + "ON CONFLICT DO NOTHING")
                .setParameter("target", targetBookId)
                .setParameter("sources", sources)
                .executeUpdate();

        entityManager.flush();
    }
}
