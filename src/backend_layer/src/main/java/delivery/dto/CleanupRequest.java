package delivery.dto;

import java.util.List;

/**
 * DTO for cleanup request containing a list of task IDs to be cleaned up.
 */
public class CleanupRequest {
    /**
     * List of task IDs to be cleaned up.
     */
    public List<String> taskIds;
}
