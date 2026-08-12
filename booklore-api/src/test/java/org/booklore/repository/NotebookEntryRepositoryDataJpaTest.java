package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.AnnotationEntity;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms that Spring Data JPA actually appends {@code ORDER BY} to
 * {@link NotebookEntryRepository#findEntries} from the {@code Pageable}'s {@code Sort} at
 * runtime, even though the native query text itself has none. Two entries are persisted, then
 * their {@code created_at} timestamps are swapped via a direct UPDATE so DB scan/insertion
 * order and timestamp order disagree - only a real ORDER BY, not incidental row order, can
 * make this test pass.
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
@Import(BookOpdsRepositoryDataJpaTest.TestConfig.class)
class NotebookEntryRepositoryDataJpaTest {

    @Autowired
    private NotebookEntryRepository notebookEntryRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void findEntries_ordersByCreatedAt_fromPageableSort_notInsertionOrder() {
        LibraryEntity library = LibraryEntity.builder().name("Notebook Library").icon("book").watch(false).build();
        entityManager.persist(library);
        entityManager.flush();

        LibraryPathEntity libraryPath = LibraryPathEntity.builder().library(library).path("/test/notebook").build();
        entityManager.persist(libraryPath);
        entityManager.flush();

        BookEntity book = BookEntity.builder().library(library).libraryPath(libraryPath).addedOn(Instant.now()).deleted(false).build();
        entityManager.persist(book);
        entityManager.flush();

        BookMetadataEntity metadata = BookMetadataEntity.builder().book(book).bookId(book.getId()).title("Notebook Book").build();
        entityManager.persist(metadata);
        entityManager.flush();

        BookLoreUserEntity user = BookLoreUserEntity.builder()
                .username("notebookuser")
                .passwordHash("hash")
                .isDefaultPassword(false)
                .name("Notebook User")
                .createdAt(LocalDateTime.now())
                .build();
        entityManager.persist(user);
        entityManager.flush();

        AnnotationEntity insertedFirst = AnnotationEntity.builder().user(user).book(book).cfi("cfi-1").text("First inserted").build();
        entityManager.persist(insertedFirst);
        entityManager.flush();

        AnnotationEntity insertedSecond = AnnotationEntity.builder().user(user).book(book).cfi("cfi-2").text("Second inserted").build();
        entityManager.persist(insertedSecond);
        entityManager.flush();

        // Swap created_at so the row inserted first ends up with the later timestamp - only a
        // genuine ORDER BY on createdAt (not row/insertion order) can recover the right order.
        entityManager.createNativeQuery("UPDATE annotations SET created_at = :ts WHERE id = :id")
                .setParameter("ts", LocalDateTime.now().plusDays(1))
                .setParameter("id", insertedFirst.getId())
                .executeUpdate();
        entityManager.createNativeQuery("UPDATE annotations SET created_at = :ts WHERE id = :id")
                .setParameter("ts", LocalDateTime.now().minusDays(1))
                .setParameter("id", insertedSecond.getId())
                .executeUpdate();
        entityManager.clear();

        var page = notebookEntryRepository.findEntries(
                user.getId(), Set.of("HIGHLIGHT"), null, null,
                PageRequest.of(0, 10, Sort.by("createdAt").descending()));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getId()).isEqualTo(insertedFirst.getId());
        assertThat(page.getContent().get(1).getId()).isEqualTo(insertedSecond.getId());
    }
}
