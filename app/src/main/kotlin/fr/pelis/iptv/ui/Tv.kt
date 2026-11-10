package fr.pelis.iptv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** true quand l'utilisateur a choisi le mode Téléviseur (navigation à la télécommande). */
val LocalTvMode = staticCompositionLocalOf { false }

/** Détection automatique d'un téléviseur / box Android TV. */
fun isTvDevice(context: Context): Boolean {
    val uiMode = context.getSystemService(UiModeManager::class.java)?.currentModeType
    val pm = context.packageManager
    return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
        pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        pm.hasSystemFeature("android.software.leanback_only")
}

/**
 * Élément navigable à la télécommande (mode TV uniquement, sans effet sur téléphone) :
 * - cadre coloré et léger agrandissement quand il a le focus ;
 * - [onLongPress] déclenché par un appui long sur OK ou par la touche Menu.
 *
 * À placer AVANT le modificateur clickable / combinedClickable.
 */
@Composable
fun Modifier.tvItem(
    shape: Shape = RoundedCornerShape(8.dp),
    onLongPress: (() -> Unit)? = null,
): Modifier {
    if (!LocalTvMode.current) return this
    return tvFocusAlways(shape, onLongPress)
}

/** Comme [tvItem], mais actif quel que soit le mode (écran de choix du type d'appareil). */
@Composable
fun Modifier.tvFocusAlways(
    shape: Shape = RoundedCornerShape(8.dp),
    onLongPress: (() -> Unit)? = null,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    var longPressFired by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "tvFocusScale")
    val color = MaterialTheme.colorScheme.primary

    var result = this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.border(3.dp, color, shape) else Modifier)

    if (onLongPress != null) {
        result = result.onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            when (native.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when {
                    event.type == KeyEventType.KeyDown && native.repeatCount == 1 -> {
                        longPressFired = true
                        onLongPress()
                        true
                    }
                    event.type == KeyEventType.KeyDown && native.repeatCount > 1 -> true
                    event.type == KeyEventType.KeyUp && longPressFired -> {
                        // On consomme le relâchement : pas de clic après un appui long.
                        longPressFired = false
                        true
                    }
                    else -> false
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (event.type == KeyEventType.KeyUp) onLongPress()
                    true
                }
                else -> false
            }
        }
    }
    return result
}
