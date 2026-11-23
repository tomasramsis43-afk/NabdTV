-keep class androidx.media3.** { *; }

# Readable crash reports from release builds (CrashActivity / Sentry): keep file names + line numbers,
# hide the real source file name. The mapping.txt uploaded by CI de-obfuscates the rest.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
