package io.github.russianranger.lsb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** A fresh bounded tail on each read also handles replacement and truncation. */
final class ServerLogTail {
    static final int MAX_BYTES=12000;
    private ServerLogTail(){}
    static String read(File file)throws IOException {
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))return "";
        try(RandomAccessFile input=new RandomAccessFile(file,"r")){
            long length=input.length(),offset=Math.max(0,length-MAX_BYTES);
            byte[] bytes=new byte[(int)(length-offset)];input.seek(offset);
            int count=0,n;while(count<bytes.length&&(n=input.read(bytes,count,bytes.length-count))>0)count+=n;
            int start=0,end=count;
            // Never expose a suffix of a credential or a broken UTF-8 character.
            if(offset>0){while(start<end&&bytes[start]!='\n')start++;if(start<end)start++;}
            // Only complete lines: a writer may be midway through a credential.
            while(end>start&&bytes[end-1]!='\n')end--;
            String text=new String(bytes,start,end-start,StandardCharsets.UTF_8);
            return text.replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]","")
                .replaceAll("\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)","")
                .replace('\r','\n').replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","");
        }catch(FileNotFoundException e){return "";}
    }
    static String lastLine(String text){
        String[] lines=text.split("\n");
        for(int i=lines.length-1;i>=0;i--){String line=lines[i].trim();if(!line.isEmpty())return line.length()>1000?"…"+line.substring(line.length()-1000):line;}
        return "";
    }
}
