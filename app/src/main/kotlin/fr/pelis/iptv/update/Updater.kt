package fr.pelis.iptv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.IntentCompat
import android.os.Handler
import android.os.Looper
import fr.pelis.iptv.data.await
import fr.pelis.iptv.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Release GitHub plus récente que la version installée. */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val pageUrl: String,
)

/**
 * Mise à jour de l'application depuis les releases GitHub du projet.
 * Seuls les APK publiés dans les releases de [REPO] sont acceptés ; Android vérifie
 * en plus que la mise à jour est signée avec la même clé que l'application installée.
 */
object Updater {
    const val REPO = "pelissiernicolas/iptv-player"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Dernière release si elle est plus récente que [current], sinon null. */
    suspend fun checkLatest(current: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API_LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "IPTVPlayer-Updater")
            .build()
        val body = http.newCall(request).await { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub a répondu HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        val root = json.parseToJsonElement(body) as? JsonObject ?: return@withContext null
        val version = root.str("tag_name")?.removePrefix("v")?.removePrefix("V") ?: return@withContext null
        if (!isNewer(version, current)) return@withContext null
        val apks = (root["assets"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.filter { it.str("name")?.endsWith(".apk", ignoreCase = true) == true }
            .orEmpty()
        // Plusieurs APK : on prend celui nommé IPTV-Player-<version>.apk, sinon le seul présent.
        val apk = apks.firstOrNull { it.str("name").equals("IPTV-Player-$version.apk", ignoreCase = true) }
            ?: apks.singleOrNull()
            ?: return@withContext null
        val url = apk.str("browser_download_url") ?: return@withContext null
        if (!url.startsWith(DOWNLOAD_PREFIX)) return@withContext null
        UpdateInfo(
            version = version,
            notes = cleanNotes(root.str("body").orEmpty()),
            apkUrl = url,
            sizeBytes = apk.str("size")?.toLongOrNull() ?: -1,
            pageUrl = root.str("html_url") ?: "https://github.com/$REPO/releases",
        )
    }

    /**
     * "1.10.0" > "1.9.2". Une préversion ("1.4.0-beta", "1.4.0-test") est plus ancienne
     * que la version finale correspondante.
     */
    fun isNewer(remote: String, current: String): Boolean {
        fun numbers(v: String) = v.substringBefore('-').split('.').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val r = numbers(remote)
        val c = numbers(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        // Mêmes numéros : seule une version finale remplace une préversion.
        return '-' !in remote && '-' in current
    }

    /** Notes de version Markdown -> texte simple pour la boîte de dialogue. */
    private fun cleanNotes(markdown: String): String = markdown.lines()
        .map { line ->
            line.trimEnd()
                .replace(Regex("^#+\\s*"), "")
                .replace(Regex("^>\\s*"), "")
                .replace(Regex("^[-*]\\s+"), "• ")
                .replace("**", "")
                .replace("`", "")
                .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /**
     * Télécharge l'APK dans le cache de l'application. [onProgress] reçoit 0..1 sur le thread
     * principal (au plus une fois par pour-cent). Annuler la coroutine interrompt le téléchargement.
     */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates")
        val target = File(dir, "IPTV-Player-${info.version}.apk")
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
        }
        val main = Handler(Looper.getMainLooper())
        val request = Request.Builder().url(info.apkUrl).header("User-Agent", "IPTVPlayer-Updater").build()
        try {
            http.newCall(request).await { resp ->
                if (!resp.isSuccessful) throw IOException("Téléchargement refusé (HTTP ${resp.code})")
                val body = resp.body ?: throw IOException("Réponse vide")
                val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPercent = -1L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                val percent = read * 100 / total
                                if (percent > lastPercent) {
                                    lastPercent = percent
                                    val value = (read.toFloat() / total).coerceAtMost(1f)
                                    main.post { onProgress(value) }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        if (info.sizeBytes > 0 && target.length() != info.sizeBytes) {
            target.delete()
            throw IOException("fichier incomplet")
        }
        return target
    }

    /** Android 8+ : l'utilisateur doit autoriser l'application à installer des APK. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val security = Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android 7 : l'autorisation « sources inconnues » est globale, dans les réglages de sécurité.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            runCatching { context.startActivity(security) }
            return
        }
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { runCatching { context.startActivity(security) } }
    }

    /**
     * Prépare l'installation via le PackageInstaller d'Android (copie de l'APK hors du thread
     * principal) puis la soumet : Android demande ensuite la confirmation à l'utilisateur.
     * En cas d'échec ou d'annulation, la session est abandonnée proprement.
     */
    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                    session.fsync(out)
                }
                val intent = Intent(context, UpdateResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    const val ACTION_INSTALL_RESULT = "fr.pelis.iptv.UPDATE_INSTALL_RESULT"
}

/** Résultat de l'installation : affiche la confirmation Android, ou l'erreur éventuelle. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Toast.makeText(context, "Mise à jour annulée", Toast.LENGTH_SHORT).show()
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT ->
                        "conflit avec l'application installée (clé de signature différente ou version plus ancienne)"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "cette version n'est pas compatible avec l'appareil"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "fichier d'installation invalide ou abîmé"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "installation bloquée par l'appareil"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "espace de stockage insuffisant"
                    else -> detail ?: "erreur $status"
                }
                Toast.makeText(context, "Échec de la mise à jour : $reason", Toast.LENGTH_LONG).show()
            }
        }
    }
}
