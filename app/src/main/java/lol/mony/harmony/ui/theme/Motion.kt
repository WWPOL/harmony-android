package lol.mony.harmony.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Reads the system's animation scale.
 *
 * `Settings.Global.ANIMATOR_DURATION_SCALE` is the switch Android's own "Remove animations"
 * accessibility setting writes. A device that cannot be read (or a JVM test) reports 1.
 */
fun systemAnimationScale(context: android.content.Context): Float =
    runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

/** The motion policy for this composition: the account's preference and the system's scale. */
@Composable
fun rememberMotionPreferences(reduceMotion: Boolean): MotionPreferences {
    val context = LocalContext.current
    val scale = remember(context) { systemAnimationScale(context) }
    return remember(reduceMotion, scale) { MotionPreferences(reduceMotion, scale) }
}

/**
 * The durations the app's own motion is built from, in Material 3 Expressive's order.
 *
 * They are longer than Material 3's classic 200ms because expressive motion is meant to be
 * legible: a sheet that arrives in 300ms reads as deliberate, one that arrives in 120ms reads
 * as a glitch. Each is a *maximum*: [MotionPreferences.duration] collapses it to none.
 *
 * They are the *fallback* the app's own animations use. Where Material draws the motion --
 * a `ShortNavigationBar`'s indicator, a `LoadingIndicator`'s morph -- the durations come from
 * [androidx.compose.material3.MotionScheme] instead, and the theme installs the expressive
 * scheme, so the two agree.
 */
object HarmonyMotion {
    /** The shortest: a pressed state, a chip's selection. */
    const val fast = 120

    /** The default: a pane, a card, a badge. */
    const val standard = 240

    /** A whole screen, or a sheet: the one place a longer travel is worth it. */
    const val slow = 320

    /** How far a card lifts when it is pressed, in dp. */
    const val pressLift = 2f
}

/**
 * A [androidx.compose.material3.MotionScheme] that honours "reduce motion".
 *
 * Material's own expressive scheme is what the app installs by default, and it is the right
 * answer for everybody who has not asked for less. A person who *has* -- with Harmony's own
 * `reduceMotion` preference or with Android's "Remove animations" -- gets every one of the
 * scheme's six specs collapsed to zero milliseconds, so a component the app does not draw
 * itself (a `ShortNavigationBar`'s sliding indicator, a `LoadingIndicator`'s shape morph) stops
 * moving too, instead of animating behind a preference the app says it respects.
 *
 * The specs stay *specs* rather than being replaced by `snap()`: a zero-duration tween still
 * delivers the end state to whatever is listening, which is what keeps a state change visible
 * with the travel removed.
 */
fun motionSchemeFor(preferences: MotionPreferences): androidx.compose.material3.MotionScheme =
    if (!preferences.still) {
        androidx.compose.material3.MotionScheme.expressive()
    } else {
        StillMotionScheme
    }

/** Every spec a zero-duration tween: the end state, with the travel removed. */
private object StillMotionScheme : androidx.compose.material3.MotionScheme {
    private fun <T> still(): FiniteAnimationSpec<T> = tween(durationMillis = 0)
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = still()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = still()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = still()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = still()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = still()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = still()
}
