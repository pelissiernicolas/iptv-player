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
    // Contexte d'application uniquement (CastContext.getSharedInstance(applicationContext)) : pas de fuite d'activité.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var cached: CastContext? = null
    /** Services Google absents : définitif pour ce processus. */
    private var unavailable = false
    /** Échec ponctuel (services Google en cours de mise à jour…) : nouvelle tentative après ce délai. */
    private var retryAfter = 0L
    private const val RETRY_DELAY_MS = 60_000L

    /** Contexte Cast, ou null si l'appareil n'a pas les services Google (ex. certaines box Android TV). */
    fun context(context: Context): CastContext? {
        cached?.let { return it }
        if (unavailable || System.currentTimeMillis() < retryAfter) return null
        return try {
            val app = context.applicationContext
            when (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app)) {
                ConnectionResult.SUCCESS -> {
                    @Suppress("DEPRECATION")
                    CastContext.getSharedInstance(app).also { cached = it }
                }
                ConnectionResult.SERVICE_MISSING, ConnectionResult.SERVICE_INVALID -> {
                    unavailable = true
                    null
                }
                else -> {
                    // Mise à jour requise, désactivés, en cours d'installation… : réessayable.
                    retryAfter = System.currentTimeMillis() + RETRY_DELAY_MS
                    null
                }
            }
        } catch (_: Exception) {
            retryAfter = System.currentTimeMillis() + RETRY_DELAY_MS
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
