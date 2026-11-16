# The app uses no reflection-based serialization (JSON is handled as dynamic
# JsonElement trees), so the default optimize rules are enough. OkHttp ships its
# own consumer rules.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
