package org.booklore.model.dto.progress;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class EpubProgress {
    @NotNull
    String cfi;
    String href;
    @NotNull
    Float percentage;
    String ttsPositionCfi;
    /** When this position was last recorded server-side. Null on inbound client requests - only ever set by the server on responses. */
    Instant lastReadTime;
}
