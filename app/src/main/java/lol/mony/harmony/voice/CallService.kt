package lol.mony.harmony.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import lol.mony.harmony.MainActivity
import lol.mony.harmony.R

/**
 * Keeps a call alive while the app is not in front, as a phone call is.
 *
 * Android requires a foreground service with a declared type for the microphone, camera and
 * screen capture; without it the system mutes or stops the capture as soon as the app leaves
 * the foreground. The notification is the visible contract with the person: it says a call is
 * running and gives them one tap to return to it.
 *
 * A type is claimed only when the app actually holds what it names. From Android 14 an
 * undeclared-or-ungranted type is not a warning but a `SecurityException` that would take the
 * call down, and a listen-only join -- a person who refused the microphone and stayed to
 * listen, which this app offers on purpose -- holds no capture at all. Such a call is put in
 * the foreground with no type and then stops itself, because `startForegroundService` obliges
 * the service to post a notification before it may stop, and skipping that is a
 * `RemoteServiceException` rather than a quiet no-op.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "In a call"
        val sharing = intent?.getBooleanExtra(EXTRA_SCREEN_SHARING, false) == true
        val types = claimedTypes(this, screenSharing = sharing)
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(label)
            .setContentText("Tap to return to the call")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        // `startForeground` is called on every path, including the one with nothing to hold:
        // `startForegroundService` obliges the service to post a notification, and returning
        // without one is a `RemoteServiceException`, not a quiet no-op. Claiming no type is
        // legal; claiming one whose permission is gone is not.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        } catch (refused: SecurityException) {
            // A permission was revoked between the check and the claim. A call that cannot hold
            // its capture in the foreground is still a call; it must not be crashed by the
            // notification that describes it.
            stopSelf()
            return START_NOT_STICKY
        }
        // Nothing is being captured, so nothing needs holding in the foreground. This asks
        // whether the call holds a capture, not whether it named a type: on API 26-28 there is
        // no type to name and the service must still stay up for the microphone.
        if (!holdsCapture(this, sharing)) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, getString(R.string.call_channel_name), NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.call_channel_description)
            setShowBadge(false)
            enableVibration(false)
        })
    }

    companion object {
        const val CHANNEL_ID = "harmony-call"
        private const val NOTIFICATION_ID = 4101
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_SCREEN_SHARING = "screenSharing"

        /**
         * The foreground-service types this app may actually claim right now.
         *
         * This is a different question from [holdsCapture], and keeping them apart is what makes
         * old releases work. The type constants arrived in API 29/30, so below them there is no
         * type to name and this is 0 -- which is what `ServiceCompat.startForeground` expects
         * there -- but a foreground service on those releases still holds the microphone, so the
         * service must still run. Gating the whole service on the type would have quietly
         * dropped the background-call protection on API 26-28. `mediaProjection` is API 29;
         * `microphone` and `camera` are API 30, so each is named only from its own release.
         *
         * Screen capture is the one the system grants per session rather than by permission, so
         * it is claimed only once the caller holds the projection consent that starts it.
         * Internal rather than private so the rule can be pinned by a test: claiming a type
         * whose permission is absent is a `SecurityException` on Android 14, not a warning.
         */
        internal fun claimedTypes(context: Context, screenSharing: Boolean): Int {
            var types = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (granted(context, Manifest.permission.RECORD_AUDIO)) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (granted(context, Manifest.permission.CAMERA)) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            if (screenSharing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            return types
        }

        /**
         * Whether the call is capturing anything that a foreground service has to hold.
         *
         * A listen-only join -- a person who refused the microphone and stayed to listen, which
         * this app offers on purpose -- captures nothing, so it needs no service and is not
         * given a call notification for a call that holds nothing.
         */
        internal fun holdsCapture(context: Context, screenSharing: Boolean): Boolean =
            granted(context, Manifest.permission.RECORD_AUDIO) ||
                granted(context, Manifest.permission.CAMERA) ||
                screenSharing

        private fun granted(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        /**
         * Holds a running call in the foreground when it has a capture to hold. Safe to call
         * again: a call that gains the microphone or starts sharing its screen calls it once
         * more to claim the type it has just earned.
         *
         * A call with no capture at all -- the listen-only join the app offers on purpose --
         * starts nothing, so a person who refused the microphone is not shown a call
         * notification for a call that is holding nothing.
         */
        fun start(context: Context, label: String, screenSharing: Boolean = false) {
            if (!holdsCapture(context, screenSharing)) return
            val intent = Intent(context, CallService::class.java)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_SCREEN_SHARING, screenSharing)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
