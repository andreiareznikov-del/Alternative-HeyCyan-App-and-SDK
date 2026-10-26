# W610 transport unit tests without Android/Meta SDK

From the repository root, using a full JDK 17:

```bash
JAVA_HOME=/path/to/jdk17 android/CyanBridge/gradlew -p tools/w610-unit-tests test
```

This project compiles the actual production transport/session files and their existing tests. It checks settling, retry, timeouts, cancellation, late callbacks, separate files, disconnect invalidation, and permit ownership. JPEG decoding tests remain in the Android test task and are **not** covered by this standalone suite. No result here proves a physical W610 transfer or an APK build.
