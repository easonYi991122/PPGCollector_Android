package com.example.ppgcollector_android

import android.content.pm.ProviderInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
