package fr.nosflix.app.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Mémorise la connexion, les profils et les réglages sur l'appareil. */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("nosflix", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE, null)
            ?: UUID.randomUUID().toString().replace("-", "").also { id ->
                prefs.edit { putString(KEY_DEVICE, id) }
            }

    val lastServer: String? get() = prefs.getString(KEY_SERVER, null)
    val lastUser: String? get() = prefs.getString(KEY_USER_NAME, null)

    var externalPlayer: Boolean
        get() = prefs.getBoolean(KEY_EXTERNAL, false)
        set(value) = prefs.edit { putBoolean(KEY_EXTERNAL, value) }

    // ---------------------------------------------------------------- Liste d'envies (une par profil)

    /** Liste d'envies (« À voir plus tard ») du profil : copie sur l'appareil de la liste du serveur. */
    fun wishlist(userId: String): List<WishItem> {
        val raw = prefs.getString("$KEY_WISHLIST:$userId", null) ?: return emptyList()
        return runCatching { wishesFromJson(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun saveWishlist(userId: String, items: List<WishItem>) {
        prefs.edit { putString("$KEY_WISHLIST:$userId", items.toJsonArray().toString()) }
    }

    /**
     * Changements faits sur cet appareil et pas encore envoyés au serveur :
     * clé TMDB (« movie:123 ») → [WISH_ADD] ou [WISH_REMOVE].
     */
    fun wishPending(userId: String): Map<String, String> {
        val raw = prefs.getString("$KEY_WISH_PENDING:$userId", null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optString(it) }
        }.getOrDefault(emptyMap())
    }

    fun saveWishPending(userId: String, ops: Map<String, String>) {
        prefs.edit { putString("$KEY_WISH_PENDING:$userId", JSONObject(ops as Map<*, *>).toString()) }
    }

    /** La liste de ce profil a déjà été accordée une fois avec celle du serveur. */
    fun wishSynced(userId: String): Boolean = prefs.getBoolean("$KEY_WISH_SYNCED:$userId", false)

    fun setWishSynced(userId: String) = prefs.edit { putBoolean("$KEY_WISH_SYNCED:$userId", true) }

    /** Avant les profils, la liste était rangée sans compte : elle revient au compte qui l'avait. */
    fun migrateWishlist(currentUserId: String) {
        val legacy = prefs.getString(KEY_WISHLIST, null)
        val legacyPending = prefs.getString(KEY_WISH_PENDING, null)
        if (legacy == null && legacyPending == null) return
        val owner = prefs.getString(KEY_WISH_OWNER, null)
        val target = owner ?: currentUserId
        prefs.edit {
            if (legacy != null && !prefs.contains("$KEY_WISHLIST:$target")) putString("$KEY_WISHLIST:$target", legacy)
            if (legacyPending != null && !prefs.contains("$KEY_WISH_PENDING:$target")) putString("$KEY_WISH_PENDING:$target", legacyPending)
            if (owner != null) putBoolean("$KEY_WISH_SYNCED:$target", true)
            remove(KEY_WISHLIST)
            remove(KEY_WISH_PENDING)
            remove(KEY_WISH_OWNER)
        }
    }

    // ---------------------------------------------------------------- Profils

    /** Profils connus (comptes Jellyfin), pour l'écran « Qui regarde ? ». */
    fun profiles(): List<Profile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Profile(o.optString("id"), o.optString("name"), o.optBoolean("admin"), o.optBoolean("password"))
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun saveProfiles(list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { p -> arr.put(JSONObject().put("id", p.id).put("name", p.name).put("admin", p.isAdmin).put("password", p.hasPassword)) }
        prefs.edit { putString(KEY_PROFILES, arr.toString()) }
    }

    /** Jeton Jellyfin de chaque profil déjà ouvert sur cet appareil (on ne redemande rien). */
    fun profileToken(userId: String): String? = prefs.getString("$KEY_PROFILE_TOKEN:$userId", null)

    fun saveProfileToken(userId: String, token: String) = prefs.edit { putString("$KEY_PROFILE_TOKEN:$userId", token) }

    fun removeProfileToken(userId: String) = prefs.edit { remove("$KEY_PROFILE_TOKEN:$userId") }

    /** Jeton d'un compte parent (administrateur) : créer des profils, choisir les titres des enfants. */
    var adminToken: String?
        get() = prefs.getString(KEY_ADMIN_TOKEN, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_ADMIN_TOKEN) else putString(KEY_ADMIN_TOKEN, value) }

    /** Profils enfants et titres autorisés : copie de la liste du serveur. */
    fun kids(): KidsConfig {
        val raw = prefs.getString(KEY_KIDS, null) ?: return KidsConfig()
        return runCatching { kidsFromJson(JSONObject(raw)) }.getOrDefault(KidsConfig())
    }

    fun saveKids(kids: KidsConfig) = prefs.edit { putString(KEY_KIDS, kids.toJson().toString()) }

    /** Identifiant du serveur Jellyfin de la maison, pour le retrouver hors de la maison sans se tromper. */
    var serverId: String?
        get() = prefs.getString(KEY_SERVER_ID, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_SERVER_ID) else putString(KEY_SERVER_ID, value) }

    /** Paramètres → « Adresse hors de la maison » (Tailscale, Internet…) ; vide : aucune. */
    var awayServer: String
        get() = prefs.getString(KEY_AWAY_SERVER, null).orEmpty()
        set(value) = prefs.edit { if (value.isBlank()) remove(KEY_AWAY_SERVER) else putString(KEY_AWAY_SERVER, value.trim()) }

    /** Clé TMDB collée dans les Paramètres (quand l'appli n'en a pas à la fabrication). */
    var tmdbKey: String
        get() = prefs.getString(KEY_TMDB, null).orEmpty()
        set(value) = prefs.edit { if (value.isBlank()) remove(KEY_TMDB) else putString(KEY_TMDB, value.trim()) }

    // ---------------------------------------------------------------- Session en cours

    fun load(): Session? {
        val server = prefs.getString(KEY_SERVER, null) ?: return null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return Session(server, userId, token, prefs.getString(KEY_USER_NAME, null).orEmpty())
    }

    fun save(session: Session) = prefs.edit {
        putString(KEY_SERVER, session.server)
        putString(KEY_USER_ID, session.userId)
        putString(KEY_TOKEN, session.token)
        putString(KEY_USER_NAME, session.userName)
        putString("$KEY_PROFILE_TOKEN:${session.userId}", session.token)
    }

    /** Déconnexion : on oublie tous les profils de l'appareil ; on garde l'adresse du serveur et le nom. */
    fun clear() = prefs.edit {
        remove(KEY_USER_ID)
        remove(KEY_TOKEN)
        remove(KEY_ADMIN_TOKEN)
        remove(KEY_PROFILES)
        prefs.all.keys.filter { it.startsWith("$KEY_PROFILE_TOKEN:") }.forEach { remove(it) }
    }

    companion object {
        const val WISH_ADD = "add"
        const val WISH_REMOVE = "remove"
        private const val KEY_WISH_PENDING = "wishPending"
        private const val KEY_WISH_OWNER = "wishOwner"
        private const val KEY_WISH_SYNCED = "wishSynced"
        private const val KEY_DEVICE = "deviceId"
        private const val KEY_SERVER = "server"
        private const val KEY_SERVER_ID = "serverId"
        private const val KEY_AWAY_SERVER = "awayServer"
        private const val KEY_TMDB = "tmdbKey"
        private const val KEY_USER_ID = "userId"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER_NAME = "userName"
        private const val KEY_EXTERNAL = "externalPlayer"
        private const val KEY_WISHLIST = "wishlist"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_PROFILE_TOKEN = "profileToken"
        private const val KEY_ADMIN_TOKEN = "adminToken"
        private const val KEY_KIDS = "kids"
    }
}
