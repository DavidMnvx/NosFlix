package fr.nosflix.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import fr.nosflix.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Une version de NosFlix déposée sur le serveur de la maison. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val date: String,
    val notes: List<String>,
    val apkUrl: String,
)

/**
 * Mises à jour sans clé USB : le serveur garde la dernière version dans un petit dossier
 * partagé (port 8097). L'appli la télécharge puis demande à Android de l'installer.
 */
object Updater {

    /** Port du dossier des mises à jour sur le serveur (service « nosflix-updates »). */
    private const val PORT = 8097

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val currentName: String get() = BuildConfig.VERSION_NAME
    val currentDate: String get() = BuildConfig.BUILD_DATE

    /** Adresse du service NosFlix, sur la même machine que Jellyfin. */
    internal fun baseUrl(server: String): String? {
        val host = Uri.parse(server).host ?: return null
        return "http://$host:$PORT"
    }

    /**
     * La version la plus récente si elle l'est plus que celle installée, sinon null.
     * D'abord le service NosFlix du serveur (s'il y en a un), sinon la dernière version publiée
     * sur GitHub (nosflix.updateRepo, vide pour une version perso).
     */
    suspend fun check(server: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val fromServer = runCatching { checkServer(server) }
        fromServer.getOrNull()?.let { return@withContext it }
        if (BuildConfig.UPDATE_REPO.isBlank()) return@withContext fromServer.getOrThrow()
        checkGitHub(BuildConfig.UPDATE_REPO)
    }

    /**
     * Dernière version publiée sur GitHub. Le fichier joint s'appelle « NosFlix-<numéro>.apk »,
     * le numéro étant le versionCode de la fabrication.
     */
    private fun checkGitHub(repo: String): UpdateInfo? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val json = http.newCall(request).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IllegalStateException("GitHub ne répond pas (${r.code}).")
            JSONObject(r.body.string())
        }
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .mapNotNull { a ->
                val code = Regex("""NosFlix-(\d+)\.apk""").matchEntire(a.optString("name"))?.groupValues?.get(1)?.toIntOrNull()
                code?.let { it to a.optString("browser_download_url") }
            }
            .maxByOrNull { it.first } ?: return null
        if (apk.first <= BuildConfig.VERSION_CODE) return null
        val notes = json.optString("body").lines().map { it.trim().removePrefix("-").removePrefix("*").trim() }.filter { it.isNotBlank() }
        return UpdateInfo(
            versionCode = apk.first,
            versionName = json.optString("tag_name").removePrefix("v").ifBlank { "nouvelle version" },
            date = json.optString("published_at").take(10),
            notes = notes,
            apkUrl = apk.second,
        )
    }

    private fun checkServer(server: String): UpdateInfo? {
        val base = baseUrl(server) ?: return null
        val request = Request.Builder().url("$base/version.json").build()
        val text = http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body.string()
        }
        val json = JSONObject(text)
        val code = json.optInt("versionCode", 0)
        if (code <= BuildConfig.VERSION_CODE) return null
        val notes = json.optJSONArray("notes")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        return UpdateInfo(
            versionCode = code,
            versionName = json.optString("versionName").ifBlank { "nouvelle version" },
            date = json.optString("date"),
            notes = notes,
            apkUrl = "$base/" + json.optString("apk").ifBlank { "NosFlix.apk" },
        )
    }

    /** Télécharge la nouvelle version ; [onProgress] reçoit 0..1. */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "NosFlix.apk")
        http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Téléchargement refusé par le serveur (${r.code}).")
            val total = r.body.contentLength().takeIf { it > 0 } ?: -1L
            r.body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        file
    }

    /**
     * Confie le fichier à Android, qui affiche sa fenêtre « Mettre à jour cette appli ? »
     * (et, la première fois, demande d'autoriser NosFlix à installer des applis).
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("NosFlix.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }
}
