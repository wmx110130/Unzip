# 引擎回归测试

这些是纯 JVM 测试，直接调用 App 生产代码里使用的同一套压缩库 API，用来验证：

- ZIP / 7Z / TAR.GZ 写入与回读
- ZSTD / GZIP 往返
- RAR4 / RAR5 读取
- 加密 7Z（AES-256）读取
- ZipCrypto / AES-256 ZIP 读取
- 压缩流结束方式（只 close 不 finish）的正确性
- 压缩包内路径清洗（Zip Slip 防护）

## 准备依赖

```bash
mkdir -p /tmp/libs && cd /tmp/libs
base=https://repo1.maven.org/maven2
curl -O $base/org/apache/commons/commons-compress/1.28.0/commons-compress-1.28.0.jar
curl -O $base/org/apache/commons/commons-lang3/3.17.0/commons-lang3-3.17.0.jar
curl -O $base/commons-io/commons-io/2.20.0/commons-io-2.20.0.jar
curl -O $base/org/tukaani/xz/1.12/xz-1.12.jar
curl -O $base/com/github/junrar/junrar/8.1.2/junrar-8.1.2.jar
curl -O $base/io/airlift/aircompressor/2.0.3/aircompressor-2.0.3.jar
curl -O $base/org/brotli/dec/0.1.2/dec-0.1.2.jar
curl -O $base/net/lingala/zip4j/zip4j/2.11.6/zip4j-2.11.6.jar
curl -O $base/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar
```

## 运行

```bash
cd tools/enginetest
./run.sh
```

部分用例会读取 `/sdcard/Download/` 下的测试压缩包（zf-test.*），不存在时自动跳过。
