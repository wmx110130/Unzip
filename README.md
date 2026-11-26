# Unzip

一款纯本地运行的多格式压缩 / 解压 Android 应用。Kotlin + Jetpack Compose (Material 3) 编写，支持 ZIP、7Z、RAR、TAR 及常见压缩流，界面中文（附英文）。

所有压缩与解压都在设备本地完成，不联网、不上传任何文件。

## 支持格式

### 浏览 / 解压

| 格式 | 扩展名 | 备注 |
|---|---|---|
| ZIP | `.zip` `.zipx` `.jar` `.apk` `.epub` `.xpi` | ZIP64；支持 ZipCrypto 与 AES-256 加密 |
| 7Z | `.7z` | LZMA/LZMA2/BCJ/Delta/BZip2/Deflate；支持 AES-256 |
| RAR | `.rar` | RAR4 / RAR5（只读） |
| TAR | `.tar` | GNU / POSIX 长文件名 |
| TAR.GZ | `.tar.gz` `.tgz` | |
| TAR.BZ2 | `.tar.bz2` `.tbz2` `.tbz` | |
| TAR.XZ | `.tar.xz` `.txz` | |
| TAR.ZST | `.tar.zst` `.tzst` | |
| GZIP | `.gz` `.gzip` | 单文件流 |
| BZIP2 | `.bz2` `.bzip2` | 单文件流 |
| XZ | `.xz` | 单文件流 |
| ZSTD | `.zst` `.zstd` | 单文件流 |
| LZ4 | `.lz4` | 单文件流 |
| BROTLI | `.br` `.brotli` | 单文件流 |

### 压缩 / 打包

ZIP、7Z（LZMA2）、TAR、TAR.GZ、TAR.BZ2、TAR.XZ、TAR.ZST、GZ、BZ2、XZ、ZST，压缩级别 0–9 可选。

## 功能

- **浏览**：压缩包内目录导航、面包屑、搜索、文件大小与时间
- **解压**：全部 / 多选；目标目录记忆；冲突策略（覆盖 / 跳过 / 自动重命名）
- **压缩**：多文件或整个文件夹；格式与级别选择；保存到任意 SAF 位置
- **加密包**：ZIP（ZipCrypto / AES-256）、7Z（AES-256）、RAR 密码输入与错误重试
- **任务**：前台服务 + 通知进度条，支持中途取消，切换后台不中断
- **界面**：Material 3，深浅色主题（跟随系统 / 手动），中英文资源
- **安全**：Zip Slip 路径清洗；不申请存储权限，全部通过 SAF 访问

## 技术栈

