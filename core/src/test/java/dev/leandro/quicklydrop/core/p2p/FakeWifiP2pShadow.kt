package dev.leandro.quicklydrop.core.p2p

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

/**
 * Fake P2P framework used to reproduce the BUSY state reported while a group is being
 * removed. The built-in shadow does not cover the config overloads or the group lag.
 */
@Implements(WifiP2pManager::class)
class FakeWifiP2pShadow {

    companion object {
        @Volatile
        var groupPresent = false

        @Volatile
        var groupInfoLagAfterRemove = 0

        @Volatile
        var createGroupBusy = 0

        @Volatile
        var createGroupCalls = 0

        @Volatile
        var removeGroupCalls = 0

        @Volatile
        var groupFrequency = 5745

        private var staleGroupPolls = 0

        fun reset() {
            groupPresent = false
            groupInfoLagAfterRemove = 0
            createGroupBusy = 0
            createGroupCalls = 0
            removeGroupCalls = 0
            groupFrequency = 5745
            staleGroupPolls = 0
        }
    }

    @Implementation
    fun initialize(
        context: Context,
        looper: Looper,
        listener: WifiP2pManager.ChannelListener?,
    ): WifiP2pManager.Channel =
        ReflectionHelpers.newInstance(WifiP2pManager.Channel::class.java)

    @Implementation
    fun createGroup(
        channel: WifiP2pManager.Channel,
        config: WifiP2pConfig,
        listener: WifiP2pManager.ActionListener,
    ) {
        createGroupCalls++
        if (createGroupBusy > 0) {
            createGroupBusy--
            listener.onFailure(WifiP2pManager.BUSY)
            return
        }

        groupPresent = true
        staleGroupPolls = 0
        listener.onSuccess()
    }

    @Implementation
    fun removeGroup(
        channel: WifiP2pManager.Channel,
        listener: WifiP2pManager.ActionListener?,
    ) {
        removeGroupCalls++
        if (groupPresent) {
            groupPresent = false
            staleGroupPolls = groupInfoLagAfterRemove
            listener?.onSuccess()
        } else {
            listener?.onFailure(WifiP2pManager.BUSY)
        }
    }

    @Implementation
    fun requestGroupInfo(
        channel: WifiP2pManager.Channel,
        listener: WifiP2pManager.GroupInfoListener?,
    ) {
        val group = when {
            groupPresent -> group()
            staleGroupPolls > 0 -> {
                staleGroupPolls--
                group()
            }
            else -> null
        }
        listener?.onGroupInfoAvailable(group)
    }

    private fun group(): WifiP2pGroup = WifiP2pGroup().also {
        ReflectionHelpers.setField(it, "mFrequency", groupFrequency)
    }
}
