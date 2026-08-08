package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.UserBookProgressEntity;
import org.booklore.model.enums.ReadStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// Deliberately mirrors BookOpdsRepositoryDataJpaTest's context configuration byte-for-byte
// (same @SpringBootTest classes, properties, and imported TestConfig) so Spring's test
// context cache reuses that already-booted application context instead of building a
// second one - two near-identical Spring Boot contexts alive at once is what pushed the
// full test-suite run over available heap in this sandbox.
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
class UserBookProgressRepositoryDataJpaTest {

    @Autowired
    private UserBookProgressRepository userBookProgressRepository;

    @PersistenceContext
    private EntityManager entityManager;

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
                .path("/test/path/" + library.getId())
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

    private BookLoreUserEntity persistUser(String username) {
        BookLoreUserEntity user = BookLoreUserEntity.builder()
                .username(username)
                .passwordHash("hash")
                .isDefaultPassword(false)
                .name(username)
                .createdAt(LocalDateTime.now())
                .build();
        entityManager.persist(user);
        entityManager.flush();
        return user;
    }

    private void persistProgress(BookLoreUserEntity user, BookEntity book, ReadStatus readStatus, Instant lastReadTime) {
        UserBookProgressEntity progress = UserBookProgressEntity.builder()
                .user(user)
                .book(book)
                .readStatus(readStatus)
                .lastReadTime(lastReadTime)
                .build();
        entityManager.persist(progress);
        entityManager.flush();
    }

    @Test
    void findContinueReadingBookIds_returnsOnlyReadingBooks_mostRecentFirst() {
        LibraryEntity library = persistLibrary("Lib A");
        BookLoreUserEntity user = persistUser("reader1");

        BookEntity readingOlder = persistBook(library);
        BookEntity readingNewer = persistBook(library);
        BookEntity unread = persistBook(library);
        BookEntity finished = persistBook(library);

        Instant now = Instant.now();
        persistProgress(user, readingOlder, ReadStatus.READING, now.minusSeconds(3600));
        persistProgress(user, readingNewer, ReadStatus.RE_READING, now);
        persistProgress(user, unread, ReadStatus.UNREAD, now);
        persistProgress(user, finished, ReadStatus.READ, now);

        Page<Long> result = userBookProgressRepository.findContinueReadingBookIds(
                user.getId(), List.of(ReadStatus.READING, ReadStatus.RE_READING), PageRequest.of(0, 10));

        assertThat(result.getContent()).containsExactly(readingNewer.getId(), readingOlder.getId());
    }

    @Test
    void findContinueReadingBookIdsByLibraryIds_excludesBooksOutsideGivenLibraries() {
        LibraryEntity accessibleLibrary = persistLibrary("Accessible");
        LibraryEntity restrictedLibrary = persistLibrary("Restricted");
        BookLoreUserEntity user = persistUser("reader2");

        BookEntity accessibleBook = persistBook(accessibleLibrary);
        BookEntity restrictedBook = persistBook(restrictedLibrary);

        Instant now = Instant.now();
        persistProgress(user, accessibleBook, ReadStatus.READING, now);
        persistProgress(user, restrictedBook, ReadStatus.READING, now);

        Page<Long> result = userBookProgressRepository.findContinueReadingBookIdsByLibraryIds(
                user.getId(), List.of(ReadStatus.READING, ReadStatus.RE_READING),
                Set.of(accessibleLibrary.getId()), PageRequest.of(0, 10));

        assertThat(result.getContent()).containsExactly(accessibleBook.getId());
    }

    @Test
    void findContinueReadingBookIds_excludesOtherUsersProgress() {
        LibraryEntity library = persistLibrary("Lib B");
        BookLoreUserEntity user1 = persistUser("reader3");
        BookLoreUserEntity user2 = persistUser("reader4");

        BookEntity book = persistBook(library);
        persistProgress(user1, book, ReadStatus.READING, Instant.now());

        Page<Long> result = userBookProgressRepository.findContinueReadingBookIds(
                user2.getId(), List.of(ReadStatus.READING, ReadStatus.RE_READING), PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }
}
