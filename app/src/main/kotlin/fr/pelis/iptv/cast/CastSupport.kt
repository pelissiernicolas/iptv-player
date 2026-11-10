package fr.pelis.iptv.cast

import android.content.Context
import android.view.ContextThemeWrapper
import fr.pelis.iptv.R
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Configuration Google Cast : on utilise le récepteur multimédia par défaut de Google
 * (pas besoin d'enregistrer une application réceptrice).
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

object CastSupport {
    private var cached: CastContext? = null
    private var unavailable = false

    /** Contexte Cast, ou null si l'appareil n'a pas les services Google (ex. certaines box Android TV). */
    fun context(context: Context): CastContext? {
        cached?.let { return it }
        if (unavailable) return null
        return try {
            val app = context.applicationContext
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS) {
                unavailable = true
                null
            } else {
                @Suppress("DEPRECATION")
                CastContext.getSharedInstance(app).also { cached = it }
            }
        } catch (_: Exception) {
            unavailable = true
            null
        }
    }

    /**
     * Bouton Cast standard (sélection de l'appareil). Le contexte doit être une FragmentActivity.
     * Le thème AppCompat est requis par la boîte de dialogue de choix d'appareil.
     */
    fun createButton(context: Context): MediaRouteButton? {
        if (context(context) == null) return null
        val themed = ContextThemeWrapper(context, R.style.Theme_IPTV)
        return MediaRouteButton(themed).also { CastButtonFactory.setUpMediaRouteButton(themed, it) }
    }
}
