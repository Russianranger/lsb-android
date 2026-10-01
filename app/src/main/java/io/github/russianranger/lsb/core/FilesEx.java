package io.github.russianranger.lsb.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

public final class FilesEx {
    private FilesEx() {}
    public static void mkdir(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create directory: " + dir.getName());
    }
    public static void delete(File file) throws IOException {
        if (!file.exists()) return;
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("Unexpected symbolic link");
        if (file.isDirectory()) for (File child : children(file)) delete(child);
        if (!file.delete()) throw new IOException("Cannot remove " + file.getName());
    }
    public static File[] children(File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("Cannot read directory " + dir.getName());
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }
    public static void move(File from, File to) throws IOException {
        if (to.exists() || !from.renameTo(to)) throw new IOException("Cannot move " + from.getName() + " to " + to.getName());
    }
    public static void text(File file, String text) throws IOException {
        mkdir(file.getParentFile());
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
        Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    public static String read(File file, int max) throws IOException {
        if (file.length() > max) throw new IOException("Text file is too large");
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    public static String relative(File root, File file) throws IOException {
        String base = root.getCanonicalPath() + File.separator;
        String path = file.getCanonicalPath();
        if (!path.startsWith(base)) throw new IOException("Path outside client directory");
        return path.substring(base.length()).replace(File.separatorChar, '/');
    }
    public static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[262144]; int n;
            while ((n = in.read(buf)) != -1) { SafeZip.checkCancelled(); digest.update(buf, 0, n); }
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    public static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) { case '\\': b.append("\\\\"); break; case '"': b.append("\\\""); break;
                case '\n': b.append("\\n"); break; case '\r': b.append("\\r"); break; case '\t': b.append("\\t"); break;
                default: if (c < 32) b.append(String.format(Locale.ROOT, "\\u%04x", (int)c)); else b.append(c); }
        }
        return b.append('"').toString();
    }
}
