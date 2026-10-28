package com.fersaiyan.cyanbridge.w610jpeg

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MinimalManifestTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun mergedPermissionsContainOnlyNearbyAndOwnSignatureGuard() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        val allowed = setOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val requested = requireNotNull(info.requestedPermissions).toSet()
        assertTrue(requested.all { it in allowed })
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in requested)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in requested)
    }
    @Test fun noServicesProvidersOrManifestReceivers() {
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS
        val info = app.packageManager.getPackageInfo(app.packageName, flags)
        assertTrue(info.services.isNullOrEmpty())
        assertTrue(info.receivers.isNullOrEmpty())
        assertTrue(info.providers.isNullOrEmpty())
    }
    @Test fun uniquePackageAndSingleLauncherActivity() {
        assertEquals("com.fersaiyan.cyanbridge.w610jpegtest", app.packageName)
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_ACTIVITIES)
        val activities = requireNotNull(info.activities)
        assertEquals(1, activities.size)
        assertEquals(JpegTestActivity::class.java.name, activities.single().name)
    }
}
