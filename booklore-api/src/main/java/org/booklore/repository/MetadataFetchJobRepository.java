package org.booklore.repository;

import org.booklore.model.entity.MetadataFetchJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface MetadataFetchJobRepository extends JpaRepository<MetadataFetchJobEntity, String> {

    int deleteAllByCompletedAtBefore(Instant cutoff);

    @Modifying
    @Query("DELETE FROM MetadataFetchJobEntity")
    int deleteAllRecords();

    @Query("SELECT COUNT(m) FROM MetadataFetchJobEntity m")
    long countAll();

    // No DISTINCT: Hibernate already de-duplicates fetch-joined roots, and an SQL DISTINCT over
    // proposals.metadata_json fails on Postgres ("could not identify an equality operator for type json").
    @Query("SELECT t FROM MetadataFetchJobEntity t LEFT JOIN FETCH t.proposals")
    List<MetadataFetchJobEntity> findAllWithProposals();
}
