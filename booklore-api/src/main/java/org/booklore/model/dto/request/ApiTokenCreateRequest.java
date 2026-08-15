package org.booklore.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ApiTokenCreateRequest {

    @NotBlank(message = "Token name must not be blank")
    @Size(max = 100, message = "Token name must not exceed 100 characters")
    private String name;
}
