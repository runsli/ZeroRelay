# BouncyCastle (X25519)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp platform hooks
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**

# Tink / security-crypto (compile-only annotations)
-dontwarn com.google.errorprone.annotations.**
