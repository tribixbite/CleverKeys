# Theory #5: Missing Runtime Dependencies

## Confidence: 10%

## Theory:
Required AndroidX or Kotlin dependencies might not be properly included in the APK, causing InputMethodService to fail during instantiation. Even though dependencies are declared in build.gradle, they might not be packaged correctly.

## Investigation Needed:

### 1. Check APK Contents
Extract and verify dependencies are present:
```bash
unzip -l build/outputs/apk/debug/tribixbite.keyboard2.apk | grep -E "androidx|kotlin|lifecycle|savedstate"
```

### 2. Verify DEX Files
Check if classes are in the DEX:
```bash
# Extract APK
unzip -q build/outputs/apk/debug/tribixbite.keyboard2.apk -d /tmp/apk_extract

# List classes in DEX
cd /tmp/apk_extract
dexdump -l plain classes.dex | grep -E "InputMethodService|Lifecycle|SavedState"
```

### 3. Check Dependencies Task
```bash
./gradlew :dependencies --configuration debugRuntimeClasspath | grep -E "androidx|lifecycle|savedstate"
```

## Potential Fixes:

### Option A: Force include dependencies
Add to build.gradle:
```gradle
android {
    packagingOptions {
        merge 'META-INF/DEPENDENCIES'
        pickFirst 'META-INF/LICENSE'
        pickFirst 'META-INF/NOTICE'
    }
}
```

### Option B: Explicit dependency resolution
```gradle
configurations.all {
    resolutionStrategy {
        force 'androidx.lifecycle:lifecycle-runtime-ktx:2.6.2'
        force 'androidx.savedstate:savedstate-ktx:1.2.1'
    }
}
```

### Option C: Check for conflicting versions
```bash
./gradlew :dependencyInsight --configuration debugRuntimeClasspath --dependency lifecycle
```

## Why This Might Work:
- Dependency conflicts could cause runtime failures
- Missing transitive dependencies
- Version mismatches between AndroidX libraries

## Why This Probably Won't Work:
- Build would fail if critical dependencies were missing
- No ClassNotFoundException in logs
- Gradle dependency resolution usually works correctly
- We're using standard, well-tested dependencies

## Next Steps:
1. Run investigation commands above
2. Check if any dependencies are missing
3. If found, apply appropriate fix
4. Build and test

---

**Note:** This is the lowest confidence theory because:
- Gradle typically handles dependencies well
- We'd see ClassNotFoundException if classes were missing
- Build succeeds, suggesting dependencies are resolved
- This is more of a "sanity check" theory
