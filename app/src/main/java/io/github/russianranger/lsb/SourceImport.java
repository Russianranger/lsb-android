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
            HttpURLConnection c = (HttpURLConnection)url.openConnection(); c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(false); c.setRequestProperty("User-Agent", "LSB-Android/0.2.0");
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
            String report = "Source: " + origin + "\nStatus: matching server snapshot selected; active deployment is unchanged.\n";
            report += "Expected client: " + expectedClient(source) + "\n";
            int binaries=0;for(String name:new String[]{"xi_connect","xi_map","xi_search","xi_world"})if(new File(source,name).isFile())binaries++;
            report += "Server-root binaries: " + binaries + "/4. Deployment also checks build/ for a unique missing binary and verifies Linux ARM64 dependencies.\n";
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
        List<File> candidates=new ArrayList<>();findSources(dir,8,candidates);
        if(candidates.size()!=1)throw new IOException("Choose a ZIP with exactly one complete server folder containing CMakeLists.txt, src/ and sql/");
        return candidates.get(0);
    }
    private static void findSources(File dir,int depth,List<File> candidates)throws IOException {
        SafeZip.checkCancelled();
        if(new File(dir,"CMakeLists.txt").isFile()&&new File(dir,"src").isDirectory()&&new File(dir,"sql").isDirectory()){candidates.add(dir);return;}
        if(depth>0)for(File child:FilesEx.children(dir))if(child.isDirectory()&&!Arrays.asList(".git","build","ext").contains(child.getName()))findSources(child,depth-1,candidates);
    }
    private static String expectedClient(File source)throws IOException {
        String version="unknown; inspect the imported settings before updating";
        java.util.regex.Pattern field=java.util.regex.Pattern.compile("(?m)^\\s*CLIENT_VER\\s*=\\s*['\"]([0-9]{8}_[0-9]+)['\"]");
        for(String name:new String[]{"settings/default/login.lua","settings/login.lua"}){
            File file=new File(source,name);if(!file.isFile())continue;
            java.util.regex.Matcher match=field.matcher(FilesEx.read(file,131072));if(match.find())version=match.group(1);
        }
        return version;
    }
}
