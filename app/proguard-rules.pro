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

# The MQTT client (HiveMQ) runs on Netty and JCTools, which reach their own
# fields by name: Netty through Atomic*FieldUpdater ("refCnt", "result"),
# JCTools through Unsafe offsets looked up by field name. Renamed, they fail
# at run time, so member names stay (the HiveMQ client's Android setup).
-keepclassmembernames class io.netty.** { *; }
-keepclassmembers class org.jctools.** { *; }
# Optional parts the app leaves out, never reached on Android: HiveMQ's
# WebSocket, proxy and epoll transports (netty-codec-http, netty-handler-proxy,
# netty-transport-native-epoll), Netty's native TLS (tcnative) and Jetty
# ALPN/NPN, its log4j loggers, and its BlockHound integration (JVM tests).
-dontwarn io.netty.channel.epoll.**
-dontwarn io.netty.handler.codec.http.**
-dontwarn io.netty.handler.proxy.**
-dontwarn io.netty.internal.tcnative.**
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn reactor.blockhound.integration.**

# Stack traces in a release crash report keep file and line numbers;
# the mapping file (app/build/outputs/mapping/release/mapping.txt) turns
# the obfuscated names back.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Sora Editor and its TextMate support (LGPL-2.1, with tm4e under EPL-2.0)
# stay whole, unrenamed and unmerged with the app's code: a user can swap in
# their own build of the library (LGPL-2.1 section 6), and tm4e reads its
# grammars and themes through Gson and reflection. joni / jcodings (the
# regex engine) and snakeyaml-engine come along with it.
-keep class io.github.rosemoe.sora.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
-dontwarn org.eclipse.jdt.annotation.**
-dontwarn org.snakeyaml.engine.**
# Sora's ShareableData.clone() refers to kotlin.Cloneable's DefaultImpls,
# which no kotlin-stdlib ships; the viewer never clones its text.
-dontwarn kotlin.Cloneable$DefaultImpls
