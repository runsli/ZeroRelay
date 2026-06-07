# BouncyCastle (X25519)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp platform hooks
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**

# Tink / security-crypto (compile-only annotations)
-dontwarn com.google.errorprone.annotations.**

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-dontwarn net.sqlcipher.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
