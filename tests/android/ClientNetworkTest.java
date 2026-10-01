package io.github.russianranger.lsb;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import java.io.File;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class ClientNetworkTest {
    private static final String ID="00000000-0000-0000-0000-000000000001";
    private static String read(File file)throws Exception{return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}
    private static InetAddress address(int last)throws Exception{return InetAddress.getByAddress(new byte[]{(byte)192,0,2,(byte)last});}

    @Test public void usesOnlySelectedNetworkAddressesAndWritesLoopbackHosts()throws Exception {
        File root=Files.createTempDirectory("client-network").toFile(),run=new File(root,"run"),logs=new File(root,"logs");
        InetAddress a=InetAddress.getByAddress("private-router.example",new byte[]{(byte)192,0,2,53});
        ClientNetwork.prepare(Arrays.asList(a,address(54)),false,run,logs,ID);
        assertEquals("nameserver 192.0.2.53\nnameserver 192.0.2.54\noptions timeout:2 attempts:2\n",read(new File(run,"resolv.conf")));
        assertEquals("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n",read(new File(run,"hosts")));
        assertFalse(new File(logs,"resolv.conf").exists());assertFalse(new File(logs,"hosts").exists());
        JSONObject receipt=new JSONObject(read(new File(logs,"network-config.json")));
        assertEquals(ID,receipt.getString("session_id"));assertEquals("system_link_properties",receipt.getString("source"));
        assertEquals(2,receipt.getInt("count"));assertFalse(receipt.getBoolean("private_dns_active"));assertTrue(receipt.getLong("elapsed_ms")>=0);
        HashSet<String> keys=new HashSet<>();java.util.Iterator<String> iterator=receipt.keys();while(iterator.hasNext())keys.add(iterator.next());
        assertEquals(new HashSet<>(Arrays.asList("format","session_id","source","count","private_dns_active","elapsed_ms")),keys);
        assertEquals(read(new File(run,"network-config.json")),receipt.toString());
        assertFalse(receipt.toString().contains("192.0.2"));assertFalse(receipt.toString().contains("private-router"));
    }

    @Test public void acceptsUnscopedIpv6AndKeepsOrderWithAtMostThreeDistinctServers()throws Exception {
        File root=Files.createTempDirectory("client-network-ipv6").toFile(),run=new File(root,"run");
        byte[] bytes=new byte[16];bytes[0]=0x20;bytes[1]=1;bytes[2]=0x0d;bytes[3]=(byte)0xb8;bytes[15]=0x53;
        InetAddress v6=InetAddress.getByAddress(bytes);
        ClientNetwork.prepare(Arrays.asList(address(53),address(53),v6,address(54),address(55)),true,run,new File(root,"logs"),ID);
        assertEquals("nameserver 192.0.2.53\nnameserver "+v6.getHostAddress()+"\nnameserver 192.0.2.54\noptions timeout:2 attempts:2\n",read(new File(run,"resolv.conf")));
        JSONObject receipt=new JSONObject(read(new File(run,"network-config.json")));
        assertEquals(3,receipt.getInt("count"));assertTrue(receipt.getBoolean("private_dns_active"));
    }

    @Test public void skipsLocalMulticastBroadcastAndScopedResolvers()throws Exception {
        File root=Files.createTempDirectory("client-network-filter").toFile(),run=new File(root,"run");
        byte[] local=new byte[16];local[0]=(byte)0xfe;local[1]=(byte)0x80;local[15]=1;
        InetAddress scoped=Inet6Address.getByAddress(null,local,3);
        ClientNetwork.prepare(Arrays.asList(null,InetAddress.getByAddress(new byte[4]),
            InetAddress.getByAddress(new byte[]{127,0,0,1}),InetAddress.getByAddress(new byte[]{(byte)224,0,0,1}),
            InetAddress.getByAddress(new byte[]{-1,-1,-1,-1}),scoped,address(53)),false,run,new File(root,"logs"),ID);
        assertEquals("nameserver 192.0.2.53\noptions timeout:2 attempts:2\n",read(new File(run,"resolv.conf")));
    }

    @Test public void unavailableDnsDeletesStalePrivateFilesAndFailsBeforeEmptyConfiguration()throws Exception {
        File root=Files.createTempDirectory("client-network-missing").toFile(),run=new File(root,"run"),logs=new File(root,"logs");
        ClientNetwork.prepare(Collections.singletonList(address(53)),false,run,logs,ID);
        try{ClientNetwork.prepare(Collections.emptyList(),false,run,logs,ID);fail("Missing DNS must stop updater startup");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("Connect to Wi-Fi or mobile data"));}
        assertFalse(new File(run,"resolv.conf").exists());assertFalse(new File(run,"hosts").exists());
        assertEquals(0,new JSONObject(read(new File(logs,"network-config.json"))).getInt("count"));
    }

    @Test public void neverChangesRuntimeOrHostConfiguration()throws Exception {
        File root=Files.createTempDirectory("client-network-isolation").toFile(),runtime=new File(root,"runtime/etc");runtime.mkdirs();
        File resolver=new File(runtime,"resolv.conf"),hosts=new File(runtime,"hosts");
        Files.write(resolver.toPath(),new byte[0]);Files.write(hosts.toPath(),"existing hosts\n".getBytes(StandardCharsets.UTF_8));
        ClientNetwork.prepare(Collections.singletonList(address(53)),false,new File(root,"run"),new File(root,"logs"),ID);
        assertEquals("",read(resolver));assertEquals("existing hosts\n",read(hosts));
    }

    @Test public void missingAndroidNetworkServiceAndPlatformErrorsAreActionableAndPrivate()throws Exception {
        for(boolean throwsError:new boolean[]{false,true}){
            Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){
                @Override public Object getSystemService(String name){
                    if(Context.CONNECTIVITY_SERVICE.equals(name)){
                        if(throwsError)throw new SecurityException("private-network-name 192.0.2.99");
                        return null;
                    }
                    return super.getSystemService(name);
                }
            };
            File root=Files.createTempDirectory("client-network-platform").toFile(),run=new File(root,"run"),logs=new File(root,"logs");
            try{ClientNetwork.prepare(context,run,logs,ID);fail("Unavailable platform settings must stop startup");}
            catch(IOException expected){assertTrue(expected.getMessage().contains("Connect to Wi-Fi or mobile data"));assertFalse(expected.toString().contains("private-network"));assertNull(expected.getCause());}
            assertFalse(new File(run,"resolv.conf").exists());assertFalse(read(new File(logs,"network-config.json")).contains("192.0.2"));
        }
    }

    @Test public void readsCurrentAndroidLinkPropertiesAndDetectsNetworkDisconnection()throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network=manager.getActiveNetwork();assertNotNull(network);
        LinkProperties properties=new LinkProperties();properties.setDnsServers(Collections.singletonList(address(53)));
        properties.setDomains("private-search.example");Shadows.shadowOf(manager).setLinkProperties(network,properties);
        File root=Files.createTempDirectory("client-network-link-properties").toFile(),run=new File(root,"run"),logs=new File(root,"logs");
        ClientNetwork.prepare(context,run,logs,ID);
        assertEquals("nameserver 192.0.2.53\noptions timeout:2 attempts:2\n",read(new File(run,"resolv.conf")));
        assertFalse(read(new File(logs,"network-config.json")).contains("private-search"));
        Shadows.shadowOf(manager).setDefaultNetworkActive(false);
        try{ClientNetwork.prepare(context,run,logs,ID);fail("A disconnected default network must stop startup");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("Connect to Wi-Fi or mobile data"));}
        assertFalse(new File(run,"resolv.conf").exists());
    }
}
