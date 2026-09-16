package io.github.russianranger.lsb;

import io.github.russianranger.lsb.core.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;

/** Source acquisition only. A source snapshot is never reported as a compiled server. */
final class SourceImport {
    static String download(File home, String repository, String ref, SafeZip.Progress progress) throws Exception {
        String clean = repository.trim().replaceFirst("^https://github.com/", "").replaceFirst("\\.git/?$", "").replaceFirst("/$", "");
        if (!clean.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IOException("Enter a GitHub owner/repository or its HTTPS repository URL");
        if (!ref.matches("[A-Za-z0-9_./-]+") || ref.contains("..")) throw new IOException("Invalid branch, tag, or commit");
        String base = "https://api.github.com/repos/" + clean;
        String encoded = URLEncoder.encode(ref, "UTF-8").replace("+", "%20");
        String sha;
        HttpURLConnection meta = connect(base + "/commits/" + encoded);
        try (InputStream in = meta.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) { SafeZip.checkCancelled(); bytes.write(b, 0, n); if (bytes.size() > 8 * 1048576) throw new IOException("GitHub metadata exceeds limit"); }
            sha = new JSONObject(bytes.toString("UTF-8")).getString("sha");
        } finally { meta.disconnect(); }
        if (!sha.matches("[0-9a-f]{40}")) throw new IOException("GitHub did not return a commit SHA");
        progress.update("Downloading source at " + sha.substring(0, 12));
        HttpURLConnection archive = connect(base + "/zipball/" + sha);
        try (InputStream in = archive.getInputStream()) { return stage(home, in, clean + " @ " + sha, progress); }
        finally { archive.disconnect(); }
    }
    static HttpURLConnection connect(String address) throws IOException {
        for (int count = 0; count < 6; count++) {
            URL url = new URL(address);
            if (!url.getProtocol().equals("https") || !Arrays.asList("api.github.com", "codeload.github.com", "github.com").contains(url.getHost())) throw new IOException("Unexpected GitHub redirect host");
            HttpURLConnection c = (HttpURLConnection)url.openConnection(); c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(false); c.setRequestProperty("User-Agent", "LSB-Android/0.1.2");
            int status = c.getResponseCode();
            if (status >= 300 && status < 400) { String location = c.getHeaderField("Location"); c.disconnect(); if (location == null) throw new IOException("Missing redirect location"); address = new URL(url, location).toString(); continue; }
            if (status != 200) { c.disconnect(); throw new IOException("GitHub HTTP " + status + ". Check the repository/ref, rate limit, or use a ZIP."); }
            return c;
        }
        throw new IOException("Too many GitHub redirects");
    }
    static String stage(File home, InputStream in, String origin, SafeZip.Progress progress) throws Exception {
        FilesEx.mkdir(home);
        File current = new File(home, "current"), previous = new File(home, "previous"), incoming = new File(home, "incoming");
        if (!current.exists() && previous.exists()) FilesEx.move(previous, current);
        FilesEx.delete(incoming); FilesEx.mkdir(incoming);
        try {
            SafeZip.extract(in, incoming, progress);
            File source = unwrap(incoming);
            if (!new File(source, "CMakeLists.txt").isFile() || !new File(source, "src").isDirectory() || !new File(source, "sql").isDirectory()) throw new IOException("Expected a LandSandBoat source ZIP containing CMakeLists.txt, src/ and sql/");
            String report = "Source: " + origin + "\nStatus: source staged; compiler and server runtime are not installed in 0.1.2.\n";
            for (String mesh : Arrays.asList("navmeshes", "ximeshes")) {
                File dir = new File(source, mesh); report += mesh + ": " + (dir.isDirectory() && FilesEx.children(dir).length > 0 ? "directory present (content not verified)" : "missing; GitHub source ZIPs do not include submodule contents") + "\n";
            }
            FilesEx.text(new File(incoming, "source-report.txt"), report);
            SafeZip.checkCancelled(); FilesEx.delete(previous); if (current.exists()) FilesEx.move(current, previous);
            try { FilesEx.move(incoming, current); }
            catch (IOException e) { if (!current.exists() && previous.exists()) FilesEx.move(previous, current); throw e; }
            return report;
        } finally { FilesEx.delete(incoming); }
    }
    private static File unwrap(File dir) throws IOException {
        for (int i = 0; i < 8; i++) {
            if (new File(dir, "CMakeLists.txt").isFile()) return dir;
            File[] children = FilesEx.children(dir);
            if (children.length != 1 || !children[0].isDirectory()) break;
            dir = children[0];
        }
        return dir;
    }
}
