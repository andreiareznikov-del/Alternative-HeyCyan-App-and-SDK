plugins { kotlin("jvm") version "2.3.10" }

// Compile the actual production files, with no Android, BLE hardware, or Meta package token.
kotlin {
    jvmToolchain(17)
    sourceSets.named("main") {
        kotlin.srcDirs(
            "../../android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ai/image",
            "../../android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/glasses",
        )
        kotlin.include("BleThumbnailTransfer.kt", "GlassesSessionCoordinator.kt")
    }
    sourceSets.named("test") {
        kotlin.srcDirs(
            "../../android/CyanBridge/app/src/test/java/com/fersaiyan/cyanbridge/ai/image",
            "../../android/CyanBridge/app/src/test/java/com/fersaiyan/cyanbridge/glasses",
        )
        kotlin.include("BleThumbnailTransferTest.kt", "GlassesSessionCoordinatorTest.kt")
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.test {
    useJUnit()
    maxHeapSize = "512m"
}
