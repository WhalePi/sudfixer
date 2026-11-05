package sudfixer.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the recording time written into a file name and rewrites it.
 *
 * <p>Recognised forms:
 * <ul>
 *   <li>SoundTrap: {@code 7176.250125000004.sud} – {@code yyMMddHHmmss} between dots</li>
 *   <li>{@code PAM_20170116_183344_000.sud} – {@code yyyyMMdd_HHmmss}</li>
 *   <li>{@code rec_20170116183344.sud} – {@code yyyyMMddHHmmss}</li>
 * </ul>
 */
public final class FilenameTime {

    private record Form(Pattern pattern, DateTimeFormatter format) { }

    private static final List<Form> FORMS = List.of(
            new Form(Pattern.compile("(?<=\\.)(\\d{12})(?=\\.)"), fmt("uuMMddHHmmss")),
            new Form(Pattern.compile("(?<!\\d)(\\d{8}_\\d{6})(?!\\d)"), fmt("uuuuMMdd_HHmmss")),
            new Form(Pattern.compile("(?<!\\d)(\\d{14})(?!\\d)"), fmt("uuuuMMddHHmmss")));

    private static DateTimeFormatter fmt(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }

    private FilenameTime() { }

    /** The time in the name, or null if the name holds no recognisable time. */
    public static LocalDateTime parse(String fileName) {
        for (Form form : FORMS) {
            Matcher m = form.pattern.matcher(fileName);
            if (m.find()) {
                try {
                    return LocalDateTime.parse(m.group(1), form.format);
                } catch (RuntimeException e) {
                    // digits that are not a valid date: try the next form
                }
            }
        }
        return null;
    }

    /**
     * The name with its time moved by {@code offset} (rounded to whole seconds, the
     * resolution of a file name), or the name unchanged if it holds no time.
     */
    public static String shift(String fileName, TimeOffset offset) {
        long seconds = Math.round(offset.micros() / (double) TimeOffset.MICROS_PER_SECOND);
        for (Form form : FORMS) {
            Matcher m = form.pattern.matcher(fileName);
            if (m.find()) {
                LocalDateTime t;
                try {
                    t = LocalDateTime.parse(m.group(1), form.format);
                } catch (RuntimeException e) {
                    continue;
                }
                String shifted = t.plusSeconds(seconds).format(form.format);
                if (shifted.length() != m.group(1).length()) {
                    return fileName; // e.g. a 2 digit year leaving its century
                }
                return fileName.substring(0, m.start(1)) + shifted + fileName.substring(m.end(1));
            }
        }
        return fileName;
    }
}
