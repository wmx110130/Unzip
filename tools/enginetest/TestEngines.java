import org.apache.commons.compress.archivers.sevenz.*;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.compressors.gzip.*;
import io.airlift.compress.zstd.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class TestEngines {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) fails++;
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("zf");
        Path src = tmp.resolve("hello.txt");
        Files.writeString(src, "hello zipforge 压缩工坊 - test payload");
        byte[] raw = Files.readAllBytes(src);

        // ---------- 1. 7z write/read（与应用 ArchiveCompressor.compressToSevenZ 相同调用） ----------
        Path sevenZ = tmp.resolve("a.7z");
        try (SevenZOutputFile out = new SevenZOutputFile(sevenZ.toFile())) {
            out.setContentCompression(SevenZMethod.LZMA2);
            SevenZArchiveEntry e = new SevenZArchiveEntry();
            e.setName("dir/hello.txt");
            e.setSize(raw.length);
            e.setLastModifiedDate(new Date());
            e.setHasStream(true);
            out.putArchiveEntry(e);
            out.write(raw, 0, raw.length);
            out.closeArchiveEntry();
            out.finish();
        }
        int sevenCount = 0;
        byte[] sevenBack = null;
        try (SevenZFile z = SevenZFile.builder().setFile(sevenZ.toFile()).get()) {
            for (SevenZArchiveEntry e : z.getEntries()) {
                sevenCount++;
                try (InputStream in = z.getInputStream(e)) { sevenBack = in.readAllBytes(); }
            }
        }
        check("7z write/read entries=1", sevenCount == 1);
        check("7z content roundtrip", Arrays.equals(raw, sevenBack));

        // ---------- 2. ZIP write/read（与应用 ArchiveWriter.writeZip 相同调用） ----------
        Path zipP = tmp.resolve("a.zip");
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(zipP.toFile())) {
            zos.setEncoding("UTF-8");
            zos.setLevel(6);
            zos.setUseZip64(Zip64Mode.AsNeeded);
            ZipArchiveEntry e = new ZipArchiveEntry("dir/hello.txt");
            e.setTime(System.currentTimeMillis());
            e.setSize(raw.length);
            zos.putArchiveEntry(e);
            zos.write(raw, 0, raw.length);
            zos.closeArchiveEntry();
            zos.finish();
        }
        int zipCount = 0;
        byte[] zipBack = null;
        try (FileChannel ch = FileChannel.open(zipP, StandardOpenOption.READ);
             ZipFile zf = new ZipFile(ch, "UTF-8", null, true)) {
            Enumeration<ZipArchiveEntry> en = zf.getEntries();
            while (en.hasMoreElements()) {
                ZipArchiveEntry e = en.nextElement();
                zipCount++;
                try (InputStream in = zf.getInputStream(e)) { zipBack = in.readAllBytes(); }
            }
        }
        check("zip write/read entries=1", zipCount == 1);
        check("zip content roundtrip", Arrays.equals(raw, zipBack));

        // ---------- 3. TAR.GZ write/read ----------
        Path tgz = tmp.resolve("a.tar.gz");
        try (OutputStream fos = Files.newOutputStream(tgz);
             GzipCompressorOutputStream gz = new GzipCompressorOutputStream(fos, new GzipParameters());
             TarArchiveOutputStream tos = new TarArchiveOutputStream(gz)) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry te = new TarArchiveEntry("dir/hello.txt");
            te.setSize(raw.length);
            tos.putArchiveEntry(te);
            tos.write(raw, 0, raw.length);
            tos.closeArchiveEntry();
            tos.finish();
        }
        int tarCount = 0;
        byte[] tarBack = null;
        try (InputStream in = new GzipCompressorInputStream(Files.newInputStream(tgz), true);
             TarArchiveInputStream tin = new TarArchiveInputStream(in)) {
            TarArchiveEntry e;
            while ((e = tin.getNextEntry()) != null) {
                tarCount++;
                tarBack = tin.readAllBytes();
            }
        }
        check("tar.gz write/read entries=1", tarCount == 1);
        check("tar.gz content roundtrip", Arrays.equals(raw, tarBack));

        // ---------- 4. ZSTD roundtrip（aircompressor） ----------
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZstdOutputStream zout = new ZstdOutputStream(bos)) { zout.write(raw); }
        byte[] zstdBack;
        try (ZstdInputStream zin = new ZstdInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            zstdBack = zin.readAllBytes();
        }
        check("zstd roundtrip", Arrays.equals(raw, zstdBack));

        // ---------- 5. GZIP 单文件 ----------
        Path gzP = tmp.resolve("a.gz");
        try (OutputStream fos = Files.newOutputStream(gzP);
             GzipCompressorOutputStream gz = new GzipCompressorOutputStream(fos, new GzipParameters())) {
            gz.write(raw);
            // 只 close，不要显式 finish（与 App 生产代码一致）
        }
        byte[] gzBack;
        try (InputStream in = new GzipCompressorInputStream(Files.newInputStream(gzP), true)) {
            gzBack = in.readAllBytes();
        }
        check("gzip roundtrip", Arrays.equals(raw, gzBack));

        // ---------- 6. RAR 读取（junrar） ----------
        File rar = new File("/sdcard/Download/zf-test.rar");
        if (rar.exists()) {
            int rarFiles = 0;
            boolean rarOk = true;
            try (com.github.junrar.Archive a = new com.github.junrar.Archive(rar)) {
                for (com.github.junrar.rarfile.FileHeader h : a.getFileHeaders()) {
                    if (h.isDirectory()) continue;
                    rarFiles++;
                    try (InputStream in = a.getInputStream(h)) {
                        if (in.readAllBytes().length == 0 && h.getFullUnpackSize() > 0) rarOk = false;
                    } catch (Exception ex) {
                        rarOk = false;
                        System.out.println("  rar read error: " + ex);
                    }
                }
            }
            check("rar read files=" + rarFiles + " ok=" + rarOk, rarOk && rarFiles > 0);
        } else {
            System.out.println("SKIP rar (sample missing)");
        }

        // ---------- 7. 加密 7Z 读取 ----------
        File enc = new File("/sdcard/Download/zf-test-encrypted.7z");
        if (enc.exists()) {
            boolean encOk = false;
            try (SevenZFile z = SevenZFile.builder().setFile(enc).setPassword("zipforge").get()) {
                for (SevenZArchiveEntry e : z.getEntries()) {
                    if (e.isDirectory()) continue;
                    try (InputStream in = z.getInputStream(e)) {
                        encOk = in.readAllBytes().length > 0;
                    }
                    break;
                }
            } catch (Exception ex) {
                System.out.println("  enc 7z error: " + ex);
            }
            check("encrypted 7z read", encOk);
        } else {
            System.out.println("SKIP encrypted 7z (sample missing)");
        }

        // ---------- 8. 路径清洗 ----------
        String[][] cases = {
            {"../evil.sh", "evil.sh"},
            {"/abs/path.txt", "abs/path.txt"},
            {"C:\\win\\a.txt", "win/a.txt"},
            {"a/./b/../c.txt", "a/b/c.txt"},
            {"dir/", "dir"},
        };
        for (String[] c : cases) {
            String got = sanitize(c[0]);
            check("sanitize(" + c[0] + ")=" + got, c[1].equals(got));
        }

        System.out.println(fails == 0 ? "ALL_TESTS_PASSED" : "FAILURES=" + fails);
        if (fails != 0) System.exit(1);
    }

    static String sanitize(String raw) {
        String p = raw.replace('\\', '/').trim();
        if (p.isEmpty()) return null;
        if (p.length() > 1 && p.charAt(1) == ':') p = p.substring(2);
        p = p.replaceFirst("^/+", "");
        List<String> parts = new ArrayList<>();
        for (String s : p.split("/")) {
            String t = s.trim();
            if (t.isEmpty() || t.equals(".") || t.equals("..")) continue;
            StringBuilder sb = new StringBuilder();
            for (char ch : t.toCharArray()) {
                if (ch < 0x20) continue;
                if ("\\/:*?\"<>|".indexOf(ch) >= 0) sb.append('_');
                else sb.append(ch);
            }
            String cleaned = sb.toString().trim();
            while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1);
            if (cleaned.isEmpty()) continue;
            parts.add(cleaned.length() > 180 ? cleaned.substring(0, 180) : cleaned);
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }
}
