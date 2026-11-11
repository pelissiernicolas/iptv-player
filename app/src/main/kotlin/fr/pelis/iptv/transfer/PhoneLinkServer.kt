package fr.pelis.iptv.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Configuration depuis un téléphone : petit serveur web sur le réseau local, ouvert seulement
 * tant que l'écran du QR code est affiché.
 *
 * - Un code aléatoire à usage unique (128 bits), contenu dans le QR code, est exigé partout.
 * - Seules les adresses du réseau local sont acceptées ; trop d'essais erronés ferment le serveur.
 * - Arrêt automatique après [LIFETIME_MS] ; rien de ce qui est reçu n'est journalisé.
 *
 * La page est servie en HTTP (un appareil domestique ne peut pas avoir de certificat valide) :
 * les données circulent en clair sur le Wi-Fi local, le temps de l'envoi.
 */
class PhoneLinkServer(
    private val mode: Mode,
    private val handler: Handler,
    private val code: String = newCode(),
) {
    enum class Mode { LOGIN, BACKUP }

    /** Actions réalisées par l'application ; message renvoyé au téléphone, ou exception. */
    interface Handler {
        suspend fun login(request: LoginRequest): String
        suspend fun importBackup(text: String): String
        suspend fun exportBackup(): Pair<String, String> // nom du fichier, contenu
        fun onFinished()
    }

    @Serializable
    data class LoginRequest(
        val c: String = "",
        val name: String = "",
        val server: String = "",
        val username: String = "",
        val password: String = "",
    ) {
        override fun toString() = "LoginRequest(name=$name, username=***, password=***)"
    }

    @Serializable
    private data class BackupRequest(val c: String = "", val backup: String = "")

    @Serializable
    private data class Reply(val ok: Boolean, val message: String)

    data class Request(val method: String, val path: String, val query: Map<String, String>, val body: String)

    data class Response(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val extraHeaders: List<Pair<String, String>> = emptyList(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: ServerSocket? = null
    private val failures = AtomicInteger()
    private val done = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    /** Adresse à mettre dans le QR code, ou null si l'appareil n'est sur aucun réseau local. */
    var url: String? = null
        private set

    fun start(): String? {
        val address = localAddress() ?: return null
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(address, 0), 8)
        socket = server
        url = "http://${address.hostAddress}:${server.localPort}/?c=$code"
        scope.launch { acceptLoop(server) }
        scope.launch {
            delay(LIFETIME_MS)
            stop()
        }
        return url
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        // Laisse aux réponses en cours le temps de partir, puis libère tout.
        scope.launch {
            delay(5_000)
            scope.cancel()
        }
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                break
            }
            scope.launch { serve(client) }
        }
    }

    private suspend fun serve(client: Socket) {
        client.use { s ->
            s.soTimeout = 15_000
            val response = try {
                if (!isLocal(s.inetAddress)) {
                    text(403, "Accès refusé")
                } else {
                    val request = readRequest(BufferedInputStream(s.getInputStream()))
                    if (request == null) text(400, "Requête invalide") else handle(request)
                }
            } catch (_: Exception) {
                text(400, "Requête invalide")
            }
            runCatching { write(s, response) }
        }
    }

    /** Routage (séparé du réseau pour les tests). */
    suspend fun handle(request: Request): Response {
        val given = when {
            request.method == "GET" -> request.query["c"].orEmpty()
            else -> runCatching { json.decodeFromString<CodeOnly>(request.body).c }.getOrDefault("")
        }
        if (!codeMatches(given)) {
            if (failures.incrementAndGet() >= MAX_FAILURES) stop()
            return text(403, "Lien expiré ou invalide : affichez un nouveau QR code sur la TV.")
        }
        return when {
            request.method == "GET" && request.path == "/" -> html(PhoneLinkPage.render(mode, code))
            request.method == "GET" && request.path == "/api/export" && mode == Mode.BACKUP -> {
                val (fileName, content) = handler.exportBackup()
                Response(
                    200, "application/json; charset=utf-8", content.toByteArray(),
                    listOf("Content-Disposition" to "attachment; filename=\"$fileName\""),
                )
            }
            request.method == "POST" && request.path == "/api/login" && mode == Mode.LOGIN ->
                once { handler.login(json.decodeFromString<LoginRequest>(request.body)) }
            request.method == "POST" && request.path == "/api/backup" && mode == Mode.BACKUP ->
                once { handler.importBackup(json.decodeFromString<BackupRequest>(request.body).backup) }
            else -> text(404, "Introuvable")
        }
    }

    /** Une seule opération à la fois ; terminée avec succès, elle ferme le serveur. */
    private suspend fun once(action: suspend () -> String): Response {
        if (done.get()) return reply(false, "Déjà fait : affichez un nouveau QR code sur la TV.", 410)
        if (!busy.compareAndSet(false, true)) return reply(false, "Opération déjà en cours sur la TV.", 409)
        return try {
            val message = action()
            done.set(true)
            reply(true, message, 200).also {
                stop()
                handler.onFinished()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            reply(false, e.message ?: "Échec", 200)
        } finally {
            busy.set(false)
        }
    }

    private fun codeMatches(given: String) =
        given.isNotEmpty() && MessageDigest.isEqual(given.toByteArray(), code.toByteArray())

    @Serializable
    private data class CodeOnly(val c: String = "")

    private fun reply(ok: Boolean, message: String, status: Int) =
        Response(status, "application/json; charset=utf-8", json.encodeToString(Reply(ok, message)).toByteArray())

    private fun text(status: Int, message: String) =
        Response(status, "text/plain; charset=utf-8", message.toByteArray())

    private fun html(page: String) = Response(200, "text/html; charset=utf-8", page.toByteArray())

    private fun write(s: Socket, r: Response) {
        val reason = when (r.status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
            409 -> "Conflict"; 410 -> "Gone"; else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 ${r.status} $reason\r\n")
            append("Content-Type: ${r.contentType}\r\n")
            append("Content-Length: ${r.body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append(
                "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; " +
                    "style-src 'unsafe-inline'; connect-src 'self'; form-action 'none'; frame-ancestors 'none'\r\n",
            )
            r.extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("Connection: close\r\n\r\n")
        }
        val out = s.getOutputStream()
        out.write(headers.toByteArray(Charsets.ISO_8859_1))
        out.write(r.body)
        out.flush()
    }

    companion object {
        const val LIFETIME_MS = 10 * 60_000L
        private const val MAX_FAILURES = 10
        private const val MAX_HEADER = 8 * 1024
        /** Une sauvegarde (4 Mo) encodée en JSON, avec de la marge. */
        private const val MAX_BODY = 9 * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        fun newCode(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Réseau local uniquement (Wi-Fi / Ethernet de la maison). */
        fun isLocal(address: InetAddress?): Boolean =
            address != null && (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress)

        /** Adresse IPv4 locale de l'appareil (Wi-Fi ou Ethernet). */
        fun localAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrNull()

        /** Lit une requête HTTP/1.1 simple ; null si elle est invalide ou trop grosse. */
        fun readRequest(input: InputStream): Request? {
            val head = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                head.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
                if (head.size() > MAX_HEADER) return null
            }
            val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n")
            val parts = lines.first().split(" ")
            if (parts.size < 3) return null
            val method = parts[0]
            if (method != "GET" && method != "POST") return null
            val target = parts[1]
            val headers = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }.toMap()
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY) return null
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) return null
                read += n
            }
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { kv ->
                val k = kv.substringBefore('=')
                val v = kv.substringAfter('=', "")
                java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
            }
            return Request(method, path, query, body.decodeToString())
        }
    }
}
