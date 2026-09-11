package org.booklore.service.metadata;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.ComicMetadata;
import org.booklore.model.entity.*;
import org.booklore.model.enums.ComicCreatorRole;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.repository.BookOpdsRepositoryDataJpaTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link BookMetadataUpdater#setBookMetadata} against a real database and reads back the
 * {@code comic_metadata_creator_mapping} rows. Unit tests with mocked repositories cannot catch
 * the regression this guards: replacing a role's creators only emptied the in-memory set, so the
 * old rows survived the flush and reappeared on the next load alongside the new ones.
 */
@SpringBootTest(classes = {
        BookloreApplication.class
})
@Transactional
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.path-config=build/tmp/test-config",
        "app.bookdrop-folder=build/tmp/test-bookdrop",
        "spring.main.allow-bean-definition-overriding=true",
        "spring.task.scheduling.enabled=false",
        "app.task.scan-library-cron=*/1 * * * * *",
        "app.task.process-bookdrop-cron=*/1 * * * * *",
        "app.features.oidc-enabled=false"
})
// Same config and TestConfig as the repository *DataJpaTest classes, so Spring's context cache
// shares one application context instead of booting another (the test JVM is near its heap cap).
@Import(BookOpdsRepositoryDataJpaTest.TestConfig.class)
class ComicCreatorUpdateIntegrationTest {

    @Autowired
    private BookMetadataUpdater bookMetadataUpdater;

    @PersistenceContext
    private EntityManager entityManager;

    private Long bookId;

    @BeforeEach
    void setUp() {
        LibraryEntity library = LibraryEntity.builder().name("Comic Library").icon("book").watch(false).build();
        entityManager.persist(library);
        LibraryPathEntity libraryPath = LibraryPathEntity.builder().library(library).path("/test/comics").build();
        entityManager.persist(libraryPath);
        BookEntity book = BookEntity.builder().library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(false).build();
        entityManager.persist(book);
        entityManager.flush();

        BookMetadataEntity metadata = BookMetadataEntity.builder().book(book).bookId(book.getId()).title("Comic").build();
        entityManager.persist(metadata);
        ComicMetadataEntity comic = ComicMetadataEntity.builder().bookId(book.getId()).bookMetadata(metadata).build();
        entityManager.persist(comic);
        persistCreator(comic, "Old Artist", ComicCreatorRole.PENCILLER);
        persistCreator(comic, "Scott Williams", ComicCreatorRole.INKER);
        entityManager.flush();
        entityManager.clear();

        bookId = book.getId();
    }

    // Before the orphanRemoval fix this stored [INKER:Scott Williams x2, PENCILLER:New Artist,
    // PENCILLER:Old Artist] - every save re-added each role's creators without deleting the old rows.
    @Test
    void replacingACreator_removesTheOldRow() {
        applyComicMetadata(ComicMetadata.builder()
                .pencillers(Set.of("New Artist"))
                .inkers(Set.of("Scott Williams"))
                .build(), MetadataReplaceMode.REPLACE_WHEN_PROVIDED);

        assertThat(storedCreators()).containsExactly("INKER:Scott Williams", "PENCILLER:New Artist");
    }

    @Test
    void clearingARole_underReplaceAll_removesItsRows() {
        applyComicMetadata(ComicMetadata.builder()
                .pencillers(Set.of())
                .inkers(Set.of("Scott Williams"))
                .build(), MetadataReplaceMode.REPLACE_ALL);

        assertThat(storedCreators()).containsExactly("INKER:Scott Williams");
    }

    private void persistCreator(ComicMetadataEntity comic, String name, ComicCreatorRole role) {
        ComicCreatorEntity creator = ComicCreatorEntity.builder().name(name).build();
        entityManager.persist(creator);
        entityManager.persist(ComicCreatorMappingEntity.builder().comicMetadata(comic).creator(creator).role(role).build());
    }

    private void applyComicMetadata(ComicMetadata comicMetadata, MetadataReplaceMode mode) {
        BookEntity book = entityManager.find(BookEntity.class, bookId);
        BookMetadata update = BookMetadata.builder().bookId(bookId).title("Comic").comicMetadata(comicMetadata).build();
        bookMetadataUpdater.setBookMetadata(MetadataUpdateContext.builder()
                .bookEntity(book)
                .metadataUpdateWrapper(MetadataUpdateWrapper.builder().metadata(update).build())
                .replaceMode(mode)
                .build());
        entityManager.flush();
        entityManager.clear();
    }

    @SuppressWarnings("unchecked")
    private List<String> storedCreators() {
        return entityManager.createNativeQuery("""
                        SELECT m.role || ':' || c.name FROM comic_metadata_creator_mapping m
                        JOIN comic_creator c ON c.id = m.creator_id
                        WHERE m.book_id = :bookId ORDER BY 1""")
                .setParameter("bookId", bookId)
                .getResultList();
    }
}
