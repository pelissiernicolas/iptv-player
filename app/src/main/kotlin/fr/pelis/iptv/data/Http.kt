package fr.pelis.iptv.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Exécute la requête et traite la réponse avec [block] (sur un thread réseau d'OkHttp).
 * Si la coroutine appelante est annulée (catégorie changée, recherche modifiée, écran
 * quitté…), la requête est réellement interrompue avec [Call.cancel], y compris pendant
 * la lecture du corps de la réponse.
 */
suspend fun <T> Call.await(block: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use(block) }
            if (!cont.isActive) return
            result.fold(cont::resume, cont::resumeWithException)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}
