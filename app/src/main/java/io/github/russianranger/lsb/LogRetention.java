package io.github.russianranger.lsb;
import java.io.*;
import java.nio.file.*;
final class LogRetention {
    static void rotate(File file)throws IOException {
        if(file.isFile())Files.move(file.toPath(),new File(file.getParentFile(),file.getName()+".previous").toPath(),StandardCopyOption.REPLACE_EXISTING);
    }
}
