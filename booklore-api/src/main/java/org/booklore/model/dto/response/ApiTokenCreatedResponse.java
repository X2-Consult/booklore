package org.booklore.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ApiTokenCreatedResponse {
    private Long id;
    private String name;
    /** The raw bearer token. Shown once, at creation time - never retrievable again. */
    private String token;
    private Instant createdAt;
}
