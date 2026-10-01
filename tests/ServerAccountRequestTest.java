import io.github.russianranger.lsb.core.*;
import java.io.*;
import java.lang.reflect.Field;

public final class ServerAccountRequestTest {
    private static void cleared(ServerAccountRequest request)throws Exception {
        for(String name:new String[]{"user","password"}){
            Field field=ServerAccountRequest.class.getDeclaredField(name);field.setAccessible(true);
            boolean empty=true;for(char c:(char[])field.get(request))empty&=c=='\0';
            CoreTest.ok(empty,"owned "+name+" characters cleared");
        }
    }
    public static void main(String[] args)throws Exception {
        String user=" account ",password="quote\" & % ! \\ end\\ ";
        final ServerAccountRequest request=new ServerAccountRequest(user,password);
        CoreTest.ok(!request.toString().contains(user)&&!request.toString().contains(password),"object descriptions omit credentials");
        ByteArrayOutputStream pipe=new ByteArrayOutputStream();request.send(pipe);
        CoreTest.ok(pipe.toString("US-ASCII").equals("LSBACCOUNT1\n"+user+"\n"+password+"\n"),"pipe preserves exact account details without trimming or shell quoting");
        cleared(request);CoreTest.fails(()->request.send(new ByteArrayOutputStream()),"account details are one use");
        String maxUser="0123456789abcdef",maxPassword=maxUser+maxUser;
        ServerAccountRequest maximum=new ServerAccountRequest(maxUser,maxPassword);pipe.reset();maximum.send(pipe);
        CoreTest.ok(pipe.size()==62,"maximum frame has the expected bounded size");
        for(String invalid:new String[]{null,"",maxUser+"x","line\nline","line\rline","nul\0char","\u007f","\u00e9"})
            CoreTest.fails(()->new ServerAccountRequest(invalid,"secret"),"invalid account name rejected");
        for(String invalid:new String[]{null,"",maxPassword+"x","line\nline","line\rline","nul\0char","\u007f","\u00e9"})
            CoreTest.fails(()->new ServerAccountRequest("account",invalid),"invalid password rejected");
        ServerAccountRequest expired=new ServerAccountRequest("account","secret");expired.close();cleared(expired);
        CoreTest.fails(()->expired.send(new ByteArrayOutputStream()),"closed queued account cannot launch");
        ServerAccountRequest failed=new ServerAccountRequest("account","secret");
        CoreTest.fails(()->failed.send(new OutputStream(){public void write(int b)throws IOException{throw new IOException("closed");}}),"broken account pipe reports failure");
        cleared(failed);CoreTest.fails(()->failed.send(new ByteArrayOutputStream()),"broken pipe consumes account details");
        System.out.println("Completed "+CoreTest.checks+" account transport checks.");
    }
}