- Kotlin 2.3.21 / AGP 8.13.2 / Gradle 8.14.3
- compileSdk & targetSdk 36，minSdk 26
- Jetpack Compose + Material 3（BOM 2025.06.01）
- DataStore Preferences
- [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) 1.28.0、[XZ for Java](https://tukaani.org/xz/java.html) 1.12、[junrar](https://github.com/junrar/junrar) 8.1.2、[Aircompressor](https://github.com/airlift/aircompressor) 2.0.3、[Brotli](https://github.com/google/brotli) dec 0.1.2

## 构建

需要 JDK 17+ 与 Android SDK（platform 36、build-tools 35）。

```bash
git clone <your-repo-url> Unzip
cd Unzip
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

首次构建会自动下载 Gradle 与依赖。

### 在 ARM64 主机（Termux / 手机容器）上构建

官方 Android SDK 的 `aapt2` / `zipalign` 只有 x86_64 版本，ARM64 主机需要额外处理：

1. 准备一份 ARM64 的 `aapt2`（例如 Termux 的 `aapt2` 包，并补齐 libc++、libprotobuf、abseil、fmt、libexpat、libpng、libzopfli、zlib 等依赖库）。
2. AGP 只接受文件名为 `aapt2` 结尾的 ELF 可执行文件，并会传入 `--no-crash-recovery` 参数。
   可写一个很小的 ELF 包装程序：设置 `LD_LIBRARY_PATH`、过滤不支持的参数、再 `exec` 真正的 aapt2。
3. 构建时指定：

   ```bash
   ./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=/path/to/aapt2
   ```

   也可以临时写进 `gradle.properties`（不要提交）。
4. 若链接器提示找不到 `/linkerconfig/ld.config.txt`，创建空文件即可消除警告。
5. 内存有限时把 `org.gradle.jvmargs` 降到 `-Xmx1280m` 左右、`org.gradle.workers.max=1`，
   并保持 `kotlin.compiler.execution.strategy=in-process`。

> 项目没有引入 `material-icons-extended`（数千个类会显著拖慢/撑爆 debug 构建的 dex 阶段），
> 所需图标在 `ui/AppIcons.kt` 里以矢量路径自带。

## 项目结构

```
Unzip/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/dsha/zipforge/
│       │   ├── MainActivity.kt / UnzipApp.kt
│       │   ├── core/        # 格式定义、数据模型、路径清洗、格式化
│       │   ├── io/          # ArchiveSource（File/SAF）、解压目标（本地/SAF）
│       │   ├── archive/     # 读取器（Zip/7z/Tar/Rar/单流）与写入器
│       │   ├── task/        # TaskManager、前台服务、通知
│       │   ├── data/        # DataStore 设置与最近记录
│       │   └── ui/          # Compose 页面、主题、自绘图标
│       └── res/             # 字符串（中/英）、主题、自适应图标
├── docs/USAGE.md            # 使用手册
├── tools/enginetest/        # 纯 JVM 引擎回归测试
├── gradle/                  # version catalog 与 wrapper
├── .github/workflows/       # GitHub Actions：构建 debug APK
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── LICENSE
└── README.md
```

> 应用包名保持 `com.dsha.zipforge`（历史原因），显示名与应用名均为 Unzip。

## 回归测试

`tools/enginetest/` 提供一套纯 JVM 测试，直接调用生产代码所用的同一套压缩库 API：

```bash
cd tools/enginetest
./run.sh
```

覆盖 7Z/ZIP/TAR.GZ 写入回读、GZIP/ZSTD 往返、RAR4/RAR5 读取、加密 7Z、ZipCrypto/AES ZIP、
压缩流收尾方式与 Zip Slip 路径清洗，共 20 项断言。依赖准备见该目录 README。

## 已知限制

- RAR 只读：RAR 为专有格式，无开源编码器。
- 不支持创建加密 ZIP / 7Z（读取已支持）。
- 不支持 ZIP / 7Z / RAR 分卷包。
- 压缩包内文件预览（图片/文本）尚未实现。
- `targetSdk 36`，低版本 Android（< 8.0）不支持。

## 更新记录

### 1.0.3
- 应用更名为 **Unzip**（包名不变，可直接覆盖安装）。
- 补充 Gradle Wrapper、LICENSE、GitHub Actions。

### 1.0.2
- 修复 7Z 密码错误被当作普通错误、无法重新输入的问题（错误页新增「重新输入密码」）。

### 1.0.1
- 修复加密 ZIP：`ZipFile` 构造函数参数顺序应为 `(channel, password, encoding, useUnicodeExtraFields)`，
  之前把密码传到了字符集位置，任何加密 ZIP 都会抛 `UnsupportedCharsetException`。
- 修复 7Z 读取缺少 `commons-lang3` 导致的 `NoClassDefFoundError`。
- 修复 GZIP / BZIP2 / XZ 先 `finish()` 后 `close()` 造成的重复 trailer 或 NPE：统一只 `close()`。
- 补充 `commons-codec` 运行时依赖。

## 许可证

[MIT](LICENSE)

## 贡献

欢迎提 Issue 或 PR。提交前请确保：

```bash
./gradlew :app:assembleDebug       # 能构建
cd tools/enginetest && ./run.sh    # 回归测试通过
```
