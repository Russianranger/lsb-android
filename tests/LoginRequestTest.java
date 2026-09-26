import io.github.russianranger.lsb.core.*;
import java.io.*;

public final class LoginRequestTest {
    public static void main(String[] args)throws Exception {
        String password="quote\" & % ! \\ end\\ ";LoginRequest login=new LoginRequest("127.0.0.1","account",password);
        ByteArrayOutputStream pipe=new ByteArrayOutputStream();login.send(pipe);
        CoreTest.ok(pipe.toString("US-ASCII").equals("LSBLOGIN1\n127.0.0.1\naccount\n"+password+"\n"),"pipe preserves literal credentials including spaces and quoting");
        CoreTest.fails(()->login.send(new ByteArrayOutputStream()),"credentials are one use");
        CoreTest.fails(()->new LoginRequest("localhost;bad","account","secret"),"host cannot carry command arguments");
        CoreTest.fails(()->new LoginRequest("localhost","account","secret\nsecondline"),"line injection rejected");
        CoreTest.fails(()->new LoginRequest("localhost","account",""),"empty password rejected");
        LoginRequest expired=new LoginRequest("localhost","account","secret");expired.close();
        CoreTest.fails(()->expired.send(new ByteArrayOutputStream()),"expired queue cannot launch");
        LoginRequest failed=new LoginRequest("localhost","account","secret");
        CoreTest.fails(()->failed.send(new OutputStream(){public void write(int b)throws IOException{throw new IOException("closed");}}),"broken pipe reports failure");
        CoreTest.fails(()->failed.send(new ByteArrayOutputStream()),"broken pipe also clears credentials");
        System.out.println("Completed "+CoreTest.checks+" login transport checks.");
    }
}
