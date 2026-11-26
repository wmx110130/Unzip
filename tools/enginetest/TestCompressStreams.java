import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.gzip.*;
import org.apache.commons.compress.compressors.bzip2.*;
import org.apache.commons.compress.compressors.xz.*;
import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;

public class TestCompressStreams {
    static byte[] raw = "hello tar.gz 压缩工坊 payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static int fail = 0;
    static void check(String n, boolean ok) { System.out.println((ok?"PASS ":"FAIL ")+n); if(!ok) fail++; }

    static void tarGz() throws Exception {
        Path p = Paths.get("/tmp/t.tar.gz");
        try (OutputStream fos = Files.newOutputStream(p);
             GzipCompressorOutputStream gz = new GzipCompressorOutputStream(fos, new GzipParameters());
             TarArchiveOutputStream tos = new TarArchiveOutputStream(gz)) {
            TarArchiveEntry e = new TarArchiveEntry("dir/a.txt");
            e.setSize(raw.length);
            tos.putArchiveEntry(e);
            tos.write(raw, 0, raw.length);
            tos.closeArchiveEntry();
            // 模拟 App：不显式 finish，交给 close
        }
        try (TarArchiveInputStream tin = new TarArchiveInputStream(new GzipCompressorInputStream(Files.newInputStream(p), true))) {
            TarArchiveEntry e = tin.getNextEntry();
            byte[] got = tin.readAllBytes();
            check("tar.gz close-only", e != null && java.util.Arrays.equals(raw, got));
        }
    }

    static void tarBz2() throws Exception {
        Path p = Paths.get("/tmp/t.tar.bz2");
        try (OutputStream fos = Files.newOutputStream(p);
             BZip2CompressorOutputStream bz = new BZip2CompressorOutputStream(fos, 9);
             TarArchiveOutputStream tos = new TarArchiveOutputStream(bz)) {
            TarArchiveEntry e = new TarArchiveEntry("a.txt");
            e.setSize(raw.length);
            tos.putArchiveEntry(e);
            tos.write(raw, 0, raw.length);
            tos.closeArchiveEntry();
        }
        try (TarArchiveInputStream tin = new TarArchiveInputStream(new BZip2CompressorInputStream(Files.newInputStream(p), true))) {
            TarArchiveEntry e = tin.getNextEntry();
            byte[] got = tin.readAllBytes();
            check("tar.bz2 close-only", e != null && java.util.Arrays.equals(raw, got));
        }
    }

    static void tarXz() throws Exception {
        Path p = Paths.get("/tmp/t.tar.xz");
        try (OutputStream fos = Files.newOutputStream(p);
             XZCompressorOutputStream xz = new XZCompressorOutputStream(fos, 6);
             TarArchiveOutputStream tos = new TarArchiveOutputStream(xz)) {
            TarArchiveEntry e = new TarArchiveEntry("a.txt");
            e.setSize(raw.length);
            tos.putArchiveEntry(e);
            tos.write(raw, 0, raw.length);
            tos.closeArchiveEntry();
        }
        try (TarArchiveInputStream tin = new TarArchiveInputStream(new XZCompressorInputStream(Files.newInputStream(p), true))) {
            TarArchiveEntry e = tin.getNextEntry();
            byte[] got = tin.readAllBytes();
            check("tar.xz close-only", e != null && java.util.Arrays.equals(raw, got));
        }
    }

    static void gzSingle() throws Exception {
        Path p = Paths.get("/tmp/s.gz");
        try (OutputStream fos = Files.newOutputStream(p);
             GzipCompressorOutputStream gz = new GzipCompressorOutputStream(fos, new GzipParameters())) {
            gz.write(raw);
        }
        try (InputStream in = new GZIPInputStream(Files.newInputStream(p))) {
            check("gz single close-only", java.util.Arrays.equals(raw, in.readAllBytes()));
        }
    }

    static void bz2Single() throws Exception {
        Path p = Paths.get("/tmp/s.bz2");
        try (OutputStream fos = Files.newOutputStream(p);
             BZip2CompressorOutputStream bz = new BZip2CompressorOutputStream(fos, 9)) {
            bz.write(raw);
        }
        try (InputStream in = new BZip2CompressorInputStream(Files.newInputStream(p), true)) {
            check("bz2 single close-only", java.util.Arrays.equals(raw, in.readAllBytes()));
        }
    }

    public static void main(String[] a) throws Exception {
        tarGz(); tarBz2(); tarXz(); gzSingle(); bz2Single();
        System.out.println(fail==0 ? "ALL_OK" : "FAILS="+fail);
        if (fail>0) System.exit(1);
    }
}
