package cz.voiceguard.punish;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses duration strings like "10s", "30s", "1m", "5m", "10m", "30m", "1h", "1d", "permanent".
 * Returns milliseconds, or {@link #PERMANENT} for a permanent punishment.
 */
public final class DurationParser {

    public static final long PERMANENT = -1L;

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)([smhd])$", Pattern.CASE_INSENSITIVE);

    private DurationParser() {
    }

    /**
     * @return duration in milliseconds, {@link #PERMANENT} for "permanent", or empty/throws for invalid input.
     */
    public static long parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Duration string is null");
        }
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("permanent")) {
            return PERMANENT;
        }
        Matcher matcher = PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid duration format: '" + input
                    + "'. Use e.g. 10s, 30s, 1m, 5m, 10m, 30m, 1h, 1d or 'permanent'.");
        }
        long amount = Long.parseLong(matcher.group(1));
        char unit = Character.toLowerCase(matcher.group(2).charAt(0));
        long unitMillis = switch (unit) {
            case 's' -> 1000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            default -> throw new IllegalArgumentException("Unknown duration unit: " + unit);
        };
        return amount * unitMillis;
    }

    /**
     * Formats milliseconds back into a short human-readable Czech string, e.g. "5 minut".
     */
    public static String format(long millis) {
        if (millis == PERMANENT) {
            return "natrvalo";
        }
        if (millis < 60_000L) {
            long s = millis / 1000L;
            return s + " s";
        }
        if (millis < 3_600_000L) {
            long m = millis / 60_000L;
            return m + " min";
        }
        if (millis < 86_400_000L) {
            long h = millis / 3_600_000L;
            return h + " h";
        }
        long d = millis / 86_400_000L;
        return d + " d";
    }
}
