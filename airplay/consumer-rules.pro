# JmDNS uses reflection over its own service info classes.
-keep class org.jmdns.** { *; }
-dontwarn org.jmdns.**

# BouncyCastle registers providers by name.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**
