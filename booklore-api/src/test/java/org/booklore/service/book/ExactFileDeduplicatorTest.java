package org.booklore.service.book;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExactFileDeduplicatorTest {

    @Mock private BookRepository bookRepository;
    @Mock private BookMergeService bookMergeService;
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private ExactFileDeduplicator deduplicator;

    @BeforeEach
    void setUp() {
        deduplicator = new ExactFileDeduplicator(bookRepository, bookMergeService, entityManager);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(any(String.class), any())).thenReturn(query);
        lenient().when(query.executeUpdate()).thenReturn(0);
        lenient().when(query.getResultList()).thenReturn(List.of());
    }

    private BookEntity book(long id, long libraryPathId, String subPath, String fileName) {
        BookFileEntity file = BookFileEntity.builder()
                .id(id * 100)
                .libraryPathId(libraryPathId)
                .fileSubPath(subPath)
                .fileName(fileName)
                .isBookFormat(true)
                .bookType(BookFileType.EPUB)
                .build();
        BookEntity b = BookEntity.builder().id(id).bookFiles(new ArrayList<>(List.of(file))).build();
        file.setBook(b);
        return b;
    }

    @Test
    void collapsesBooksSharingAnIdenticalFile_keepingLowestId() {
        BookEntity keep = book(5, 1L, "fiction", "dune.epub");
        BookEntity dupe = book(9, 1L, "fiction", "dune.epub");
        when(bookRepository.findAllWithFilesByLibraryId(1L)).thenReturn(List.of(keep, dupe));
        when(bookRepository.findAllById(List.of(9L))).thenReturn(List.of(dupe));

        int collapsed = deduplicator.collapseExactFileDuplicates(1L);

        assertThat(collapsed).isEqualTo(1);
        verify(bookMergeService).transferUserData(5L, List.of(9L));
        verify(bookRepository).deleteAll(List.of(dupe));
    }

    @Test
    void keepsTheBookThatHasReadingProgress() {
        BookEntity lowId = book(3, 1L, "fic", "dune.epub");
        BookEntity readOne = book(8, 1L, "fic", "dune.epub");
        when(bookRepository.findAllWithFilesByLibraryId(1L)).thenReturn(List.of(lowId, readOne));
        when(bookRepository.findAllById(List.of(3L))).thenReturn(List.of(lowId));
        // book 8 has a user_book_progress row
        when(query.getResultList()).thenReturn(List.<Object>of(8L));

        deduplicator.collapseExactFileDuplicates(1L);

        verify(bookMergeService).transferUserData(8L, List.of(3L));
        verify(bookRepository).deleteAll(List.of(lowId));
    }

    @Test
    void leavesBooksWithDistinctFilesAlone() {
        when(bookRepository.findAllWithFilesByLibraryId(1L)).thenReturn(List.of(
                book(1, 1L, "fic", "dune.epub"),
                book(2, 1L, "fic", "dune.pdf"),       // different filename
                book(3, 1L, "scifi", "dune.epub"),    // different subpath
                book(4, 2L, "fic", "dune.epub")       // different library path
        ));

        int collapsed = deduplicator.collapseExactFileDuplicates(1L);

        assertThat(collapsed).isZero();
        verifyNoInteractions(bookMergeService);
        verify(bookRepository, never()).deleteAll(any());
    }

    @Test
    void repointsBookFileRowsToTheSurvivor() {
        BookEntity keep = book(5, 7L, "fiction", "dune.epub");
        BookEntity dupe = book(9, 7L, "fiction", "dune.epub");
        when(bookRepository.findAllWithFilesByLibraryId(1L)).thenReturn(List.of(keep, dupe));
        when(bookRepository.findAllById(List.of(9L))).thenReturn(List.of(dupe));

        deduplicator.collapseExactFileDuplicates(1L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager, atLeastOnce()).createNativeQuery(sql.capture());
        assertThat(sql.getAllValues()).anyMatch(s -> s.contains("UPDATE book_file SET book_id"));
        verify(query).setParameter("target", 5L);
        verify(query).setParameter("lp", 7L);
    }
}
