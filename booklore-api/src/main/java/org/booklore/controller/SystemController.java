package org.booklore.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.booklore.model.dto.SelfUpdateStatus;
import org.booklore.service.system.SystemUpdateService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System", description = "Server administration endpoints")
public class SystemController {

    private final SystemUpdateService systemUpdateService;

    @Operation(summary = "Get self-update status", description = "Whether an update is available and can be applied from within the app.")
    @ApiResponse(responseCode = "200", description = "Status returned successfully")
    @PreAuthorize("@securityUtil.isAdmin()")
    @GetMapping("/update-status")
    public ResponseEntity<SelfUpdateStatus> getUpdateStatus() {
        return ResponseEntity.ok(systemUpdateService.getStatus());
    }

    @Operation(summary = "Trigger an in-app update", description = "Pull, rebuild and restart the server. Native installs only.")
    @ApiResponse(responseCode = "202", description = "Update started")
    @PreAuthorize("@securityUtil.isAdmin()")
    @PostMapping("/update")
    public ResponseEntity<Map<String, Object>> triggerUpdate() {
        systemUpdateService.triggerUpdate();
        return ResponseEntity.accepted().body(Map.of("started", true));
    }
}
