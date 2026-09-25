package io.github.russianranger.lsb;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Per-session guest network files; never overwrite the runtime or Android configuration. */
final class ClientNetwork {
    private static final String UNAVAILABLE="PlayOnline needs network DNS settings. Connect to Wi-Fi or mobile data, then open PlayOnline again.";
    private static final String HOSTS="127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n";

    static void prepare(Context context,File run,File logs,String session)throws IOException {
        long started=SystemClock.elapsedRealtime();List<InetAddress> addresses=null;boolean privateDns=false;
        try {
            ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network=manager==null?null:manager.getActiveNetwork();
            LinkProperties properties=network==null?null:manager.getLinkProperties(network);
            if(properties!=null){
                addresses=properties.getDnsServers();
                privateDns=Build.VERSION.SDK_INT>=28&&properties.isPrivateDnsActive();
            }
        }catch(RuntimeException unavailable){
            // Platform errors can contain network names or addresses. Retain
            // only the fixed availability result, never the exception text.
        }
        prepare(addresses,privateDns,run,logs,session,started);
    }

    static void prepare(List<InetAddress> addresses,boolean privateDns,File run,File logs,String session)throws IOException {
        prepare(addresses,privateDns,run,logs,session,SystemClock.elapsedRealtime());
    }

    private static String literal(InetAddress address){
        if(address==null||address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isMulticastAddress())return null;
        if(address instanceof Inet6Address){
            Inet6Address v6=(Inet6Address)address;
            if(v6.getScopeId()!=0||v6.getScopedInterface()!=null||v6.isLinkLocalAddress())return null;
        }
        String value=address.getHostAddress();
        // getHostAddress never performs reverse DNS. Restrict its representation
        // again before placing it in a line-oriented resolver configuration.
        if(value==null||value.length()>45||!value.matches("[0-9A-Fa-f:.]+")||value.equals("255.255.255.255"))return null;
        return value;
    }

    private static void prepare(List<InetAddress> addresses,boolean privateDns,File run,File logs,String session,long started)throws IOException {
        Set<String> selected=new LinkedHashSet<>();
        if(addresses!=null){
            int scanned=0;
            for(InetAddress address:addresses){
                if(++scanned>64||selected.size()==3)break;
                String value=literal(address);if(value!=null)selected.add(value);
            }
        }
        try {
            // Do not leave an earlier session's resolver usable after a failed
            // preparation. Both paths belong solely to this private run folder.
            Files.deleteIfExists(new File(run,"resolv.conf").toPath());
            Files.deleteIfExists(new File(run,"hosts").toPath());
            if(!selected.isEmpty()){
                StringBuilder resolver=new StringBuilder();
                for(String address:selected)resolver.append("nameserver ").append(address).append('\n');
                resolver.append("options timeout:2 attempts:2\n");
                // This config uses the active link's DNS endpoints. glibc does
                // not implement Android's Private DNS/DoT policy; a future DNS
                // bridge must handle that separately. No public DNS fallback.
                ClientRuntime.write(new File(run,"resolv.conf"),resolver.toString());
                ClientRuntime.write(new File(run,"hosts"),HOSTS);
            }
            JSONObject receipt=new JSONObject().put("format",1).put("session_id",session)
                .put("source","system_link_properties").put("count",selected.size())
                .put("private_dns_active",privateDns).put("elapsed_ms",Math.max(0,SystemClock.elapsedRealtime()-started));
            ClientRuntime.write(new File(run,"network-config.json"),receipt.toString());
            ClientRuntime.write(new File(logs,"network-config.json"),receipt.toString());
        }catch(IOException|JSONException failure){
            throw new IOException("Could not prepare PlayOnline network settings. Retry opening PlayOnline.");
        }
        if(selected.isEmpty())throw new IOException(UNAVAILABLE);
    }

    private ClientNetwork(){}
}
