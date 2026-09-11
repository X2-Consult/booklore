package org.booklore.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class MetadataBatchProgressNotification {
    private String taskId;
    private int completed;
    private int total;
    private String message;
    private String status;
    private boolean isReview;
    // Batch-level problems (e.g. a provider blocking requests) that stay visible while the
    // per-book message underneath keeps changing.
    private List<String> warnings;

    public MetadataBatchProgressNotification(String taskId, int completed, int total, String message, String status, boolean isReview) {
        this(taskId, completed, total, message, status, isReview, List.of());
    }
}
