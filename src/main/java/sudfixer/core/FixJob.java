package sudfixer.core;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Shifts the time of a batch of .sud files, either in place or into a new folder.
 *
 * <p>Every file is first written to a temporary file next to its destination, so a failure
 * part way through never leaves a half written .sud behind. In place, renamed files are only
 * moved to their new names once every file has been processed: shifting a sequence of
 * recordings by a multiple of their length gives one file the name another still has.
 */
public final class FixJob {

    private static final String TEMP_SUFFIX = ".sudfix-tmp";

    /**
     * @param offset            the time to add
     * @param renameFiles       rewrite the time in the file name
     * @param shiftModifiedTime move the file's last-modified time by the same offset
     * @param outputDir         the folder to write to, or null to replace the originals
     * @param sourceRoot        the folder files were found under; their sub folders are
     *                          recreated in {@code outputDir}. Null puts every file
     *                          directly in {@code outputDir}.
     */
    public record Options(TimeOffset offset, boolean renameFiles, boolean shiftModifiedTime,
                          Path outputDir, Path sourceRoot) {
        public boolean inPlace() {
            return outputDir == null;
        }
    }

    /** One file, and where it goes. */
    public record Item(Path source, Path target) { }

    public interface Listener {
        void file(int index, int count, Path source);

        void bytes(long done, long total);
    }

    public record Outcome(int succeeded, List<String> errors, boolean cancelled) { }

    private final List<Item> items;
    private final Options options;

    public FixJob(List<Path> files, Options options) {
        this.options = options;
        this.items = plan(files, options);
    }

    public List<Item> items() {
        return items;
    }

    /** Where each file will be written. */
    public static List<Item> plan(List<Path> files, Options options) {
        List<Item> plan = new ArrayList<>();
        for (Path source : files) {
            String name = source.getFileName().toString();
            String newName = options.renameFiles() ? FilenameTime.shift(name, options.offset()) : name;
            Path target;
            if (options.inPlace()) {
                target = source.resolveSibling(newName);
            } else {
                Path dir = options.outputDir();
                if (options.sourceRoot() != null && source.startsWith(options.sourceRoot())) {
                    Path relParent = options.sourceRoot().relativize(source).getParent();
                    if (relParent != null) {
                        dir = dir.resolve(relParent);
                    }
                }
                target = dir.resolve(newName);
            }
            plan.add(new Item(source, target));
        }
        return plan;
    }

