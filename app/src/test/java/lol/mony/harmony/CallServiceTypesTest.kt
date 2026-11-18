package lol.mony.harmony

import android.Manifest
import android.content.pm.ServiceInfo
import lol.mony.harmony.voice.CallService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Which foreground-service types a call may claim, and when.
 *
 * This is the rule behind a crash rather than a cosmetic detail. From Android 14, starting a
 * foreground service with a type whose permission the app does not hold throws
 * `SecurityException`, so a call that claimed `microphone` unconditionally would take the app
 * down in the one case the app deliberately supports: a person who refused the microphone and
 * stayed to listen. The service must therefore claim only what it holds, and a call holding
 * nothing must not be put in the foreground at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CallServiceTypesTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun grant(vararg permissions: String) {
        shadowOf(app).grantPermissions(*permissions)
    }

    @Test fun aListenOnlyCallClaimsNoType() {
        // Nothing granted: no microphone, no camera, no screen share.
        assertEquals(0, CallService.claimedTypes(app, screenSharing = false))
    }

    @Test fun aMicrophoneCallClaimsTheMicrophone() {
        grant(Manifest.permission.RECORD_AUDIO)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            CallService.claimedTypes(app, screenSharing = false),
        )
    }

    @Test fun aCameraCallAddsTheCamera() {
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            CallService.claimedTypes(app, screenSharing = false),
        )
    }

    @Test fun screenSharingAddsProjectionButNeverWithoutIt() {
        // The projection consent is a per-session grant, so the caller states it; it is not a
        // permission this can read.
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            CallService.claimedTypes(app, screenSharing = true),
        )
        grant(Manifest.permission.RECORD_AUDIO)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            CallService.claimedTypes(app, screenSharing = true),
        )
    }

    @Test fun aRevokedPermissionIsNotClaimed() {
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            CallService.claimedTypes(app, screenSharing = false),
        )
    }

    @Test fun startingWithNoCaptureDoesNotStartTheService() {
        // `start` must be a no-op rather than a foreground service with type 0, which would
        // show a call notification for a call that is not holding anything.
        CallService.start(app, "general")
        assertEquals(null, shadowOf(app).nextStartedService)
    }

    @Test fun startingWithTheMicrophoneStartsTheService() {
        grant(Manifest.permission.RECORD_AUDIO)
        CallService.start(app, "general")
        assertEquals(CallService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
    }

    @Test
    @Config(sdk = [29])
    fun belowApi30OnlyTheProjectionTypeCanBeNamed() {
        // `microphone` and `camera` are API 30 types; `mediaProjection` is API 29. So on 29 the
        // only type this app can honestly name is projection, and the service still runs for the
        // microphone because holding a capture and naming a type are separate questions.
        grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            CallService.claimedTypes(app, screenSharing = true),
        )
        assertEquals(0, CallService.claimedTypes(app, screenSharing = false))
        assertEquals(true, CallService.holdsCapture(app, screenSharing = false))
        CallService.start(app, "general")
        assertEquals(CallService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
    }

    @Test
    @Config(sdk = [28])
    fun belowApi29NoTypeIsNamedButTheMicrophoneIsStillHeld() {
        // Types did not exist before API 29. A microphone call on API 26-28 must still start the
        // service, or the capture dies the moment the app leaves the foreground.
        grant(Manifest.permission.RECORD_AUDIO)
        assertEquals(0, CallService.claimedTypes(app, screenSharing = true))
        assertEquals(true, CallService.holdsCapture(app, screenSharing = false))
        CallService.start(app, "general")
        assertEquals(CallService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
    }

    @Test fun aListenOnlyCallHoldsNothingToCapture() {
        assertEquals(false, CallService.holdsCapture(app, screenSharing = false))
        grant(Manifest.permission.CAMERA)
        assertEquals(true, CallService.holdsCapture(app, screenSharing = false))
        assertEquals(true, CallService.holdsCapture(app, screenSharing = true))
    }
}
