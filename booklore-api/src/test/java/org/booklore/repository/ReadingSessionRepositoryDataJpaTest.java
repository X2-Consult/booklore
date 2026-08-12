package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.dto.AudiobookProgressDto;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.entity.ReadingSessionEntity;
import org.booklore.model.enums.BookFileType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
class ReadingSessionRepositoryDataJpaTest {

    @Autowired
    private ReadingSessionRepository readingSessionRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private LibraryEntity persistLibrary() {
        LibraryEntity library = LibraryEntity.builder().name("Audiobook Library").icon("book").watch(false).build();
        entityManager.persist(library);
        entityManager.flush();
        return library;
    }

    private BookEntity persistBook(LibraryEntity library) {
        LibraryPathEntity libraryPath = LibraryPathEntity.builder()
                .library(library)
                .path("/test/audiobook/" + System.nanoTime())
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

    private void persistAudiobookFile(BookEntity book, long durationSeconds, String subPath) {
        BookFileEntity file = BookFileEntity.builder()
                .book(book)
                .fileName("audiobook.m4b")
                .fileSubPath(subPath)
                .isBookFormat(true)
                .folderBased(false)
                .bookType(BookFileType.AUDIOBOOK)
                .durationSeconds(durationSeconds)
                .build();
        entityManager.persist(file);
        entityManager.flush();
    }

    private void persistSession(BookLoreUserEntity user, BookEntity book, Instant startTime, int durationSeconds) {
        ReadingSessionEntity session = ReadingSessionEntity.builder()
                .user(user)
                .book(book)
                .bookType(BookFileType.AUDIOBOOK)
                .startTime(startTime)
                .endTime(startTime.plusSeconds(durationSeconds))
                .durationSeconds(durationSeconds)
                .build();
        entityManager.persist(session);
        entityManager.flush();
    }

    @Test
    void findAudiobookProgressByUser_doesNotInflateDuration_whenBookHasMultipleAudiobookFiles() {
        LibraryEntity library = persistLibrary();
        BookEntity book = persistBook(library);
        BookLoreUserEntity user = persistUser("audiolistener");

        BookMetadataEntity metadata = BookMetadataEntity.builder()
                .book(book).bookId(book.getId()).title("Multi-File Audiobook").build();
        entityManager.persist(metadata);
        entityManager.flush();

        // Two audiobook file rows for the same book (e.g. alternative format) - the fan-out risk.
        persistAudiobookFile(book, 3600L, "part-1");
        persistAudiobookFile(book, 3700L, "part-2");

        persistSession(user, book, Instant.now().minusSeconds(600), 300);
        persistSession(user, book, Instant.now().minusSeconds(200), 200);
        entityManager.clear();

        List<AudiobookProgressDto> result = readingSessionRepository.findAudiobookProgressByUser(user.getId());

        assertThat(result).hasSize(1);
        AudiobookProgressDto dto = result.get(0);
        assertThat(dto.getBookId()).isEqualTo(book.getId());
        // Sum of the two sessions' durations, not multiplied by the two matching book_file rows.
        assertThat(dto.getListenedDurationSeconds()).isEqualTo(500L);
        // MAX of the two file durations, per the subquery.
        assertThat(dto.getTotalDurationSeconds()).isEqualTo(3700L);
    }

    // findWeeklyListeningTrend isn't covered here: it uses Postgres-only syntax
    // (make_interval, AT TIME ZONE on a parameterized zone) that H2 can't execute, same as
    // the rest of this repository's native queries. The timezone-frame fix there was verified
    // by inspection - see the ReadingSessionRepository @Query comment.
}
