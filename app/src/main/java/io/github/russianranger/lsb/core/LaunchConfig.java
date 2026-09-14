package io.github.russianranger.lsb.core;

import java.io.*;
import java.util.*;

public final class LaunchConfig {
    public final String host, region;
    public LaunchConfig(String host, String region) {
        if (host == null || host.length() > 253 || !host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")) throw new IllegalArgumentException("Enter an IPv4 address or hostname without spaces, a port, or command arguments");
        if (!Arrays.asList("US", "EU", "JP").contains(region)) throw new IllegalArgumentException("Choose US, EU, or JP");
        this.host = host; this.region = region;
    }
    public int language() { return region.equals("JP") ? 0 : region.equals("US") ? 1 : 2; }
    public String registry() { return "SOFTWARE\\PlayOnline" + (region.equals("JP") ? "" : region); }
    public String properties() { return "schema=1\nhost=" + host + "\nregion=" + region + "\n"; }
    public static LaunchConfig load(File f) throws IOException {
        if (!f.exists()) return new LaunchConfig("127.0.0.1", "US");
        Properties p = new Properties(); try (Reader r = new StringReader(FilesEx.read(f, 4096))) { p.load(r); }
        return new LaunchConfig(p.getProperty("host", "127.0.0.1"), p.getProperty("region", "US"));
    }
}
