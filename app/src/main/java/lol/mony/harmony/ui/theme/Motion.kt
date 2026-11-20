package lol.mony.harmony.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
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
