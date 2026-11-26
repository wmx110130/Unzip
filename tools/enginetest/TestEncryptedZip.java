import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.EncryptionMethod;

public class TestEncryptedZip {
    public static void main(String[] a) throws Exception {
        Path src = Paths.get("/tmp/zc-src.txt");
        Files.writeString(src, "zipcrypto payload");
        Path z = Paths.get("/tmp/zipcrypto.zip");
        Files.deleteIfExists(z);
        ZipFile zf = new ZipFile(z.toFile());
        zf.setPassword("pass123".toCharArray());
        ZipParameters p = new ZipParameters();
        p.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
        zf.addFile(src.toFile(), p);
        System.out.println("zipcrypto created size=" + Files.size(z));

        for (String[] order : new String[][]{{"UTF-8","pass123"},{"pass123","UTF-8"}}) {
            String label = "(" + order[0] + "," + order[1] + ")";
            try (FileChannel ch = FileChannel.open(z);
                 var cf = new org.apache.commons.compress.archivers.zip.ZipFile(ch, order[0], order[1], true)) {
                var en = cf.getEntries();
                while (en.hasMoreElements()) {
                    var e = en.nextElement();
                    try (InputStream in = cf.getInputStream(e)) {
                        System.out.println(label + " OK entry=" + e.getName() + " read=" + in.readAllBytes().length);
                    }
                }
            } catch (Exception e) {
                System.out.println(label + " -> 预期失败（错误参数顺序）：" + e.getClass().getSimpleName());
            }
        }
    }
}
