package io.github.russianranger.lsb.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** One-use, memory-only credentials. No JSON, preferences, Intent extras or toString payload. */
public final class LoginRequest implements AutoCloseable {
    public final String host;
    private final char[] user,password;
    private boolean closed;
    public LoginRequest(String host,String user,String password){
        this.host=new LaunchConfig(host,"US").host;
        check(user);check(password);this.user=user.toCharArray();this.password=password.toCharArray();
    }
    private static void check(String value){
        if(value==null||value.isEmpty()||value.length()>128)throw new IllegalArgumentException("Enter account and password (1–128 characters each)");
        for(int i=0;i<value.length();i++)if(value.charAt(i)<32||value.charAt(i)>126)throw new IllegalArgumentException("This loader login supports ASCII account/password characters without line breaks");
    }
    public synchronized void send(OutputStream out)throws IOException {
        if(closed)throw new IOException("Login details expired; enter them again");
        try{
            out.write("LSBLOGIN1\n".getBytes(StandardCharsets.US_ASCII));out.write(host.getBytes(StandardCharsets.US_ASCII));out.write('\n');
            for(char c:user)out.write(c);out.write('\n');for(char c:password)out.write(c);out.write('\n');out.flush();
        }finally{close();}
    }
    @Override public synchronized void close(){Arrays.fill(user,'\0');Arrays.fill(password,'\0');closed=true;}
}
