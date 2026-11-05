package sudfixer.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SudFixerTest {

    @TempDir
    Path dir;

    // ------------------------------------------------------------------ offsets

    @Test
    void parsesOffsets() {
        assertEquals(3_600_000_000L, TimeOffset.parse("+01:00:00").micros());
        assertEquals(-1_800_000_000L, TimeOffset.parse("-00:30").micros());
        assertEquals(-1_800_000_000L, TimeOffset.parse("-30m").micros());
        assertEquals(250_000L, TimeOffset.parse("250ms").micros());
        assertEquals(1_500_000L, TimeOffset.parse("1.5").micros());
        assertEquals(93_784_500_000L, TimeOffset.parse("1d 2h 3m 4.5s").micros());
        assertEquals(93_784_000_000L, TimeOffset.parse("1d 02:03:04").micros());
        assertEquals(1_123_400L, TimeOffset.parse("0:00:01.1234").micros());
        assertThrows(IllegalArgumentException.class, () -> TimeOffset.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> TimeOffset.parse("1:75"));
        assertThrows(IllegalArgumentException.class, () -> TimeOffset.parse("-"));
        assertEquals("-1 day 02:03:04.5", TimeOffset.parse("-1d 2h 3m 4.5s").describe());
    }

    // ---------------------------------------------------------------- file names

    @Test
    void shiftsFileNames() {
        TimeOffset hour = TimeOffset.parse("1h");
        assertEquals("7176.250125010004.sud", FilenameTime.shift("7176.250125000004.sud", hour));
        assertEquals("7176.250124230004.sud", FilenameTime.shift("7176.250125000004.sud", TimeOffset.parse("-1h")));
        assertEquals("PAM_20170116_193344_000.sud", FilenameTime.shift("PAM_20170116_183344_000.sud", hour));
        assertEquals("recording.sud", FilenameTime.shift("recording.sud", hour));
        assertEquals(LocalDateTime.of(2025, 1, 25, 0, 0, 4), FilenameTime.parse("7176.250125000004.sud"));
        assertNull(FilenameTime.parse("7176.999999999999.sud"));
    }

    // ------------------------------------------------------------- sud rewriting

    @Test
    void shiftsEveryChunkAndKeepsCrcsValid() throws IOException {
        long t0 = 1_737_763_204L;
        Path in = dir.resolve("7176.250125000004.sud");
        writeSud(in, t0, 43_047, "<EVENT>\n<START STATE=\"REOPEN\" />\n</EVENT>\n", 3);

        Path out = dir.resolve("out.sud");
        // -1.5 s exercises the microsecond borrow
        SudTimeShifter.Result r = SudTimeShifter.shift(in, out, TimeOffset.parse("-1.5"), null, null);
        assertEquals(4, r.chunks());
        assertEquals(1, r.xmlChunks());
        assertEquals(16, r.trailingBytes());

        List<long[]> before = chunks(Files.readAllBytes(in));
        List<long[]> after = chunks(Files.readAllBytes(out));
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i)[0] - 1_500_000, after.get(i)[0], "chunk " + i);
            assertEquals(1, after.get(i)[1], "header crc ok in chunk " + i);
            assertEquals(1, after.get(i)[2], "data crc ok in chunk " + i);
        }

        byte[] a = Files.readAllBytes(in);
        byte[] b = Files.readAllBytes(out);
        assertEquals(a.length, b.length);
        // file header and trailing bytes untouched
        assertArrayEquals(java.util.Arrays.copyOf(a, 30), java.util.Arrays.copyOf(b, 30));
        assertArrayEquals(java.util.Arrays.copyOfRange(a, a.length - 16, a.length),
                java.util.Arrays.copyOfRange(b, b.length - 16, b.length));

        assertEquals(t0 * 1_000_000L + 43_047 + 1000, SudTimeShifter.firstDataTimeMicros(in));
    }

    @Test
    void shiftsDateTimesWrittenInXml() throws IOException {
        Path in = dir.resolve("x.sud");
        writeSud(in, 1_737_763_204L, 0,
                "<WavFileHandler SamplingStartTimeUTC=\"2025-01-25T00:00:04\" /><BINFILE TIME=\"12:21:46\" />", 1);
        Path out = dir.resolve("y.sud");
        SudTimeShifter.Result r = SudTimeShifter.shift(in, out, TimeOffset.parse("-1h"), null, null);
        assertEquals(1, r.xmlTimesChanged());

        byte[] f = Files.readAllBytes(out);
        int len = SudFormat.getUInt16(f, 30 + 4);
        byte[] xml = java.util.Arrays.copyOfRange(f, 50, 50 + len);
        SudFormat.swapPairs(xml);
        String s = new String(xml, StandardCharsets.ISO_8859_1);
        assertTrue(s.contains("2025-01-24T23:00:04"), s);
        assertTrue(s.contains("TIME=\"12:21:46\""), "firmware build time is not a recording time");
        for (long[] c : chunks(f)) {
            assertEquals(1, c[1]);
            assertEquals(1, c[2]);
        }
    }

    // --------------------------------------------------------------- batch jobs

    @Test
    void inPlaceRenameChainDoesNotClobber() throws IOException {
        // Three 15 minute files shifted by +15 min: each takes the next one's name.
        long t = 1_737_763_204L;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LocalDateTime when = LocalDateTime.of(2025, 1, 25, 0, 15 * i, 4);
            Path p = dir.resolve(String.format("7176.2501250%d%02d04.sud", 0, 15 * i));
            writeSud(p, t + 900L * i, 0, "<EVENT/>", 1);
            Files.setLastModifiedTime(p, FileTime.fromMillis(1_700_000_000_000L));
            files.add(p);
            assertEquals(when, FilenameTime.parse(p.getFileName().toString()));
        }
        FixJob job = new FixJob(files, new FixJob.Options(TimeOffset.parse("15m"), true, true, null, null));
        FixJob.Outcome outcome = job.run(noop(), () -> false);
        assertTrue(outcome.errors().isEmpty(), outcome.errors().toString());
        assertEquals(3, outcome.succeeded());

        List<String> names = list(dir);
        assertEquals(List.of("7176.250125001504.sud", "7176.250125003004.sud", "7176.250125004504.sud"), names);
        for (int i = 0; i < 3; i++) {
            Path p = dir.resolve(names.get(i));
            assertEquals((t + 900L * (i + 1)) * 1_000_000L + 1000, SudTimeShifter.firstDataTimeMicros(p));
            assertEquals(1_700_000_000_000L + 900_000L, Files.getLastModifiedTime(p).toMillis());
        }
    }

    @Test
    void writesToNewFolderKeepingSubFolders() throws IOException {
        Path src = dir.resolve("src");
        Path sub = src.resolve("day1");
        Files.createDirectories(sub);
        Path a = sub.resolve("7176.250125000004.sud");
        writeSud(a, 1_737_763_204L, 0, "<EVENT/>", 1);
        byte[] original = Files.readAllBytes(a);

        Path outDir = dir.resolve("fixed");
        FixJob job = new FixJob(List.of(a), new FixJob.Options(TimeOffset.parse("-1h"), true, false, outDir, src));
        FixJob.Outcome outcome = job.run(noop(), () -> false);
        assertTrue(outcome.errors().isEmpty(), outcome.errors().toString());

        assertTrue(Files.exists(outDir.resolve("day1/7176.250124230004.sud")));
        assertArrayEquals(original, Files.readAllBytes(a), "source untouched");
        assertEquals(Files.getLastModifiedTime(a), Files.getLastModifiedTime(outDir.resolve("day1/7176.250124230004.sud")));
        try (Stream<Path> s = Files.walk(dir)) {
            assertFalse(s.anyMatch(p -> p.toString().endsWith(".sudfix-tmp")));
        }
    }

    @Test
    void refusesToOverwriteUnrelatedFile() throws IOException {
        Path a = dir.resolve("7176.250125000004.sud");
        writeSud(a, 1_737_763_204L, 0, "<EVENT/>", 1);
        Path b = dir.resolve("7176.250125010004.sud");
        Files.writeString(b, "not part of the job");
        FixJob job = new FixJob(List.of(a), new FixJob.Options(TimeOffset.parse("1h"), true, true, null, null));
        FixJob.Outcome outcome = job.run(noop(), () -> false);
        assertEquals(1, outcome.errors().size());
        assertEquals("not part of the job", Files.readString(b));
        assertTrue(Files.exists(a));
    }

    // ------------------------------------------------------------------ helpers

    private static FixJob.Listener noop() {
        return new FixJob.Listener() {
            @Override
            public void file(int index, int count, Path source) { }

            @Override
            public void bytes(long done, long total) { }
        };
    }

    private static List<String> list(Path d) throws IOException {
        try (Stream<Path> s = Files.list(d)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** A SUD file: header, one XML chunk, {@code audioChunks} data chunks 1 ms apart, 16 bytes of tail. */
    static void writeSud(Path file, long timeS, long timeUs, String xml, int audioChunks) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] header = new byte[30];
        SudFormat.putUInt32Pdp(header, 2, 1_751_192_514L);
        SudFormat.putUInt32Pdp(header, 8, 1_751_192_514L);
        SudFormat.putUInt16(header, 28, SudFormat.crc(header, 0, 28));
        o.write(header);

        byte[] x = xml.getBytes(StandardCharsets.ISO_8859_1);
        if (x.length % 2 != 0) x = java.util.Arrays.copyOf(x, x.length + 1);
        SudFormat.swapPairs(x);
        o.write(chunk(0, x, timeS, timeUs));

        for (int i = 0; i < audioChunks; i++) {
            byte[] data = new byte[100];
            for (int j = 0; j < data.length; j++) data[j] = (byte) (i * 7 + j);
            long us = timeUs + 1000L * (i + 1);
            o.write(chunk(4, data, timeS + us / 1_000_000, us % 1_000_000));
        }
        o.write(new byte[16]); // tail of the last block
        Files.write(file, o.toByteArray());
    }

    static byte[] chunk(int id, byte[] data, long timeS, long timeUs) {
        byte[] c = new byte[20 + data.length];
        SudFormat.putUInt16(c, 0, SudFormat.CHUNK_MAGIC);
        SudFormat.putUInt16(c, 2, id);
        SudFormat.putUInt16(c, 4, data.length);
        SudFormat.putUInt16(c, 6, 0);
        SudFormat.putUInt32Pdp(c, 8, timeS);
        SudFormat.putUInt32Pdp(c, 12, timeUs);
        SudFormat.putUInt16(c, 16, SudFormat.crc(data, 0, data.length));
        SudFormat.putUInt16(c, 18, SudFormat.crc(c, 0, 18));
        System.arraycopy(data, 0, c, 20, data.length);
        return c;
    }

    /** Per chunk: time in micros, header crc ok (1/0), data crc ok (1/0). */
    static List<long[]> chunks(byte[] f) {
        List<long[]> out = new ArrayList<>();
        int p = 30;
        while (p + 20 <= f.length && SudFormat.getUInt16(f, p) == SudFormat.CHUNK_MAGIC) {
            byte[] h = java.util.Arrays.copyOfRange(f, p, p + 20);
            int len = SudFormat.getUInt16(h, 4);
            out.add(new long[] {
                    SudTimeShifter.chunkMicros(h),
                    SudFormat.getUInt16(h, 18) == SudFormat.crc(h, 0, 18) ? 1 : 0,
                    SudFormat.getUInt16(h, 16) == SudFormat.crc(f, p + 20, len) ? 1 : 0});
            p += 20 + len;
        }
        return out;
    }
}
