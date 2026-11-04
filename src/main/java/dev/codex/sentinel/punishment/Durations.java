package dev.codex.sentinel.punishment;

/** Parses config durations such as "30m", "12h" or "7d". */
public final class Durations {
    private Durations() { }

    /** @return milliseconds, or -1 when the input is malformed, non-positive, uses an unknown unit or overflows. */
    public static long parseMillis(String input) {
        if (input == null || input.length() < 2) return -1;
        long unit = switch (Character.toLowerCase(input.charAt(input.length() - 1))) {
            case 's' -> 1_000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            default -> -1L;
        };
        if (unit < 0) return -1;
        try {
            long n = Long.parseLong(input.substring(0, input.length() - 1).trim());
            return n <= 0 ? -1 : Math.multiplyExact(n, unit);
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }
}
