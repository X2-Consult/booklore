package org.booklore.repository;

import org.booklore.model.entity.ApiTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApiTokenRepository extends JpaRepository<ApiTokenEntity, Long> {

    Optional<ApiTokenEntity> findByTokenHash(String tokenHash);

    List<ApiTokenEntity> findAllByUserIdOrderByCreatedAtDesc(Long userId);
}
