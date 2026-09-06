package org.booklore.service.book;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookMergeServiceTest {

    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private BookMergeService service;

    @BeforeEach
    void setUp() {
        service = new BookMergeService(entityManager);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(any(String.class), any())).thenReturn(query);
        lenient().when(query.executeUpdate()).thenReturn(0);
    }

    @Test
    void noOpWhenSourcesAreEmptyOrOnlyTheTarget() {
        service.transferUserData(5L, List.of(5L));
        verifyNoInteractions(entityManager);
    }

    @Test
    void repointsEveryUserDataTableAndShelvesToTheTarget() {
        service.transferUserData(1L, List.of(2L, 3L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager, atLeastOnce()).createNativeQuery(sql.capture());
        List<String> statements = sql.getAllValues();

        // per-user tables that must be collision-guarded
        for (String table : List.of("user_book_progress", "pdf_annotations", "ebook_viewer_preference",
                "cbx_viewer_preference", "epub_viewer_preference", "new_pdf_viewer_preference",
                "pdf_viewer_preference", "book_marks", "book_notes_v2", "annotations")) {
            assertThat(statements).anyMatch(s ->
                    s.startsWith("UPDATE " + table + " SET book_id") && s.contains("NOT EXISTS"));
        }
        // book-scoped tables re-pointed wholesale
        for (String table : List.of("reading_sessions", "book_notes")) {
            assertThat(statements).anyMatch(s ->
                    s.startsWith("UPDATE " + table + " SET book_id") && !s.contains("NOT EXISTS"));
        }
        assertThat(statements).anyMatch(s ->
                s.contains("INSERT INTO book_shelf_mapping") && s.contains("ON CONFLICT DO NOTHING"));

        verify(query, atLeastOnce()).setParameter("target", 1L);
        verify(query, atLeastOnce()).setParameter("sources", List.of(2L, 3L));
        verify(entityManager).flush();
    }

    @Test
    void cfiTablesCorrelateOnCfiToo() {
        service.transferUserData(1L, List.of(2L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager, atLeastOnce()).createNativeQuery(sql.capture());
        assertThat(sql.getAllValues()).anyMatch(s ->
                s.startsWith("UPDATE annotations SET book_id") && s.contains("x.cfi = annotations.cfi"));
    }
}
