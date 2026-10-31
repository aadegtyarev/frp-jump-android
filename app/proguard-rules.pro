# R8 rules for the release build (minify + resource shrinking).
#
# What is NOT here because it needs nothing: the manifest's activity, service
# and receivers (aapt keeps them), OkHttp, CameraX, kotlinx.serialization,
# Compose, AppCompat and Biometric (each library ships its own consumer rules),
# ZXing core and sshj's own classes (no reflection: sshj builds its factories
# with `new`), Termux's JNI class (proguard-android-optimize.txt keeps classes
# with native methods under their names). There is no WebView JavaScript
# interface. Check the R8 output (app/build/outputs/mapping/release/usage.txt)
# for the classes below after bumping any of these libraries.

# BouncyCastle, a JCA provider: BouncyCastleProvider loads its algorithm
# tables by class name (Class.forName("...$Mappings")), and those tables map
# "Ed25519", "X25519", ... to SPI class names as strings. sshj also creates
# the provider itself by name (SecurityUtils.registerSecurityProvider).
# Keep the provider packages whole; the engines they use follow from them.
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.pqc.jcajce.provider.** { *; }
# JDK-only types BouncyCastle refers to for features Android lacks (LDAP
# cert stores, JNDI): never reached on Android.
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.bouncycastle.est.**

# sshj logs through slf4j; with no binding on the classpath it is a no-op.
-dontwarn org.slf4j.impl.**

# Stack traces in a release crash report keep file and line numbers;
# the mapping file (app/build/outputs/mapping/release/mapping.txt) turns
# the obfuscated names back.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
