package io.github.russianranger.lsb.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.DirectoryStream;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.HashSet;
import java.util.Properties;

/**
 * Recoverable replacement of the app's internal files and managed storage.
 * The caller must stop runtimes and serialize all readers/writers for the entire
 * begin/extract/activate/settings/finish sequence. Only trusted, app-owned paths
 * belong in this API; archive entry names must never select transaction paths.
 *
 * A process death before the durable commit marker restores both original roots.
 * After that marker, recover() retains the restored roots AND rollback copies
 * until the caller has applied its settings marker and calls finish(). Settings
 * therefore remain replayable if Android kills the process while applying them.
 */
public final class SessionTransaction {
    public interface Progress { void update(String step) throws IOException; }

    public static final class Stage {
        private final File files, storage;
        private Stage(File files, File storage) { this.files = files; this.storage = storage; }
        public File files() { return files; }
        public File storage() { return storage; }
    }

    private static final LinkOption[] NO_FOLLOW = { LinkOption.NOFOLLOW_LINKS };
    private final Path files, storage, work, filesStage, storageStage, filesPrevious,
            storagePrevious, journal, journalTemp, nestedStorage;

    public SessionTransaction(File filesRoot, File storageRoot, File workDir) throws IOException {
        files = trusted(filesRoot);
        storage = trusted(storageRoot);
        work = trusted(workDir);
        if (files.equals(storage) || files.startsWith(storage))
            throw new IOException("Session storage must be separate from, or inside, internal files");
        if (work.startsWith(files) || work.startsWith(storage) || files.startsWith(work) || storage.startsWith(work))
            throw new IOException("Session transfer directory must be outside both live roots");
        nestedStorage = storage.startsWith(files) ? files.relativize(storage) : null;
        filesStage = work.resolve("files-stage");
        filesPrevious = work.resolve("files-previous");
        if (nestedStorage == null) {
            if (storage.getParent() == null) throw new IOException("Invalid managed storage root");
            String name = storage.getFileName().toString();
            storageStage = storage.resolveSibling("." + name + "-session-stage");
            storagePrevious = storage.resolveSibling("." + name + "-session-previous");
        } else {
            storageStage = work.resolve("storage-stage");
            storagePrevious = work.resolve("storage-previous");
        }
        journal = work.resolve("transaction.properties");
        journalTemp = work.resolve("transaction.properties.tmp");
        if (storageStage.startsWith(files) || storagePrevious.startsWith(files)
                || work.startsWith(storageStage) || work.startsWith(storagePrevious))
            throw new IOException("Session staging paths overlap live files");
    }

