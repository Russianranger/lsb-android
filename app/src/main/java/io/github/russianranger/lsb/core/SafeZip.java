package io.github.russianranger.lsb.core;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Streaming ZIP64-capable transport. Imports always target an isolated, empty directory. */
public final class SafeZip {
    public interface Progress { void update(String message); }
    public static final long MAX_BYTES = 80L * 1024 * 1024 * 1024;
    public static final long RESERVE_BYTES = 512L * 1024 * 1024;
    private SafeZip() {}
    public static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Operation cancelled");
    }
    public static long extract(InputStream source, File target, Progress progress) throws IOException {
        return extract(source, target, progress, MAX_BYTES, RESERVE_BYTES);
    }
    public static long extract(InputStream source, File target, Progress progress, long maxBytes, long reserve) throws IOException {
        FilesEx.mkdir(target);
        if (FilesEx.children(target).length != 0) throw new IOException("Import staging directory is not empty");
        Map<String, String> spellings = new HashMap<>();
        Set<String> fileNames = new HashSet<>();
        long bytes = 0, nextReport = 0; int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry e; byte[] buffer = new byte[262144];
            while ((e = zip.getNextEntry()) != null) {
                checkCancelled();
                if (++count > 500000) throw new IOException("Archive exceeds 500,000 entries");
                String name = safeName(e.getName());
                String[] parts = name.split("/"); String parent = "";
                for (String part : parts) {
                    parent += (parent.isEmpty() ? "" : "/") + part;
                    String lower = parent.toLowerCase(Locale.ROOT);
                    String old = spellings.put(lower, parent);
                    if (old != null && !old.equals(parent)) throw new IOException("Conflicting Windows filename casing: " + parent);
                }
                File out = new File(target, name);
                FilesEx.relative(target, out);
                if (e.isDirectory()) { FilesEx.mkdir(out); zip.closeEntry(); continue; }
                if (!fileNames.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate archive file: " + name);
                if (e.getSize() > maxBytes - bytes) throw new IOException("Archive exceeds available import budget");
                FilesEx.mkdir(out.getParentFile());
                try (OutputStream dest = new BufferedOutputStream(new FileOutputStream(out))) {
                    int n; while ((n = zip.read(buffer)) != -1) {
                        checkCancelled();
                        bytes += n;
                        if (bytes > maxBytes) throw new IOException("Archive exceeds import size limit");
                        if (out.getParentFile().getUsableSpace() < reserve + n) throw new IOException("Not enough free space; 512 MiB is reserved");
                        dest.write(buffer, 0, n);
                        if (bytes >= nextReport) { progress.update("Imported " + count + " files / " + (bytes / 1048576) + " MiB"); nextReport = bytes + 16 * 1048576; }
                    }
                }
                zip.closeEntry(); // verifies the entry CRC before any promotion
            }
        }
        if (count == 0) throw new IOException("Empty or invalid ZIP archive");
        return bytes;
    }
    public static String safeName(String name) throws IOException {
        name = name.replace('\\', '/');
        if (name.startsWith("/") || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0 || name.length() > 2048) throw new IOException("Unsafe archive path");
        while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty()) throw new IOException("Empty archive path");
        String[] parts = name.split("/", -1);
        if (parts.length > 64) throw new IOException("Archive nesting is too deep");
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".") || p.equals("..") || p.endsWith(".") || p.endsWith(" ")) throw new IOException("Unsafe archive path: " + name);
            for (char c : p.toCharArray()) if (c < 32) throw new IOException("Control character in path");
        }
        return name;
    }
    public static void writeTree(ZipOutputStream zip, File root, String prefix, Progress progress) throws IOException {
        walk(zip, root, root, prefix, progress, new long[2]);
    }
    private static void walk(ZipOutputStream zip, File root, File dir, String prefix, Progress progress, long[] stats) throws IOException {
        for (File file : FilesEx.children(dir)) {
            checkCancelled();
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) throw new IOException("Cannot export symbolic links");
            if (file.isDirectory()) {
                if (FilesEx.children(file).length == 0) { zip.putNextEntry(new ZipEntry(prefix + FilesEx.relative(root, file) + "/")); zip.closeEntry(); }
                else walk(zip, root, file, prefix, progress, stats);
                continue;
            }
            String name = prefix + FilesEx.relative(root, file);
            zip.putNextEntry(new ZipEntry(name));
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                byte[] b = new byte[262144]; int n;
                while ((n = in.read(b)) != -1) { checkCancelled(); zip.write(b, 0, n); stats[1] += n; }
            }
            zip.closeEntry();
            if (++stats[0] % 100 == 0) progress.update("Exported " + stats[0] + " files / " + stats[1] / 1048576 + " MiB");
        }
    }
    public static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(safeName(name)));
        zip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
