package dev.leandro.quicklydrop.core.p2p

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pPermissionsTest {

    @Test
    fun `api 35 only asks for nearby and notification permissions`() {
        val permissions = P2pPermissions.required(35)

        assertFalse(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertFalse(Manifest.permission.BLUETOOTH_SCAN in permissions)
    }

    @Test
    fun `api 32 still asks for fine location`() {
        val permissions = P2pPermissions.required(32)

        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertFalse(Manifest.permission.NEARBY_WIFI_DEVICES in permissions)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in permissions)
    }
}
