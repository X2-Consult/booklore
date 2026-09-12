package org.booklore.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.booklore.BookloreApplication;
import org.booklore.model.entity.MetadataFetchJobEntity;
import org.booklore.model.entity.MetadataFetchProposalEntity;
import org.booklore.model.enums.FetchedMetadataProposalStatus;
import org.booklore.model.enums.MetadataFetchTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MetadataFetchJobRepository#findAllWithProposals} used to be {@code SELECT DISTINCT}, which
 * Postgres rejects because proposals.metadata_json is a {@code json} column. H2 can't reproduce that
 * error, so this pins the other half: without DISTINCT, each job still comes back once, carrying all
 * of its proposals.
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
class MetadataFetchJobRepositoryDataJpaTest {

    @Autowired
    private MetadataFetchJobRepository metadataFetchJobRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private MetadataFetchJobEntity job(String taskId, int proposals) {
        MetadataFetchJobEntity job = MetadataFetchJobEntity.builder()
                .taskId(taskId)
                .status(MetadataFetchTaskStatus.COMPLETED)
                .startedAt(Instant.now())
                .totalBooksCount(proposals)
                .completedBooks(proposals)
                .build();
        for (long bookId = 1; bookId <= proposals; bookId++) {
            job.getProposals().add(MetadataFetchProposalEntity.builder()
                    .job(job)
                    .bookId(bookId)
                    .metadataJson("{\"title\":\"Book " + bookId + "\"}")
                    .status(FetchedMetadataProposalStatus.FETCHED)
                    .fetchedAt(Instant.now())
                    .build());
        }
        return job;
    }

    @Test
    void findAllWithProposals_returnsEachJobOnce_withAllItsProposals() {
        metadataFetchJobRepository.save(job("job-with-three", 3));
        metadataFetchJobRepository.save(job("job-with-none", 0));
        entityManager.flush();
        entityManager.clear();

        List<MetadataFetchJobEntity> jobs = metadataFetchJobRepository.findAllWithProposals();

        assertThat(jobs).extracting(MetadataFetchJobEntity::getTaskId)
                .containsExactlyInAnyOrder("job-with-three", "job-with-none");
        assertThat(jobs).filteredOn(j -> j.getTaskId().equals("job-with-three"))
                .singleElement()
                .satisfies(j -> assertThat(j.getProposals()).hasSize(3));
    }
}
