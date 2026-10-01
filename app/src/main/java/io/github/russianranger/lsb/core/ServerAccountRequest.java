package io.github.russianranger.lsb.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** One-use account details, carried only through the managed server's stdin. */
public final class ServerAccountRequest implements AutoCloseable {
    private final char[] user,password;
    private boolean closed;
    public ServerAccountRequest(String user,String password){
        check(user,16,"Account name");check(password,32,"Password");
        this.user=user.toCharArray();this.password=password.toCharArray();
    }
    private static void check(String value,int maximum,String field){
        if(value==null||value.isEmpty()||value.length()>maximum)throw new IllegalArgumentException(field+" must contain 1–"+maximum+" characters");
        for(int i=0;i<value.length();i++)if(value.charAt(i)<32||value.charAt(i)>126)throw new IllegalArgumentException(field+" supports printable ASCII characters, including spaces, without line breaks");
    }
    public synchronized void send(OutputStream out)throws IOException {
        if(closed)throw new IOException("Account details expired; enter them again");
        try{
            out.write("LSBACCOUNT1\n".getBytes(StandardCharsets.US_ASCII));
            for(char c:user)out.write(c);out.write('\n');
            for(char c:password)out.write(c);out.write('\n');out.flush();
        }finally{close();}
    }
    @Override public synchronized void close(){Arrays.fill(user,'\0');Arrays.fill(password,'\0');closed=true;}
}
