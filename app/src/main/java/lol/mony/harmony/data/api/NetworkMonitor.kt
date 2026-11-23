package lol.mony.harmony.data.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * When the device has a network again.
 *
 * This is what makes a reconnect quick rather than merely eventual. The realtime loop's backoff
 * caps at half a minute, so without this a phone coming out of a tunnel could wait that long
 * with its room dead; with it, regaining a network is a nudge that skips the rest of the wait.
 *
 * It reports only *regaining* a network. Losing one is not news the app can act on -- there is
 * nothing to connect to -- and the socket's own failure is what tells the room it is offline.
 */
class NetworkMonitor(private val context: Context) {
    /**
     * A flow that emits once each time a network becomes available after not having been.
     *
     * Android reports the default network as available the moment a callback is registered, so
     * the flow starts from whether the device has one already ([hasNetwork]) and reports only a
     * change. Counting the registration report would nudge every room into a retry as it opened
     * and flash a reconnect that nothing was wrong with; starting from "offline" would miss the
     * case a person is most likely to be watching, a phone whose first network arrives while the
     * app is open.
     */
    fun regained(): Flow<Unit> = callbackFlow {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            // A device without connectivity services cannot tell us anything; the loop's own
            // backoff is the fallback, so the flow stays open and silent.
            awaitClose { }
            return@callbackFlow
        }
        val available = AtomicBoolean(hasNetwork())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Only the change from nothing to something is news; a second network joining a
                // phone that already had one is not a reason to retry anything.
                if (available.compareAndSet(false, true)) trySend(Unit)
            }

            override fun onLost(network: Network) {
                // Tracked rather than reported: what the app can act on is the network coming
                // back, not the one going away.
                if (manager.activeNetwork == null) available.set(false)
            }

            override fun onUnavailable() { available.set(false) }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (denied: SecurityException) {
            // ACCESS_NETWORK_STATE is declared; a device that refuses it anyway is one the
            // backoff has to carry, not one the app can crash on.
            awaitClose { }
            return@callbackFlow
        }
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.conflate()

    /**
     * Whether the device has a default network right now.
     *
     * This is deliberately not "the internet works": the platform reports the default network
     * the same way whether or not it reaches anything, and the question this answers is the
     * narrow one the callback's first report poses -- is that report a change?
     *
     * `activeNetwork` alone is not enough, because it is non-null on a device whose network is
     * disconnected; the connection state is what says whether there is something to reconnect
     * through.
     */
    fun hasNetwork(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        if (manager.activeNetwork == null) return false
        @Suppress("DEPRECATION")
        return manager.activeNetworkInfo?.isConnected == true
    }
}
