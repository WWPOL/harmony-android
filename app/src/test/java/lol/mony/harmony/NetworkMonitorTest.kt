package lol.mony.harmony

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lol.mony.harmony.data.api.NetworkMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.atomic.AtomicInteger

/**
 * The device's own network changes, which the reconnect uses as a nudge.
 *
 * The distinction that matters is between "a network came back" and "there is a network": the
 * callback fires once when it is registered on a connected phone, and treating that as a regain
 * would make every room retry the moment it opened. These tests drive Robolectric's own
 * `ConnectivityManager` shadow, so they exercise the real registration path rather than a mock
 * of the class under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NetworkMonitorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val shadow: ConnectivityManager
        get() = context.getSystemService(ConnectivityManager::class.java)

    /** The callbacks the monitor registered, which is what a test can drive. */
    private fun callbacks(): Set<ConnectivityManager.NetworkCallback> =
        shadowOf(shadow).networkCallbacks

    /** Takes the network away, as a phone in a tunnel has. */
    private fun goOffline() {
        @Suppress("DEPRECATION")
        shadowOf(shadow).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.DISCONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, false, android.net.NetworkInfo.State.DISCONNECTED))
    }

    /** Puts a default network under the device, as a phone that is already online has. */
    private fun goOnline() {
        val network = ShadowNetwork.newInstance(303)
        @Suppress("DEPRECATION")
        shadowOf(shadow).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, android.net.NetworkInfo.State.CONNECTED))
        val capabilities = NetworkCapabilities()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(shadow).setNetworkCapabilities(network, capabilities)
        shadowOf(shadow).setDefaultNetworkActive(true)
    }

    /**
     * Collects the flow on a scope of its own, so the test may block while it waits.
     *
     * A `runBlocking` here would wait for the collector to finish before the test could drive
     * anything, which is the deadlock this shape avoids.
     */
    private fun collecting(monitor: NetworkMonitor, counted: AtomicInteger): Job =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
            .launch { monitor.regained().collect { counted.incrementAndGet() } }

    /**
     * Waits for the flow to be collected, which is when it registers its callback.
     *
     * The flow is cold on purpose -- a callback registered by a flow nobody listens to would be
     * a wakeup with no reader -- so a test that drove the callbacks immediately after starting
     * the collector would find none and be asserting against its own timing.
     */
    private fun awaitRegistration() {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && callbacks().isEmpty()) Thread.sleep(20)
        assertTrue("the monitor registered no connectivity callback", callbacks().isNotEmpty())
    }

    @Test fun aNetworkThatComesBackIsReported() {
        // Start the way a phone in a tunnel is: no connected network. The first report is then
        // a genuine regain rather than the registration echo the next test covers.
        goOffline()
        val monitor = NetworkMonitor(context)
        assertTrue("the fixture did not start offline", !monitor.hasNetwork())
        val counted = AtomicInteger()
        val collector = collecting(monitor, counted)
        try {
            awaitRegistration()
            callbacks().forEach { it.onAvailable(ShadowNetwork.newInstance(202)) }
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && counted.get() == 0) Thread.sleep(20)
            assertEquals("a network coming back was not reported", 1, counted.get())
        } finally { collector.cancel() }
    }

    /**
     * A phone that is already online is not "regaining" a network when the monitor starts.
     *
     * Android reports the default network as available the moment a callback is registered. If
     * that counted, every room would be nudged into a retry as it opened, and the banner would
     * flash a reconnect that nothing was wrong with.
     */
    @Test fun theNetworkAlreadyUpIsNotARegain() {
        goOnline()
        val monitor = NetworkMonitor(context)
        assertTrue("the fixture has no default network, so it cannot test an echo", monitor.hasNetwork())
        val counted = AtomicInteger()
        val collector = collecting(monitor, counted)
        try {
            awaitRegistration()
            // The registration echo: Android reports the default network as available at once.
            callbacks().forEach { it.onAvailable(ShadowNetwork.newInstance(303)) }
            Thread.sleep(500)
            assertEquals("the network that was already up was reported as a regain", 0, counted.get())
        } finally { collector.cancel() }
    }

    /** A device with no connected network is one the monitor answers honestly about. */
    @Test fun aDeviceWithNoNetworkIsNotOnline() {
        goOffline()
        val monitor = NetworkMonitor(context)
        assertTrue("a device with no connected network claimed to have one", !monitor.hasNetwork())
    }
}
