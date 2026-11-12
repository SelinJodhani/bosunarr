# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.bosunarr.**$$serializer { *; }
-keepclassmembers class app.bosunarr.** { *** Companion; }
-keepclasseswithmembers class app.bosunarr.** { kotlinx.serialization.KSerializer serializer(...); }
# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Tink
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
# JSch (SSH for the Server type) instantiates its algorithms by class name from its config (Class.forName):
# key exchanges (DH*), user-auth methods, JCE algorithms, and the BouncyCastle-backed ones selected in
# feature/server/Ssh.kt. Everything else in JSch (SFTP, X11, agents, proxies…) is unused and shrunk away.
# Optional integrations (SLF4J/Log4j loggers, JNA/FFM Pageant and Unix sockets, GSS-API) never run on Android.
-keep class com.jcraft.jsch.jce.** { <init>(); }
-keep class com.jcraft.jsch.DH* { <init>(); }
-keep class com.jcraft.jsch.UserAuth* { <init>(); }
-keep class com.jcraft.jsch.CipherNone { <init>(); }
-keep class com.jcraft.jsch.jbcrypt.JBCrypt { <init>(); }
-keep class com.jcraft.jsch.bc.XDH,
            com.jcraft.jsch.bc.SignatureEd25519,
            com.jcraft.jsch.bc.SignatureEd448,
            com.jcraft.jsch.bc.KeyPairGenEdDSA,
            com.jcraft.jsch.bc.ChaCha20Poly1305,
            com.jcraft.jsch.bc.MLKEM768,
            com.jcraft.jsch.bc.MLKEM1024,
            com.jcraft.jsch.bc.SNTRUP761,
            com.jcraft.jsch.bc.SCrypt { <init>(); }
# JSch loads those algorithm classes with Class.forName from its own package, and several are package-private
# (e.g. DHEC256). If R8 repackages JSch's classes (minified names like `jy3`), the loader ends up in another
# package and key exchange fails with IllegalAccessException. Keep every JSch class's name and package;
# unused classes can still be removed.
-keepnames class com.jcraft.jsch.**
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.sun.jna.**
