#!/usr/bin/env bash
# 在 JVM 上回归验证 App 使用的压缩库调用链（不需要 Android 设备）。
# 用法：./run.sh
set -e
cd "$(dirname "$0")"
LIBS=${LIBS:-/tmp/libs}
if [ ! -d "$LIBS" ]; then
  echo "请先把依赖 jar 下载到 $LIBS（见 README）"
  exit 1
fi
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64}
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p out
javac -encoding UTF-8 -cp "$LIBS/*" -d out TestEngines.java TestCompressStreams.java TestEncryptedZip.java
java -cp "$LIBS/*:out" TestEngines
java -cp "$LIBS/*:out" TestCompressStreams
java -cp "$LIBS/*:out" TestEncryptedZip
