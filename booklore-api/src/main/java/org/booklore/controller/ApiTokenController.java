package org.booklore.controller;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.request.ApiTokenCreateRequest;
import org.booklore.model.dto.response.ApiTokenCreatedResponse;
import org.booklore.model.dto.response.ApiTokenSummary;
import org.booklore.service.ApiTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "API Tokens", description = "Self-service tokens for third-party apps. Always read-only plus reading-progress updates, regardless of the account's own permissions.")
@RestController
@RequestMapping("/api/v1/api-tokens")
@RequiredArgsConstructor
public class ApiTokenController {

    private final ApiTokenService apiTokenService;
    private final AuthenticationService authenticationService;

    @Operation(summary = "Create an API token", description = "Generates a new read-only-plus-progress API token for the current user. The raw token is returned once and cannot be retrieved again.")
    @ApiResponse(responseCode = "200", description = "API token created successfully")
    @PostMapping
    public ResponseEntity<ApiTokenCreatedResponse> createToken(@RequestBody @Valid ApiTokenCreateRequest request) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        return ResponseEntity.ok(apiTokenService.createToken(userId, request.getName()));
    }

    @Operation(summary = "List API tokens", description = "Lists the current user's active API tokens. Raw token values are never returned after creation.")
    @ApiResponse(responseCode = "200", description = "API tokens returned successfully")
    @GetMapping
    public ResponseEntity<List<ApiTokenSummary>> listTokens() {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        return ResponseEntity.ok(apiTokenService.listTokens(userId));
    }

    @Operation(summary = "Revoke an API token", description = "Revokes one of the current user's API tokens. Immediately invalidates it for any app using it.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "API token revoked successfully"),
        @ApiResponse(responseCode = "403", description = "Token belongs to another user"),
        @ApiResponse(responseCode = "404", description = "Token not found")
    })
    @DeleteMapping("/{tokenId}")
    public ResponseEntity<Void> revokeToken(@Parameter(description = "ID of the API token to revoke") @PathVariable Long tokenId) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        apiTokenService.revokeToken(userId, tokenId);
        return ResponseEntity.noContent().build();
    }
}