    /** Both returned staging directories are empty, distinct and non-nested. */
    public synchronized Stage begin() throws IOException {
        if (exists(journal)) throw new IOException("A session restore is pending recovery");
        checkLiveRoot(files);
        checkLiveRoot(storage);
        ensureDirectory(work);
        for (Path path : new Path[] { filesStage, filesPrevious, storageStage, storagePrevious }) {
            if (exists(path)) throw new IOException("Unexpected previous session transfer data; recovery is required");
        }
        Properties state = new Properties();
        state.setProperty("format", "lsb-session-transaction-v1");
        state.setProperty("files", files.toString());
        state.setProperty("storage", storage.toString());
        state.setProperty("work", work.toString());
        state.setProperty("filesExisted", Boolean.toString(exists(files)));
        state.setProperty("storageExisted", Boolean.toString(exists(storage)));
        save(state, "STAGING");
        try {
            ensureDirectory(filesStage);
            ensureDirectory(storageStage);
        } catch (IOException failure) {
            try { abort(); } catch (IOException recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
        return new Stage(filesStage.toFile(), storageStage.toFile());
    }

    public synchronized void activate() throws IOException { activate(null); }

    /** Activate only after the caller has verified the entire staged archive. */
    public synchronized void activate(Progress progress) throws IOException {
        Properties state = read();
        if (!"STAGING".equals(state.getProperty("phase")))
            throw new IOException("Session restore is not ready for activation");
        requireDirectory(filesStage);
        requireDirectory(storageStage);
        if (exists(files) != flag(state, "filesExisted") || exists(storage) != flag(state, "storageExisted"))
            throw new IOException("Live session changed during restore");
        checkLiveRoot(files);
        checkLiveRoot(storage);
        try {
            if (nestedStorage != null) {
                Path destination = filesStage.resolve(nestedStorage);
                if (exists(destination)) throw new IOException("Backup contains overlapping managed storage");
                ensureParentsInside(filesStage, destination.getParent());
                move(storageStage, destination);
                report(progress, "storage-merged");
            }
            save(state, "PREPARED");
            report(progress, "prepared");
            activateRoot(files, filesStage, filesPrevious, flag(state, "filesExisted"), "files", progress);
            if (nestedStorage == null)
                activateRoot(storage, storageStage, storagePrevious, flag(state, "storageExisted"), "storage", progress);
            report(progress, "before-commit");
            save(state, "COMMITTED");
        } catch (IOException | RuntimeException failure) {
            try { recover(); } catch (IOException recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
    }

    /**
     * Recover before creating runtime/store instances. Returns true when restored
     * data is committed and settings replay plus finish() are still required.
     * A committed restore is never rolled back, even if settings application fails.
     */
    public synchronized boolean recover() throws IOException {
        if (!exists(journal)) return false;
        Properties state = read();
        String phase = state.getProperty("phase");
        if ("COMMITTED".equals(phase)) {
            requireDirectory(files);
            requireDirectory(storage);
            return true;
        }
        if ("PREPARED".equals(phase)) {
            if (nestedStorage == null)
                rollbackRoot(storage, storageStage, storagePrevious, flag(state, "storageExisted"));
            rollbackRoot(files, filesStage, filesPrevious, flag(state, "filesExisted"));
            save(state, "ABORTED");
        } else if ("STAGING".equals(phase)) {
            if (exists(filesPrevious) || exists(storagePrevious))
                throw new IOException("Unexpected rollback data before restore activation");
            save(state, "ABORTED");
        }
        cleanupAborted();
        return false;
    }

    /** Discard an uncommitted restore; committed data always requires finish(). */
    public synchronized void abort() throws IOException {
        if (recover()) throw new IOException("Restore is already committed; apply settings and finish recovery");
    }

    /** Call only after settings were successfully applied (or replayed on startup). */
    public synchronized void finish() throws IOException {
        if (!exists(journal)) return;
        Properties state = read();
        if (!"COMMITTED".equals(state.getProperty("phase")))
            throw new IOException("Session restore has not committed");
        requireDirectory(files);
        requireDirectory(storage);
        deleteTree(filesPrevious);
        deleteTree(storagePrevious);
        deleteTree(filesStage);
        deleteTree(storageStage);
        removeJournal();
    }

    private static void activateRoot(Path live, Path staged, Path previous, boolean existed,
            String label, Progress progress) throws IOException {
        if (existed) {
            move(live, previous);
            report(progress, label + "-saved");
        }
        move(staged, live);
        report(progress, label + "-installed");
    }

    private static void rollbackRoot(Path live, Path staged, Path previous, boolean existed) throws IOException {
        if (existed) {
            if (exists(previous)) {
                requireDirectory(previous);
                if (exists(live)) {
                    if (exists(staged)) removeEmptyPlaceholder(live);
                    else move(live, staged);
                }
                move(previous, live);
            } else if (!exists(live) || !exists(staged)) {
                throw new IOException("Original session data could not be located; transfer data was retained");
            }
            requireDirectory(live);
        } else {
            if (exists(previous)) throw new IOException("Unexpected original session data during recovery");
            if (exists(staged)) {
                if (exists(live)) removeEmptyPlaceholder(live);
            } else if (exists(live)) {
                move(live, staged);
            } else {
                throw new IOException("Staged session data could not be located; transfer data was retained");
            }
        }
    }

    private void cleanupAborted() throws IOException {
        if (exists(filesPrevious) || exists(storagePrevious))
            throw new IOException("Original session data still requires recovery");
        deleteTree(filesStage);
        deleteTree(storageStage);
        removeJournal();
    }

    private void removeJournal() throws IOException {
        Files.deleteIfExists(journalTemp);
        Files.delete(journal);
        syncDirectory(work);
    }

    private Properties read() throws IOException {
        if (Files.isSymbolicLink(journal) || !Files.isRegularFile(journal, NO_FOLLOW) || Files.size(journal) > 16384)
            throw new IOException("Invalid session restore journal");
        Properties state = new Properties();
        try (FileInputStream in = new FileInputStream(journal.toFile())) { state.load(in); }
        if (!"lsb-session-transaction-v1".equals(state.getProperty("format"))
                || !files.toString().equals(state.getProperty("files"))
                || !storage.toString().equals(state.getProperty("storage"))
                || !work.toString().equals(state.getProperty("work")))
            throw new IOException("Session restore journal does not match these app directories");
        String phase = state.getProperty("phase", "");
        if (!phase.equals("STAGING") && !phase.equals("PREPARED") && !phase.equals("COMMITTED") && !phase.equals("ABORTED"))
            throw new IOException("Unknown session restore journal phase");
        flag(state, "filesExisted");
        flag(state, "storageExisted");
        return state;
    }

    private void save(Properties state, String phase) throws IOException {
        state.setProperty("phase", phase);
        Files.deleteIfExists(journalTemp);
        try (FileOutputStream out = new FileOutputStream(journalTemp.toFile())) {
            state.store(out, "App-owned session restore recovery journal");
            out.getFD().sync();
        }
        Files.move(journalTemp, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(work);
    }

    private static boolean flag(Properties state, String name) throws IOException {
        String value = state.getProperty(name);
        if (!"true".equals(value) && !"false".equals(value)) throw new IOException("Invalid session restore journal flag");
        return Boolean.parseBoolean(value);
    }

    private static Path trusted(File path) throws IOException {
        if (Files.isSymbolicLink(path.toPath())) throw new IOException("Session root must not be a symbolic link");
        Path result = path.getCanonicalFile().toPath();
        if (result.getParent() == null) throw new IOException("Session root must not be a filesystem root");
        return result;
    }

    private static boolean exists(Path path) { return Files.exists(path, NO_FOLLOW); }

    private static void checkLiveRoot(Path path) throws IOException {
        if (exists(path)) requireDirectory(path);
    }

    private static void requireDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, NO_FOLLOW)) throw new IOException("Session path is not a real directory: " + path.getFileName());
    }

    private static void ensureDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        requireDirectory(path);
    }

    private static void ensureParentsInside(Path root, Path parent) throws IOException {
        Path current = root;
        for (Path component : root.relativize(parent)) {
            current = current.resolve(component);
            if (!exists(current)) Files.createDirectory(current);
            requireDirectory(current);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        if (exists(to)) throw new IOException("Session move destination already exists: " + to.getFileName());
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        syncDirectory(from.getParent());
        if (!from.getParent().equals(to.getParent())) syncDirectory(to.getParent());
    }

    private static void report(Progress progress, String step) throws IOException {
        if (progress != null) progress.update(step);
    }

    private static void syncDirectory(Path path) {
        // Android filesystems differ in support for opening directories. The
        // journal itself is always fsynced; directory sync is an extra safeguard.
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
        catch (IOException | UnsupportedOperationException ignored) { }
    }

    private static void removeEmptyPlaceholder(Path live) throws IOException {
        // Android may recreate filesDir before Activity recovery runs. Only an
        // empty real directory is disposable; unexpected files must be retained.
        requireDirectory(live);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(live)) {
            if (children.iterator().hasNext())
                throw new IOException("Ambiguous session recovery; unexpected live data was retained");
        }
        Files.delete(live);
        syncDirectory(live.getParent());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!exists(root)) return;
        if (Files.isDirectory(root, NO_FOLLOW)) {
            // Restored prefixes may contain read-only directories. Grant only
            // this app's owner access before cleanup, never following links.
            try {
                Set<PosixFilePermission> mode = new HashSet<>(Files.getPosixFilePermissions(root, NO_FOLLOW));
                boolean changed = mode.add(PosixFilePermission.OWNER_READ);
                changed |= mode.add(PosixFilePermission.OWNER_WRITE);
                changed |= mode.add(PosixFilePermission.OWNER_EXECUTE);
                if (changed) Files.setPosixFilePermissions(root, mode);
            } catch (IOException | UnsupportedOperationException unsupported) {
                // Emulated external storage may report POSIX modes but reject
                // chmod. Its existing owner access usually already permits removal.
                File directory = root.toFile();
                if (!directory.canRead()) directory.setReadable(true, true);
                if (!directory.canWrite()) directory.setWritable(true, true);
                if (!directory.canExecute()) directory.setExecutable(true, true);
            }
            try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
                for (Path child : children) deleteTree(child);
            }
        }
        Files.delete(root);
    }
}
