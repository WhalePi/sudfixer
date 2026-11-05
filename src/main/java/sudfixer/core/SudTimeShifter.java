package sudfixer.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites a .sud file with every time inside it moved by a fixed offset.
 *
 * <p>Times live in three places inside the file:
 * <ul>
 *   <li>the {@code TimeS}/{@code TimeOffsetUs} pair in the header of <em>every</em> chunk,
 *       XML metadata and audio alike – this is what readers such as PAMGuard use as the
 *       recording time;</li>
 *   <li>date-times written as text inside the XML metadata chunks
 *       (e.g. {@code 2025-01-25T00:00:04});</li>
 *   <li>the header and data CRCs, which are recomputed for every chunk that changes so the
 *       file still passes its integrity checks.</li>
 * </ul>
 * The 30 byte file header holds the host's offload time rather than a recording time, so it
 * is copied unchanged, as are any bytes after the last complete chunk. Audio data is never
 * touched.
 */
public final class SudTimeShifter {

    /** ISO style date-times written into the XML, e.g. by the SoundTrap host software. */
    private static final Pattern XML_DATE_TIME =
            Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})([T ])(\\d{2}):(\\d{2}):(\\d{2})");

    private static final DateTimeFormatter XML_FORMAT_T = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter XML_FORMAT_SPACE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SudTimeShifter() { }

    /** What was changed in one file. */
    public record Result(long chunks, long xmlChunks, long xmlTimesChanged, long trailingBytes) { }

    /**
     * Time of the first recorded data in the file, in microseconds since the epoch (UTC):
     * the first non-XML chunk, or the first chunk of any kind if there is no data.
     *
     * @return the time, or {@code null} if the file has no readable chunks
     */
    public static Long firstDataTimeMicros(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            byte[] fileHeader = new byte[SudFormat.FILE_HEADER_BYTES];
            if (!readFully(in, fileHeader)) {
                return null;
            }
            byte[] h = new byte[SudFormat.CHUNK_HEADER_BYTES];
            Long first = null;
            // Metadata comes first; give up looking for data after a generous number of chunks.
            for (int i = 0; i < 10_000; i++) {
                if (!readFully(in, h) || SudFormat.getUInt16(h, 0) != SudFormat.CHUNK_MAGIC) {
                    break;
                }
                long t = chunkMicros(h);
                if (first == null) {
                    first = t;
                }
                if (SudFormat.getUInt16(h, 2) != SudFormat.XML_CHUNK_ID) {
                    return t;
                }
                skipFully(in, SudFormat.getUInt16(h, 4));
            }
            return first;
        }
    }

    /**
     * Copy {@code source} to {@code target}, shifting all the times in it by {@code offset}.
     *
     * @param progress  told the number of source bytes consumed so far (may be null)
     * @param cancelled polled between chunks; when it returns true an
     *                  {@link java.util.concurrent.CancellationException} is thrown (may be null)
     */
    public static Result shift(Path source, Path target, TimeOffset offset,
                               LongConsumer progress, BooleanSupplier cancelled) throws IOException {
        long chunks = 0;
        long xmlChunks = 0;
        long xmlTimes = 0;
        long trailing = 0;
        long consumed = 0;
        long lastReported = 0;

        try (InputStream in = new BufferedInputStream(Files.newInputStream(source), 1 << 20);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), 1 << 20)) {

            byte[] fileHeader = new byte[SudFormat.FILE_HEADER_BYTES];
            if (!readFully(in, fileHeader)) {
                throw new IOException("Too short to be a .sud file");
            }
            out.write(fileHeader);
            consumed += fileHeader.length;

            byte[] h = new byte[SudFormat.CHUNK_HEADER_BYTES];
            byte[] data = new byte[65536];
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    throw new java.util.concurrent.CancellationException();
                }
                int got = readUpTo(in, h);
                if (got == 0) {
                    break;
                }
                if (got < h.length || SudFormat.getUInt16(h, 0) != SudFormat.CHUNK_MAGIC) {
                    // Not a chunk: the unused tail of the last block. Keep it byte for byte.
                    out.write(h, 0, got);
                    trailing += got + in.transferTo(out);
                    break;
                }
                int length = SudFormat.getUInt16(h, 4);
                int dataGot = readUpTo(in, data, length);
                if (dataGot < length) {
                    // Truncated final chunk: keep it as it is.
                    out.write(h);
                    out.write(data, 0, dataGot);
                    trailing += h.length + dataGot;
                    break;
                }
                consumed += h.length + length;

                byte[] payload = data;
                int payloadLength = length;
                if (SudFormat.getUInt16(h, 2) == SudFormat.XML_CHUNK_ID) {
                    xmlChunks++;
                    byte[] rewritten = shiftXml(data, length, offset);
                    if (rewritten != null) {
                        payload = rewritten;
                        payloadLength = rewritten.length;
                        SudFormat.putUInt16(h, 4, payloadLength);
                        SudFormat.putUInt16(h, 16, SudFormat.crc(payload, 0, payloadLength));
                        xmlTimes++;
                    }
                }

                shiftChunkTime(h, offset);
                SudFormat.putUInt16(h, 18, SudFormat.crc(h, 0, 18));

                out.write(h);
                out.write(payload, 0, payloadLength);
                chunks++;

                if (progress != null && consumed - lastReported > (1 << 20)) {
                    progress.accept(consumed);
                    lastReported = consumed;
                }
            }
        }
        if (progress != null) {
            progress.accept(Files.size(source));
        }
        return new Result(chunks, xmlChunks, xmlTimes, trailing);
    }

    /** Chunk time in microseconds since the epoch. */
    public static long chunkMicros(byte[] header) {
        return SudFormat.getUInt32Pdp(header, 8) * TimeOffset.MICROS_PER_SECOND
                + SudFormat.getUInt32Pdp(header, 12);
    }

    private static void shiftChunkTime(byte[] h, TimeOffset offset) {
        long seconds = SudFormat.getUInt32Pdp(h, 8);
        long micros = SudFormat.getUInt32Pdp(h, 12);
        if (seconds == 0 && micros == 0) {
            return; // an unset time stays unset
        }
        long t = seconds * TimeOffset.MICROS_PER_SECOND + micros + offset.micros();
        if (t < 0 || t / TimeOffset.MICROS_PER_SECOND > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("Offset moves a chunk time outside the range a .sud file can hold");
        }
        SudFormat.putUInt32Pdp(h, 8, t / TimeOffset.MICROS_PER_SECOND);
        SudFormat.putUInt32Pdp(h, 12, t % TimeOffset.MICROS_PER_SECOND);
    }

    /**
     * Shift any date-times written in an XML chunk.
     *
     * @return the new (byte-swapped, even length) chunk data, or null if nothing changed
     */
    static byte[] shiftXml(byte[] data, int length, TimeOffset offset) {
        byte[] text = java.util.Arrays.copyOf(data, length);
        SudFormat.swapPairs(text);
        // ISO-8859-1 maps every byte to one char and back, so untouched bytes survive exactly.
        String xml = new String(text, StandardCharsets.ISO_8859_1);
        Matcher m = XML_DATE_TIME.matcher(xml);
        if (!m.find()) {
            return null;
        }
        long seconds = Math.round(offset.micros() / (double) TimeOffset.MICROS_PER_SECOND);
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        do {
            String replacement = m.group();
            try {
                DateTimeFormatter f = m.group(4).equals("T") ? XML_FORMAT_T : XML_FORMAT_SPACE;
                LocalDateTime t = LocalDateTime.parse(m.group(), f).plusSeconds(seconds);
                String shifted = t.format(f);
                if (shifted.length() == replacement.length()) {
                    changed |= !shifted.equals(replacement);
                    replacement = shifted;
                }
            } catch (RuntimeException e) {
                // Looks like a date but isn't one (e.g. month 13): leave it alone.
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        } while (m.find());
        m.appendTail(sb);
        if (!changed) {
            return null;
        }
        byte[] out = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        if (out.length % 2 != 0) {
            out = java.util.Arrays.copyOf(out, out.length + 1);
        }
        SudFormat.swapPairs(out);
        return out;
    }

    // ------------------------------------------------------------------ io helpers

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        return readUpTo(in, b) == b.length;
    }

    private static int readUpTo(InputStream in, byte[] b) throws IOException {
        return readUpTo(in, b, b.length);
    }

    private static int readUpTo(InputStream in, byte[] b, int length) throws IOException {
        return in.readNBytes(b, 0, length);
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        try {
            in.skipNBytes(n);
        } catch (EOFException e) {
            // end of file mid-chunk: nothing more to read
        }
    }
}
