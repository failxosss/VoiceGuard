package cz.voiceguard.config;

/**
 * A single step of the escalation ladder, e.g. "3rd violation -> 30 minutes".
 */
public final class PunishmentLevel {

    private final int violations;
    private final long durationMillis;

    public PunishmentLevel(int violations, long durationMillis) {
        this.violations = violations;
        this.durationMillis = durationMillis;
    }

    public int getViolations() {
        return violations;
    }

    public long getDurationMillis() {
        return durationMillis;
    }
}
