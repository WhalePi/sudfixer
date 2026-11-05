package sudfixer.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A signed time offset, held in microseconds (the resolution of a SUD chunk time).
 *
 * <p>Text accepted by {@link #parse(String)}, all with an optional leading {@code +} or {@code -}:
 * <ul>
 *   <li>clock form: {@code 01:30}, {@code 01:30:15}, {@code 01:30:15.250}, with an optional day
 *       count first: {@code 2d 01:30:00}</li>
 *   <li>units: {@code 1d 2h 30m 15.5s}, {@code -90m}, {@code 250ms}, {@code 10us}</li>
 *   <li>a bare number of seconds: {@code 3600}, {@code -0.5}</li>
 * </ul>
 */
public final class TimeOffset {

    public static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long MICROS_PER_MINUTE = 60 * MICROS_PER_SECOND;
    private static final long MICROS_PER_HOUR = 60 * MICROS_PER_MINUTE;
    private static final long MICROS_PER_DAY = 24 * MICROS_PER_HOUR;

    public static final TimeOffset ZERO = new TimeOffset(0);

    private static final Pattern CLOCK = Pattern.compile(
            "(?:(\\d+)\\s*d(?:ays?)?\\s+)?(\\d+):(\\d{1,2})(?::(\\d{1,2})(?:\\.(\\d{1,6}))?)?");

    private static final Pattern UNIT = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(days?|d|hours?|hrs?|h|ms|us|µs|minutes?|mins?|m|seconds?|secs?|s)");

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?|\\.\\d+");

    private final long micros;

    private TimeOffset(long micros) {
        this.micros = micros;
    }

    public static TimeOffset ofMicros(long micros) {
        return new TimeOffset(micros);
    }

    public static TimeOffset ofSeconds(long seconds) {
        return new TimeOffset(seconds * MICROS_PER_SECOND);
    }

    public long micros() {
        return micros;
    }

    /** The offset in milliseconds, rounded toward negative infinity. */
    public long millis() {
        return Math.floorDiv(micros, 1000L);
    }

    public boolean isZero() {
        return micros == 0;
    }

    /**
     * Parse an offset typed by the user.
     *
     * @throws IllegalArgumentException with a readable message if the text is not an offset
     */
    public static TimeOffset parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Type an offset, e.g. +01:00:00 or -30m");
        }
        String s = text.trim().toLowerCase(Locale.ROOT);
        int sign = 1;
        if (s.startsWith("+")) {
            s = s.substring(1).trim();
        } else if (s.startsWith("-") || s.startsWith("−")) {
            sign = -1;
            s = s.substring(1).trim();
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Type a number after the sign");
        }

        Matcher clock = CLOCK.matcher(s);
        if (clock.matches()) {
            long days = clock.group(1) == null ? 0 : Long.parseLong(clock.group(1));
            long hours = Long.parseLong(clock.group(2));
            long minutes = Long.parseLong(clock.group(3));
            long seconds = clock.group(4) == null ? 0 : Long.parseLong(clock.group(4));
            if (minutes > 59 || seconds > 59) {
                throw new IllegalArgumentException("Minutes and seconds must be 0-59");
            }
            long frac = 0;
            if (clock.group(5) != null) {
                String f = (clock.group(5) + "000000").substring(0, 6);
                frac = Long.parseLong(f);
            }
            long total = days * MICROS_PER_DAY + hours * MICROS_PER_HOUR + minutes * MICROS_PER_MINUTE
                    + seconds * MICROS_PER_SECOND + frac;
            return new TimeOffset(sign * total);
        }

        if (NUMBER.matcher(s).matches()) {
            return new TimeOffset(sign * toMicros(s, MICROS_PER_SECOND));
        }

        // Unit form: the whole string must be made of unit terms, optionally space separated.
        Matcher unit = UNIT.matcher(s);
        long total = 0;
        int pos = 0;
        boolean any = false;
        while (unit.find()) {
            if (!s.substring(pos, unit.start()).isBlank()) {
                throw new IllegalArgumentException("Don't understand \"" + s.substring(pos, unit.start()).trim() + "\"");
            }
            total += toMicros(unit.group(1), unitMicros(unit.group(2)));
            pos = unit.end();
            any = true;
        }
        if (!any || !s.substring(pos).isBlank()) {
            throw new IllegalArgumentException("Use hh:mm:ss, units like 1h 30m, or seconds");
        }
        return new TimeOffset(sign * total);
    }

    private static long unitMicros(String unit) {
        if (unit.startsWith("d")) return MICROS_PER_DAY;
        if (unit.startsWith("h")) return MICROS_PER_HOUR;
        if (unit.equals("ms")) return 1000L;
        if (unit.equals("us") || unit.equals("µs")) return 1L;
        if (unit.startsWith("m")) return MICROS_PER_MINUTE;
        return MICROS_PER_SECOND;
    }

    /** Exact decimal multiply, so "0.1" seconds is exactly 100000 us. */
    private static long toMicros(String number, long scale) {
        return new java.math.BigDecimal(number)
                .multiply(java.math.BigDecimal.valueOf(scale))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** A readable form, e.g. {@code +1 d 02:30:00.250000}. */
    public String describe() {
        long abs = Math.abs(micros);
        long days = abs / MICROS_PER_DAY;
        abs %= MICROS_PER_DAY;
        long h = abs / MICROS_PER_HOUR;
        abs %= MICROS_PER_HOUR;
        long m = abs / MICROS_PER_MINUTE;
        abs %= MICROS_PER_MINUTE;
        long sec = abs / MICROS_PER_SECOND;
        long us = abs % MICROS_PER_SECOND;

        StringBuilder sb = new StringBuilder(micros < 0 ? "-" : "+");
        if (days > 0) sb.append(days).append(days == 1 ? " day " : " days ");
        sb.append(String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, sec));
        if (us != 0) {
            String f = String.format(Locale.ROOT, "%06d", us).replaceAll("0+$", "");
            sb.append('.').append(f);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
