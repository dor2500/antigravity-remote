# OkHttp and ML Kit ship their own consumer rules. These cover optional TLS providers
# that OkHttp references but the app does not bundle.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
