package fr.pelis.iptv.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

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
 * - contour et fond colorés quand il a le focus, SANS agrandissement : l'élément garde
 *   exactement sa taille et sa place, il ne recouvre jamais ses voisins (lignes de
 *   chaînes, programmes du guide dont la largeur suit la durée…) ;
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
    val color = MaterialTheme.colorScheme.primary

    var result = this
        // Focus direct uniquement (isFocused, pas hasFocus) : une carte dont un bouton interne
        // a le focus n'est pas mise en avant en même temps -> une seule action visible.
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused) {
                Modifier
                    .background(color.copy(alpha = 0.22f), shape)
                    .border(3.dp, color, shape)
            } else {
                Modifier
            },
        )

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

/**
 * Mémoire du focus d'un écran (mode TV) : le dernier élément qui avait le focus, pour le
 * lui rendre quand on revient d'une fiche film/série ou du guide.
 */
class FocusMemory(initial: String?, private val onFocused: (String) -> Unit) {
    /** Élément à refocaliser au retour sur l'écran (null = rien à restaurer). */
    var pending by mutableStateOf(initial)

    fun focused(key: String) = onFocused(key)
}

val LocalFocusMemory = staticCompositionLocalOf<FocusMemory?> { null }

/**
 * Mémorise [key] quand l'élément a le focus et le refocalise au retour sur l'écran.
 * À placer AVANT [tvItem] et le modificateur clickable.
 */
@Composable
fun Modifier.rememberFocus(key: String): Modifier {
    if (!LocalTvMode.current) return this
    val memory = LocalFocusMemory.current ?: return this
    val requester = remember { FocusRequester() }
    if (memory.pending == key) {
        LaunchedEffect(key) {
            withFrameNanos { } // laisse la liste finir de se placer
            runCatching { requester.requestFocus() }
        }
    }
    // Restauration confirmée par le focus réellement reçu (pas par l'absence d'erreur).
    return focusRequester(requester).onFocusChanged {
        if (it.isFocused) {
            memory.pending = null
            memory.focused(key)
        }
    }
}
