package com.example.ppgcollector_android

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that only cache-staged exports can be shared through the provider. */
@RunWith(AndroidJUnit4::class)
class FileProviderContractTest {
    @Test
    fun providerIsPrivateButGrantsExplicitUriAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = "${context.packageName}.fileprovider"
        val provider: ProviderInfo? = context.packageManager.resolveContentProvider(authority, 0)

        assertNotNull("FileProvider must be declared for export/share", provider)
        assertFalse(provider!!.exported)
        assertTrue(provider.grantUriPermissions)
    }

    @Test
    fun captureServiceIsPrivateAndDeclaresConnectedDeviceType() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = context.packageManager.resolveService(
            Intent(context, CaptureForegroundService::class.java),
            0,
        )

        assertNotNull("capture service must be declared", service)
        val serviceInfo = service!!.serviceInfo
        assertFalse(serviceInfo.exported)
        assertTrue(
            serviceInfo.foregroundServiceType and
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0,
        )
    }

    @Test
    fun targetAndForegroundServicePermissionContractIsDeclared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(requested.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(requested.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        assertTrue(requested.contains("android.permission.POST_NOTIFICATIONS"))
        assertEquals(37, context.applicationInfo.targetSdkVersion)
    }
}
