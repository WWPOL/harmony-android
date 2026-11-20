package lol.mony.harmony.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's type scale.
 *
 * Harmony's account preferences decide *size*, not *hierarchy*: `fontSize` is the message body
 * size the person picked and `zoomLevel` scales the whole app. Both are applied as a ratio
 * against this scale rather than replacing it, so a person who asks for larger messages still
 * gets a heading that reads as a heading, and the Material roles stay recognisable.
 *
 * The face is the device's own. This APK ships no third-party typeface (the account can name
 * one the instance stores, and the appearance panel says so), which is also what keeps the
 * app's typography correct for every script the system font covers.
 */
object HarmonyType {
    /** The message size the scale is designed against; the account's `fontSize` divides by it. */
    const val BASE_MESSAGE_SP = 15

    /**
     * A multiplier for every text role, from the account's message size and zoom.
     *
     * Compressed so a very large message size does not turn a headline into a wall: the person
     * asked for readable messages, not for the app to become one.
     */
    fun scale(messageSize: Int, zoomPercent: Int): Float {
        val size = messageSize.coerceIn(10, 32).toFloat() / BASE_MESSAGE_SP
        val zoom = zoomPercent.coerceIn(50, 200).toFloat() / 100f
        return (1f + (size - 1f) * 0.55f) * zoom
    }

    /**
     * The role scale, at [multiplier] = 1.
     *
     * The sizes lean expressive: display and headline roles carry the personality (tight
     * tracking, heavier weight), body roles stay plain and readable, and labels are small and
     * weighted rather than uppercased. Line heights are generous because the app is dense.
     */
    fun typography(family: FontFamily = FontFamily.Default, multiplier: Float = 1f): Typography {
        fun style(
            size: Int,
            weight: FontWeight,
            lineHeight: Int = (size * 1.32).toInt(),
            tracking: Double = 0.0,
        ): TextStyle = TextStyle(
            fontFamily = family,
            fontSize = (size * multiplier).sp,
            fontWeight = weight,
            lineHeight = (lineHeight * multiplier).sp,
            letterSpacing = tracking.sp,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
        )
        return Typography(
            displayLarge = style(57, FontWeight.Normal, 64, -0.25),
            displayMedium = style(45, FontWeight.Normal, 52),
            displaySmall = style(36, FontWeight.Normal, 44),
            headlineLarge = style(32, FontWeight.SemiBold, 40),
            headlineMedium = style(28, FontWeight.SemiBold, 36),
            headlineSmall = style(24, FontWeight.SemiBold, 32),
            titleLarge = style(22, FontWeight.SemiBold, 28),
            titleMedium = style(16, FontWeight.SemiBold, 24, 0.15),
            titleSmall = style(14, FontWeight.SemiBold, 20, 0.1),
            bodyLarge = style(16, FontWeight.Normal, 24, 0.5),
            bodyMedium = style(14, FontWeight.Normal, 20, 0.25),
            bodySmall = style(12, FontWeight.Normal, 16, 0.4),
            labelLarge = style(14, FontWeight.Medium, 20, 0.1),
            labelMedium = style(12, FontWeight.Medium, 16, 0.5),
            labelSmall = style(11, FontWeight.Medium, 16, 0.5),
        )
    }
}

/**
 * The app's shape scale.
 *
 * Material 3 Expressive is rounder than Material 3, and the app leans on that deliberately: a
 * channel row, a message card, a sheet and the composer are four different rounds, which is
 * what makes a dense list still read as a hierarchy. [pill] is the one shape a control takes
 * when it is meant to look touchable rather than boxed.
 */
object HarmonyShape {
    val extraSmall: CornerBasedShape = RoundedCornerShape(8.dp)
    val small: CornerBasedShape = RoundedCornerShape(12.dp)
    val medium: CornerBasedShape = RoundedCornerShape(18.dp)
    val large: CornerBasedShape = RoundedCornerShape(24.dp)
    val extraLarge: CornerBasedShape = RoundedCornerShape(32.dp)

    /** The pill a chip, a reaction and a compact action use. */
    val pill: CornerBasedShape = RoundedCornerShape(percent = 50)

    /** The sheet and the docked composer: two large corners, two nearly square ones. */
    val sheet: CornerBasedShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    val shapes = Shapes(extraSmall, small, medium, large, extraLarge)
}

/**
 * The app's own layout constants, so no screen invents its own.
 *
 * Every interactive row is at least [minTouchTarget] tall, which is Android's floor; a row that
 * is shorter is a bug, not a density choice, because it cannot be hit reliably with a thumb or
 * a switch device.
 */
object HarmonySize {
    /** A row a finger can hit; Android's floor is 48dp and Material's list rows are 56dp. */
    val minTouchTarget: Dp = 48.dp
    val rowHeight: Dp = 56.dp
    val gutter: Dp = 16.dp
    val gutterLarge: Dp = 24.dp

    /** The width a reading column is capped at on a wide window. */
    val readingMaxWidth: Dp = 760.dp

    /** The width the channel/room list takes beside a room. */
    val paneListWidth: Dp = 340.dp

    /** The server rail's width on a wide window. */
    val railWidth: Dp = 96.dp
}

/**
 * The width at which the app stops being a single column.
 *
 * 600dp is Material's compact/medium boundary: a phone held upright is compact, a phone on its
 * side, a foldable unfolded and a tablet are not. [HarmonySize] states the widths the panes
 * take; this is the width that decides whether there are panes at all.
 */
const val TWO_PANE_MIN_WIDTH_DP = 600

/** True when a window [widthDp] wide has room for a list beside its detail. */
fun showsTwoPanes(widthDp: Int): Boolean = widthDp >= TWO_PANE_MIN_WIDTH_DP

/** True when a window [widthDp] wide is compact enough for a bottom navigation bar. */
fun isCompactWidth(widthDp: Int): Boolean = widthDp < TWO_PANE_MIN_WIDTH_DP
