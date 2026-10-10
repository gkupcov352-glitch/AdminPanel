package com.example.adminpanel;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single player report.
 *
 * <p>The reporter is stored by UUID + name instead of a {@code Player} reference:
 * holding a Player object after they log out leaks it and would break the list.
 */
public class Report {

    private final UUID reporterId;
    private final String reporterName;
    private final String targetName;
    private final String reason;
    private final LocalDateTime time;

    public Report(UUID reporterId, String reporterName, String targetName, String reason) {
        this.reporterId = reporterId;
        this.reporterName = reporterName;
        this.targetName = targetName;
        this.reason = reason;
        this.time = LocalDateTime.now();
    }

    public UUID getReporterId() {
        return reporterId;
    }

    public String getTargetName() {
        return targetName;
    }

    public String getSummary() {
        return reporterName + ": " + reason + " at " + time;
    }
}
