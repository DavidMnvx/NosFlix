package fr.nosflix.app.data

import android.net.Uri
import fr.nosflix.app.BuildConfig

/**
 * Les adresses du serveur de la maison.
 *
 * À la maison, NosFlix passe par le réseau local. Ailleurs, il passe par une autre adresse
 * du même serveur (Tailscale, ou une adresse sur Internet). Le choix se fait tout seul
 * (voir JellyfinClient.reroute) ; l'identifiant du serveur évite de se brancher sur un autre Jellyfin.
 *
 * Les adresses viennent de l'adresse de connexion, de local.properties (nosflix.homeServer,
 * nosflix.remoteServers) et de Paramètres → « Adresse hors de la maison ».
 */
object ServerRoute {

    /** Adresses connues dès la fabrication de l'appli (local.properties). */
    private val builtIn: List<String> = (listOf(BuildConfig.DEFAULT_SERVER) + BuildConfig.REMOTE_SERVERS.split(','))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map(JellyfinClient::normalizeServer)

    /** Des adresses hors de la maison sont déjà prévues à la fabrication (nosflix.remoteServers). */
    val hasBuiltInRemote: Boolean get() = BuildConfig.REMOTE_SERVERS.isNotBlank()

    /** « Adresse hors de la maison » choisie dans les Paramètres (vide : aucune). */
    @Volatile
    var away: String = ""
        set(value) {
            field = value.trim().takeIf { it.isNotEmpty() }?.let(JellyfinClient::normalizeServer).orEmpty()
        }

    /** Les adresses où chercher le serveur, celles du réseau local d'abord. */
    fun candidates(server: String?): List<String> =
        (listOfNotNull(server) + builtIn + listOfNotNull(away.takeIf { it.isNotEmpty() }))
            .distinct()
            .sortedBy { if (isLocal(it)) 0 else 1 }

    /** L'adresse connue par laquelle commence [url], s'il y en a une. */
    fun baseOf(url: String, server: String?): String? =
        candidates(server).firstOrNull { url == it || url.startsWith("$it/") || url.startsWith("$it?") }

    /** Comment l'appareil joint le serveur, pour les paramètres. */
    fun label(server: String): String = when {
        isLocal(server) -> "Réseau de la maison"
        isTailscale(server) -> "Tailscale (hors de la maison)"
        else -> "Internet"
    }

    private fun host(server: String): String = Uri.parse(server).host.orEmpty().lowercase()

    private fun isLocal(server: String): Boolean {
        val h = host(server)
        if (h.isEmpty()) return false
        if (h.startsWith("192.168.") || h.startsWith("10.") || h.endsWith(".local") || h == "localhost" || '.' !in h) return true
        val parts = h.split('.')
        return parts.size == 4 && parts[0] == "172" && (parts[1].toIntOrNull() ?: 0) in 16..31
    }

    private fun isTailscale(server: String): Boolean {
        val h = host(server)
        if (h.endsWith(".ts.net")) return true
        val parts = h.split('.').map { it.toIntOrNull() ?: return false }
        // 100.64.0.0/10 : les adresses données par Tailscale.
        return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
    }
}
