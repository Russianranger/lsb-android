import io.github.russianranger.lsb.core.SessionTransaction;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;

/** Exercises interrupted renames against real directories, without Android. */
public final class SessionTransactionTest {
    private static int checks;
    private static final class Crash extends Error { }
    private interface Action { void run() throws Exception; }
    private static final class Fixture implements AutoCloseable {
        final Path home, files, managed, work, sentinel;
        Fixture(boolean nested, boolean oldFiles, boolean oldManaged) throws Exception {
            home = Files.createTempDirectory("lsb-session-transaction-");
            files = home.resolve("private/files");
            managed = nested ? files.resolve("lsb") : home.resolve("external/files/lsb");
            work = home.resolve("private/no_backup/session-transfer");
            sentinel = home.resolve("untouched.txt");
            put(sentinel, "outside");
            Files.createDirectories(files.getParent());
            if (oldFiles) {
                put(files.resolve("settings.json"), "old settings");
                Files.createSymbolicLink(files.resolve("guest-root-link"), home);
            }
            if (oldManaged) put(managed.resolve("client.dat"), "old client");
        }
        SessionTransaction transaction() throws Exception {
            return new SessionTransaction(files.toFile(), managed.toFile(), work.toFile());
        }
        SessionTransaction prepared() throws Exception {
            SessionTransaction tx = transaction();
            SessionTransaction.Stage stage = tx.begin();
            check(!stage.files().toPath().startsWith(stage.storage().toPath())
                    && !stage.storage().toPath().startsWith(stage.files().toPath()), "staging roots never overlap");
            put(stage.files().toPath().resolve("settings.json"), "new settings");
            put(stage.files().toPath().resolve("session-restore-settings.json"), "settings replay marker");
            put(stage.storage().toPath().resolve("client.dat"), "new client");
            Files.createSymbolicLink(stage.files().toPath().resolve("guest-root-link"), home);
            return tx;
        }
        void old(boolean hadFiles, boolean hadManaged) throws Exception {
            check(Files.exists(files) == hadFiles, "original files presence restored");
            check(Files.exists(managed) == hadManaged, "original managed presence restored");
            if (hadFiles) check(read(files.resolve("settings.json")).equals("old settings"), "original settings intact");
            if (hadManaged) check(read(managed.resolve("client.dat")).equals("old client"), "original client intact");
            check(read(sentinel).equals("outside"), "recovery never traverses guest symlinks");
        }
        void restored() throws Exception {
            check(read(files.resolve("settings.json")).equals("new settings"), "restored internal files active");
            check(read(managed.resolve("client.dat")).equals("new client"), "restored managed files active");
            check(read(sentinel).equals("outside"), "cleanup preserves symlink targets");
        }
        public void close() throws Exception { delete(home); }
    }
    public static void main(String[] args) throws Exception {
        for (boolean nested : new boolean[] { false, true }) {
            try (Fixture f = new Fixture(nested, true, true)) {
                SessionTransaction tx = f.prepared();
                fails(() -> f.transaction().begin(), "another restore cannot replace pending stage");
                tx.activate();
                f.restored();
                check(Files.exists(f.work.resolve("files-previous/settings.json")), "activation retains old session until settings apply");
                SessionTransaction restarted = f.transaction();
                check(restarted.recover(), "committed restore requires settings replay after restart");
                check(restarted.recover(), "settings replay failure retains committed restore on retry");
                fails(() -> restarted.abort(), "committed session cannot be aborted");
                f.restored();
                Files.delete(f.files.resolve("session-restore-settings.json"));
                restarted.finish();
                check(!Files.exists(f.work.resolve("files-previous")), "finish removes prior session only after settings replay");
                check(!restarted.recover(), "finished restore does not replay again");
                restarted.finish();
                f.restored();
            }
            String[] steps = nested
                ? new String[] { "storage-merged", "prepared", "files-saved", "files-installed", "before-commit" }
                : new String[] { "prepared", "files-saved", "files-installed", "storage-saved", "storage-installed", "before-commit" };
            for (String point : steps) {
                try (Fixture f = new Fixture(nested, true, true)) {
                    SessionTransaction tx = f.prepared();
                    crash(() -> tx.activate(step -> { if (step.equals(point)) throw new Crash(); }), "process death at " + point);
                    check(!f.transaction().recover(), "uncommitted restore rolls back after " + point);
                    f.old(true, true);
                    check(!f.transaction().recover(), "recovery is idempotent after " + point);
                }
            }
            try (Fixture f = new Fixture(nested, true, true)) {
                SessionTransaction tx = f.prepared();
                fails(() -> tx.activate(step -> { if (step.equals("files-installed")) throw new IOException("cancelled"); }), "cancellation during activation rolls back");
                f.old(true, true);
                check(!f.transaction().recover(), "cancelled activation leaves no pending journal");
            }
            try (Fixture f = new Fixture(nested, true, true)) {
                SessionTransaction tx = f.prepared();
                tx.abort();
                f.old(true, true);
                check(!f.transaction().recover(), "staged archive cancellation leaves live session unchanged");
            }
        }
        for (boolean hadFiles : new boolean[] { false, true }) {
            for (boolean hadManaged : new boolean[] { false, true }) {
                for (String point : new String[] { "prepared", "files-installed", "storage-installed", "before-commit" }) {
                    try (Fixture f = new Fixture(false, hadFiles, hadManaged)) {
                        SessionTransaction tx = f.prepared();
                        crash(() -> tx.activate(step -> { if (step.equals(point)) throw new Crash(); }), "mixed empty roots process death");
                        f.transaction().recover();
                        f.old(hadFiles, hadManaged);
                    }
                }
                try (Fixture f = new Fixture(false, hadFiles, hadManaged)) {
                    SessionTransaction tx = f.prepared();
                    tx.activate();
                    check(f.transaction().recover(), "restore commits with independently absent original roots");
                    f.transaction().finish();
                    f.restored();
                }
            }
        }
        try (Fixture f = new Fixture(true, false, false)) {
            SessionTransaction tx = f.prepared();
            crash(() -> tx.activate(step -> { if (step.equals("files-installed")) throw new Crash(); }), "fresh fallback restore process death");
            f.transaction().recover();
            f.old(false, false);
            SessionTransaction next = f.prepared();
            next.activate(); next.finish(); f.restored();
        }
        for (boolean nested : new boolean[] { false, true }) {
            try (Fixture f = new Fixture(nested, true, true)) {
                SessionTransaction tx = f.prepared();
                crash(() -> tx.activate(step -> { if (step.equals("files-saved")) throw new Crash(); }), "Android restart between internal renames");
                Files.createDirectory(f.files); // Context.getFilesDir() may do this before Activity startup.
                check(!f.transaction().recover(), "empty Android files placeholder permits rollback");
                f.old(true, true);
            }
        }
        try (Fixture f = new Fixture(false, true, true)) {
            SessionTransaction tx = f.prepared();
            crash(() -> tx.activate(step -> { if (step.equals("storage-saved")) throw new Crash(); }), "restart between managed renames");
            Files.createDirectory(f.managed);
            f.transaction().recover();
            f.old(true, true);
        }
        try (Fixture f = new Fixture(false, false, false)) {
            SessionTransaction tx = f.prepared();
            crash(() -> tx.activate(step -> { if (step.equals("prepared")) throw new Crash(); }), "fresh app activation interrupted");
            Files.createDirectory(f.files); Files.createDirectory(f.managed);
            f.transaction().recover();
            f.old(false, false);
        }
        try (Fixture f = new Fixture(false, true, true)) {
            SessionTransaction tx = f.prepared();
            crash(() -> tx.activate(step -> { if (step.equals("files-saved")) throw new Crash(); }), "restart with unexpected writes");
            put(f.files.resolve("unexpected.txt"), "keep unexpected live data");
            fails(() -> f.transaction().recover(), "nonempty live placeholder is never deleted");
            check(read(f.files.resolve("unexpected.txt")).equals("keep unexpected live data"), "unexpected live file retained");
            check(read(f.work.resolve("files-previous/settings.json")).equals("old settings"), "original retained alongside unexpected file");
            check(Files.exists(f.work.resolve("transaction.properties")), "ambiguous recovery retains journal");
            Files.delete(f.files.resolve("unexpected.txt"));
            f.transaction().recover();
            f.old(true, true);
        }
        try (Fixture f = new Fixture(false, true, true)) {
            Path immutable = f.files.resolve("prefix/read-only");
            put(immutable.resolve("payload"), "old prefix data");
            Files.createSymbolicLink(immutable.resolve("outside"), f.home);
            Files.setPosixFilePermissions(immutable, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
            SessionTransaction tx = f.prepared();
            tx.activate(); tx.finish();
            f.restored();
            check(!Files.exists(f.work.resolve("files-previous")), "read-only old directories can be removed after success");
            SessionTransaction cancelled = f.transaction();
            SessionTransaction.Stage stage = cancelled.begin();
            Path locked = stage.files().toPath().resolve("prefix/locked");
            put(locked.resolve("payload"), "incomplete restore");
            Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
            Files.setPosixFilePermissions(stage.files().toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
            cancelled.abort();
            f.restored();
            check(!Files.exists(f.work.resolve("files-stage")), "cancelled read-only staging is removed without touching live session");
        }
        try (Fixture f = new Fixture(true, true, true)) {
            SessionTransaction tx = f.transaction();
            SessionTransaction.Stage stage = tx.begin();
            put(stage.storage().toPath().resolve("client.dat"), "new client");
            Files.createSymbolicLink(stage.files().toPath().resolve("lsb"), f.home);
            fails(tx::activate, "overlapping nested managed tree is rejected");
            f.old(true, true);
        }
        try (Fixture f = new Fixture(false, true, true)) {
            SessionTransaction tx = f.prepared();
            Path journal = f.work.resolve("transaction.properties");
            Properties p = new Properties();
            try (java.io.InputStream in = Files.newInputStream(journal)) { p.load(in); }
            p.setProperty("files", f.home.resolve("other-app").toString());
            try (java.io.OutputStream out = Files.newOutputStream(journal)) { p.store(out, "malformed journal"); }
            fails(tx::recover, "journal paths cannot redirect recovery");
            f.old(true, true);
            check(Files.exists(f.work.resolve("files-stage/settings.json")), "invalid journal retains staged data for recovery");
        }
        try (Fixture f = new Fixture(false, true, true)) {
            fails(() -> new SessionTransaction(f.files.toFile(), f.managed.toFile(), f.files.resolve("transfer").toFile()), "reject transfer directory within live files");
            fails(() -> new SessionTransaction(f.files.toFile(), f.files.toFile(), f.work.toFile()), "reject identical live roots");
            Path alias = f.home.resolve("files-alias"); Files.createSymbolicLink(alias, f.files);
            fails(() -> new SessionTransaction(alias.toFile(), f.managed.toFile(), f.work.toFile()), "reject symbolic live root");
        }
        System.out.println("Completed " + checks + " session transaction recovery checks.");
    }
    private static void check(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }
    private static void fails(Action action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static void crash(Action action, String message) throws Exception {
        try { action.run(); } catch (Crash expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static String read(Path file) throws IOException { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
    private static void put(Path file, String data) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, data.getBytes(StandardCharsets.UTF_8));
    }
    private static void delete(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException { if (e != null) throw e; Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }
}
