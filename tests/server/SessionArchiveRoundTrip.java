import io.github.russianranger.lsb.core.SafeZip;
import io.github.russianranger.lsb.core.SessionArchive;
import io.github.russianranger.lsb.core.SessionTransaction;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Native MariaDB CI uses the same transport and activation code as Android. */
public final class SessionArchiveRoundTrip {
    private static Map<String,File> roots(Path base) {
        Map<String,File> result=new LinkedHashMap<>();
        result.put("files",base.resolve("files").toFile());
        result.put("managed",base.resolve("managed").toFile());
        return result;
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("source-root destination-root archive");
        Path source=Paths.get(args[0]),destination=Paths.get(args[1]),archive=Paths.get(args[2]);
        if(destination.startsWith(source)||source.startsWith(destination))throw new IllegalArgumentException("Independent roots required");
        byte[] settings="{\"format\":\"native-database-fixture\",\"controller\":{\"enabled\":true}}".getBytes(StandardCharsets.UTF_8);
        SafeZip.Progress progress=step->{};
        SessionArchive.Summary exported;
        try(OutputStream out=Files.newOutputStream(archive)) {
            exported=SessionArchive.write(out,roots(source),settings,(scope,path)->false,progress);
        }
        Map<String,File> target=roots(destination);
        SessionTransaction transaction=new SessionTransaction(target.get("files"),target.get("managed"),destination.resolveSibling("restore-transfer").toFile());
        SessionTransaction.Stage stage=transaction.begin();
        Map<String,File> staging=new LinkedHashMap<>();staging.put("files",stage.files());staging.put("managed",stage.storage());
        SessionArchive.Result restored;
        try(InputStream in=Files.newInputStream(archive)) {
            restored=SessionArchive.read(in,staging,target,progress);
        }
        if(!Arrays.equals(restored.metadata,settings)||restored.summary.bytes!=exported.bytes||restored.summary.entries!=exported.entries)
            throw new AssertionError("Archive settings or inventory changed");
        transaction.activate();
        if(!transaction.recover())throw new AssertionError("Activated restore must be recoverable until settings apply");
        transaction.finish();
        if(transaction.recover())throw new AssertionError("Finished restore left a pending journal");
        System.out.println("PASS: complete-session archive and activation roundtrip · "+restored.summary);
    }
}
