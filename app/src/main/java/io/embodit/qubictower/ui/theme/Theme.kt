package io.embodit.qubictower.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.qubictower.domain.Language
import io.embodit.qubictower.platform.Graph
import java.util.Locale

/**
 * design-package colours: indigo app colour (icon / primary), amber and celadon beads, graphite tower. Text colours are
 * solid ink measured on the window glass (D13: no Vibrant grey).
 */
object QtColors {
    val Primary = Color(0xFF2A2D5C)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFD9DBF2)
    val Secondary = Color(0xFF7A5A2E)
    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF16181D)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF353A42)
    /** Error text on glass; the brighter Error stays for 3D marks and button fills. */
    val ErrorText = Color(0xFF7A1A18)
    val Warn = Color(0xFFE0A040)

    // 3D, tags, beams
    val Amber = Color(0xFFE8873A)
    val Celadon = Color(0xFF2FA39A)
    /** Darker bead colours for text / chips on light faces (contrast). */
    val AmberInk = Color(0xFF8A4A12)
    val CeladonInk = Color(0xFF0F5E58)
    val Graphite = Color(0xFF2B2F36)
    val Frost = Color(0xFFE6EAEE)
    /** Peg-top marker: the frosted peg's colour family, half a shade darker, opaque (board-input 通用模式). */
    val PegMarker = Color(0xFFB4BCC6)
    /** The selected peg (confirm phase): a cool blue none of the beams / rings use. */
    val SelectGlow = Color(0xFF5DB8FF)
    val BeamWhite = Color(0xFFF4F6F8)
    val BeamGrey = Color(0xFF9AA0A8)
    val Danger = Color(0xFFE5483F)
    val HintGreen = Color(0xFF3CC46B)
    val LitWarm = Color(0xFFFFE2A8)
    val HoverCream = Color(0xFFFBF3E0)
    /** Tags on the plinth's dark front face: a dark plate with light ink (sample: 实墨色、不用 Vibrant 灰). */
    val TagFace = Color(0xFF15171B)
    val TagInk = Color(0xFFF2EFE8)
    /** Light cards in the control window. */
    val CardFace = Color(0xFFF7F6F2)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). The language setting localises resources below this
 * point through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun QubicTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = QtColors.OnPrimary,
        labelPrimary = QtColors.Ink,
        labelSecondary = QtColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = QtColors.Error,
        alert = QtColors.Warn,
        passable = QtColors.Secondary,
        interaction = QtColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
