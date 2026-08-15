package org.booklore.model.dto.progress;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@Builder
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class KoreaderProgress {
    private Long timestamp;
    @NotBlank
    private String document;
    @NotNull
    private Float percentage;
    private String progress;
    private String device;
    private String device_id;
}
