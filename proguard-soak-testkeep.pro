# R8 SOAK ONLY — never referenced by release builds.
#
# androidTest-on-minified-app constraints (verified 2026-07-20):
# 1. AGP does NOT -applymapping the test APK, so app-side obfuscation breaks
#    every test-APK reference into app-provided classes -> "No tests!".
# 2. The test APK dedupes libs the app already ships (kotlin-stdlib,
#    coroutines, androidx, compose); shrinking removes the parts only tests
#    use -> classload failure at enumeration.
# So the soak disables obfuscation and keeps shared-lib surface; SHRINKING
# and OPTIMIZATION still run on everything unkept — the historical
# "R8 broke NN inference" risk class (dead-code removal + inlining vs JNI).
-dontobfuscate
-keep class tribixbite.cleverkeys.** { *; }
-keep class kotlin.** { *; }
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn kotlin.**
-dontwarn androidx.**
