package org.booklore.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ApiTokenSummary {
    private Long id;
    private String name;
    private Instant createdAt;
    private Instant lastUsedAt;
}
