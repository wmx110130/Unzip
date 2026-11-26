# ZipForge keep rules
-keep class com.github.junrar.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-keep class io.airlift.compress.** { *; }
-keep class org.brotli.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.brotli.**
-dontwarn io.airlift.compress.**