    public Outcome run(Listener listener, BooleanSupplier cancelled) {
        List<String> errors = new ArrayList<>();
        int succeeded = 0;

        long total = 0;
        for (Item item : items) {
            try {
                total += Files.size(item.source());
            } catch (IOException e) {
                // reported when the file is processed
            }
        }

        Set<Path> sources = new HashSet<>();
        for (Item item : items) {
            sources.add(item.source().toAbsolutePath().normalize());
        }
        Map<Path, Integer> targetCount = new HashMap<>();
        for (Item item : items) {
            targetCount.merge(norm(item.target()), 1, Integer::sum);
        }

        // In place renames wait until everything is processed: source -> temp
        Map<Item, Path> pendingRenames = new LinkedHashMap<>();
        Map<Item, FileTime> pendingTimes = new HashMap<>();

        final long totalBytes = total;
        long done = 0;
        boolean wasCancelled = false;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (cancelled.getAsBoolean()) {
                wasCancelled = true;
                break;
            }
            listener.file(i, items.size(), item.source());
            Path source = item.source();
            Path target = item.target();
            String name = source.getFileName().toString();

            long size;
            try {
                size = Files.size(source);
            } catch (IOException e) {
                errors.add(name + ": cannot read (" + e.getMessage() + ")");
                continue;
            }

            if (targetCount.get(norm(target)) > 1) {
                errors.add(name + ": another selected file would also be written to " + target.getFileName());
                done += size;
                listener.bytes(done, totalBytes);
                continue;
            }
            boolean renamed = !norm(target).equals(norm(source));
            if (!options.inPlace() && !renamed) {
                errors.add(name + ": the output folder is the same as the source folder");
                done += size;
                listener.bytes(done, totalBytes);
                continue;
            }
            if (renamed && Files.exists(target)
                    && !(options.inPlace() && sources.contains(norm(target)))) {
                errors.add(name + ": " + target + " already exists");
                done += size;
                listener.bytes(done, totalBytes);
                continue;
            }

            Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
            final long base = done;
            Set<PosixFilePermission> copyPermissions = null;
            try {
                FileTime modified = Files.getLastModifiedTime(source);
                FileTime newModified = options.shiftModifiedTime()
                        ? FileTime.fromMillis(modified.toMillis() + options.offset().millis())
                        : modified;

                copyPermissions = permissions(source);
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                SudTimeShifter.shift(source, temp, options.offset(),
                        bytes -> listener.bytes(base + bytes, totalBytes), cancelled);
                if (copyPermissions != null) {
                    Files.setPosixFilePermissions(temp, copyPermissions);
                }

                if (options.inPlace() && renamed) {
                    pendingRenames.put(item, temp);
                    pendingTimes.put(item, newModified);
                } else {
                    move(temp, target);
                    Files.setLastModifiedTime(target, newModified);
                    succeeded++;
                }
            } catch (CancellationException e) {
                deleteQuietly(temp);
                wasCancelled = true;
                break;
            } catch (Exception e) {
                deleteQuietly(temp);
                errors.add(name + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            done = base + size;
            listener.bytes(done, totalBytes);
        }

        if (wasCancelled) {
            for (Path temp : pendingRenames.values()) {
                deleteQuietly(temp);
            }
            return new Outcome(succeeded, errors, true);
        }
        succeeded += finishRenames(pendingRenames, pendingTimes, errors);
        return new Outcome(succeeded, errors, false);
    }

    /**
     * Swap the processed temps in under their new names. A rename may only take a name that
     * is free, or that belongs to a file which is itself successfully moving away.
     */
    private int finishRenames(Map<Item, Path> pending, Map<Item, FileTime> times, List<String> errors) {
        if (pending.isEmpty()) {
            return 0;
        }
        Map<Item, Path> ok = new LinkedHashMap<>(pending);
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<Path> vacating = new HashSet<>();
            for (Item item : ok.keySet()) {
                vacating.add(norm(item.source()));
            }
            for (Item item : new ArrayList<>(ok.keySet())) {
                if (Files.exists(item.target()) && !vacating.contains(norm(item.target()))) {
                    errors.add(item.source().getFileName() + ": " + item.target().getFileName()
                            + " already exists; original left unchanged");
                    deleteQuietly(ok.remove(item));
                    changed = true;
                }
            }
        }

        // All originals in the set go first, so no new name lands on a file still in use.
        for (Item item : ok.keySet()) {
            try {
                Files.delete(item.source());
            } catch (IOException e) {
                errors.add(item.source().getFileName() + ": could not remove original (" + e.getMessage() + ")");
            }
        }
        int count = 0;
        for (Map.Entry<Item, Path> e : ok.entrySet()) {
            Item item = e.getKey();
            try {
                move(e.getValue(), item.target());
                Files.setLastModifiedTime(item.target(), times.get(item));
                count++;
            } catch (IOException ex) {
                errors.add(item.source().getFileName() + ": shifted copy kept as " + e.getValue().getFileName()
                        + " (" + ex.getMessage() + ")");
            }
        }
        return count;
    }

    /** The source's POSIX permissions, or null on file systems without them (Windows). */
    private static Set<PosixFilePermission> permissions(Path source) {
        try {
            return Files.getPosixFilePermissions(source);
        } catch (UnsupportedOperationException | IOException e) {
            return null;
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path norm(Path p) {
        return p.toAbsolutePath().normalize();
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // best effort
        }
    }
}
