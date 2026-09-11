package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.ShelfEntity;
import org.booklore.service.task.TaskCronService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;



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
@Import(BookOpdsRepositoryDataJpaTest.TestConfig.class)
public class BookOpdsRepositoryDataJpaTest {

    @Autowired
    private BookOpdsRepository bookOpdsRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @org.springframework.boot.test.context.TestConfiguration
    public static class TestConfig {
        @Bean("flyway")
        @Primary
        public org.flywaydb.core.Flyway flyway() {
            return mock(org.flywaydb.core.Flyway.class);
        }

        @Bean
        @Primary
        public TaskCronService taskCronService() {
            return mock(TaskCronService.class);
        }
    }

    @Test
    void contextLoads() {
        assertThat(bookOpdsRepository).isNotNull();
    }

    @Test
    void findAllWithMetadataByIds_executesAgainstJpaMetamodel() {
        LibraryEntity library = LibraryEntity.builder()
                .name("Test Library")
                .icon("book")
                .watch(false)
                .build();
        entityManager.persist(library);
        entityManager.flush();

        LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                .library(library)
                .path("/test/path")
                .build();
        entityManager.persist(libraryPath);
        entityManager.flush();

        BookEntity book = BookEntity.builder()
                .library(library)
                .libraryPath(libraryPath)
                .addedOn(Instant.now())
                .deleted(false)
                .build();
        entityManager.persist(book);
        entityManager.flush();

        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(book)
                .bookId(book.getId())
                .title("Test Title")
                .build();
        entityManager.persist(metadata);
        entityManager.flush();

        List<BookEntity> result = bookOpdsRepository.findAllWithMetadataByIds(List.of(book.getId()));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(book.getId());
    }

    // Regression coverage for the DISTINCT/ORDER BY bug: "SELECT DISTINCT x ... ORDER BY y"
    // where y isn't in the select list is a hard error on PostgreSQL (42601), though H2
    // (used here) tolerates it - which is exactly how it slipped through before. These tests
    // exercise the actual query execution path so a future edit that reintroduces the pattern
    // at least breaks something, even though H2 alone can't catch the Postgres-specific error.

    private LibraryEntity persistLibrary(String name) {
        LibraryEntity library = LibraryEntity.builder()
                .name(name)
                .icon("book")
                .watch(false)
                .build();
        entityManager.persist(library);
        entityManager.flush();
        return library;
    }

    private BookEntity persistBook(LibraryEntity library) {
        LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                .library(library)
                .path("/test/path/" + library.getId() + "/" + System.nanoTime())
                .build();
        entityManager.persist(libraryPath);
        entityManager.flush();

        BookEntity book = BookEntity.builder()
                .library(library)
                .libraryPath(libraryPath)
                .addedOn(Instant.now())
                .deleted(false)
                .build();
        entityManager.persist(book);
        entityManager.flush();
        return book;
    }

    private BookMetadataEntity persistMetadata(BookEntity book, String title, String seriesName, Float seriesNumber) {
        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(book)
                .bookId(book.getId())
                .title(title)
                .seriesName(seriesName)
                .seriesNumber(seriesNumber)
                .build();
        entityManager.persist(metadata);
        entityManager.flush();
        return metadata;
    }

    @Test
    void findBookIdsBySeriesName_ordersBySeriesNumber_withoutDistinctOrderByConflict() {
        LibraryEntity library = persistLibrary("Series Library");

        BookEntity book1 = persistBook(library);
        persistMetadata(book1, "Book One", "My Series", 1f);
        BookEntity book2 = persistBook(library);
        persistMetadata(book2, "Book Two", "My Series", 2f);
        BookEntity otherSeriesBook = persistBook(library);
        persistMetadata(otherSeriesBook, "Other", "Different Series", 1f);

        Page<Long> result = bookOpdsRepository.findBookIdsBySeriesName("My Series", PageRequest.of(0, 10));

        assertThat(result.getContent()).containsExactly(book1.getId(), book2.getId());
    }

    @Test
    void findBookIdsByAuthorName_dedupesAndExecutes_withoutDistinctOrderByConflict() {
        LibraryEntity library = persistLibrary("Author Library");
        BookEntity book = persistBook(library);
        BookMetadataEntity metadata = persistMetadata(book, "Authored Book", null, null);

        AuthorEntity author = AuthorEntity.builder().name("Jane Doe").build();
        entityManager.persist(author);
        entityManager.flush();

        metadata.setAuthors(new java.util.ArrayList<>(List.of(author)));
        entityManager.merge(metadata);
        entityManager.flush();
        entityManager.clear();

        Page<Long> result = bookOpdsRepository.findBookIdsByAuthorName("Jane Doe", PageRequest.of(0, 10));

        assertThat(result.getContent()).containsExactly(book.getId());
    }

    @Test
    void findBookIdsByShelfIds_dedupesBookOnMultipleMatchingShelves_withoutDistinctOrderByConflict() {
        LibraryEntity library = persistLibrary("Shelf Library");
        BookEntity book = persistBook(library);
        persistMetadata(book, "Shelved Book", null, null);

        BookLoreUserEntity user = BookLoreUserEntity.builder()
                .username("shelfuser")
                .passwordHash("hash")
                .isDefaultPassword(false)
                .name("Shelf User")
                .createdAt(LocalDateTime.now())
                .build();
        entityManager.persist(user);
        entityManager.flush();

        ShelfEntity shelfA = ShelfEntity.builder().user(user).name("Shelf A").build();
        ShelfEntity shelfB = ShelfEntity.builder().user(user).name("Shelf B").build();
        entityManager.persist(shelfA);
        entityManager.persist(shelfB);
        entityManager.flush();

        // Same book on both shelves - the join fans this out to two rows before dedup.
        book.setShelves(new java.util.HashSet<>(Set.of(shelfA, shelfB)));
        entityManager.merge(book);
        entityManager.flush();
        entityManager.clear();

        Page<Long> result = bookOpdsRepository.findBookIdsByShelfIds(
                Set.of(shelfA.getId(), shelfB.getId()), PageRequest.of(0, 10));

        assertThat(result.getContent()).containsExactly(book.getId());
    }
}