import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Clé TMDB (tendances mondiales) : à mettre dans local.properties, jamais dans le code.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val tmdbApiKey: String = localProperties.getProperty("tmdb.apiKey", "").trim()

// Réglages propres à une maison, eux aussi dans local.properties (jamais dans le code partagé) :
//   nosflix.edition        « perso » : logo NF ; sinon (version publique) le mot « nosflix »
//   nosflix.homeServer     adresse proposée à la connexion, ex. http://192.168.1.10:8096
//   nosflix.defaultUser    utilisateur proposé à la connexion
//   nosflix.remoteServers  autres adresses du même serveur (Tailscale…), séparées par des virgules
val edition: String = localProperties.getProperty("nosflix.edition", "public").trim().ifEmpty { "public" }
val homeServer: String = localProperties.getProperty("nosflix.homeServer", "").trim()
val defaultUser: String = localProperties.getProperty("nosflix.defaultUser", "").trim()
val remoteServers: String = localProperties.getProperty("nosflix.remoteServers", "").trim()
//   nosflix.updateRepo     dépôt GitHub d'où viennent les mises à jour (vide : aucune ; une version perso
//                          se met à jour par le service NosFlix de son serveur, pas par GitHub)
val updateRepo: String = localProperties.getProperty("nosflix.updateRepo", if (edition == "perso") "" else "DavidMnvx/NosFlix").trim()
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
//   nosflix.signing        chemin d'un keystore.properties (storeFile, storePassword, keyAlias, keyPassword)
//                          pour signer la version « release » ; sans lui, la clé de test (debug) est utilisée.
val signingFile: File? = localProperties.getProperty("nosflix.signing", "").trim()
    .takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }?.takeIf { it.exists() }
val signingProps: Properties? = signingFile?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

// Numéro de version qui augmente tout seul à chaque fabrication (minutes depuis le 1er janvier 2026) :
// l'appli reconnaît ainsi qu'une version déposée sur le serveur est plus récente qu'elle.
val buildMillis = System.currentTimeMillis()
val buildNumber = ((buildMillis - 1_767_225_600_000L) / 60_000L).toInt()
val buildDate: String = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
    .withZone(ZoneId.of("Europe/Paris"))
    .format(Instant.ofEpochMilli(buildMillis))

android {
    namespace = "fr.nosflix.app"
    // Compose 1.13 demande la version 37.1 ou plus de l'API Android.
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "fr.nosflix.app"
        minSdk = 26
        targetSdk = 37
        versionCode = buildNumber
        versionName = "0.3.0"
        buildConfigField("String", "TMDB_API_KEY", quoted(tmdbApiKey))
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
        buildConfigField("boolean", "MONOGRAM", (edition == "perso").toString())
        buildConfigField("String", "DEFAULT_SERVER", quoted(homeServer))
        buildConfigField("String", "DEFAULT_USER", quoted(defaultUser))
        buildConfigField("String", "REMOTE_SERVERS", quoted(remoteServers))
        buildConfigField("String", "UPDATE_REPO", quoted(updateRepo))
    }

    signingConfigs {
        if (signingProps != null && signingFile != null) {
            create("release") {
                storeFile = File(signingFile.parentFile, signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    // Icône et bannière TV : logo NF (perso) ou le mot « nosflix » (version publique).
    sourceSets.getByName("main").res.srcDir(if (edition == "perso") "src/perso/res" else "src/public/res")

    buildTypes {
        debug {
            // Version de test : ARM (téléphone, projecteur) + x86_64 (émulateur Android sur le PC).
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
        release {
            // Version rapide pour le projecteur et les téléphones (ARM).
            // « gradlew assembleRelease -Pemu » ajoute x86_64 pour l'essayer sur l'émulateur du PC.
            ndk {
                abiFilters += if (providers.gradleProperty("emu").isPresent) {
                    listOf("arm64-v8a", "armeabi-v7a", "x86_64")
                } else {
                    listOf("arm64-v8a", "armeabi-v7a")
                }
            }
            // Code optimisé et allégé (R8) : l'interface est bien plus fluide qu'en version de test.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signée avec la clé de nosflix.signing (version publique), sinon avec la clé de test :
            // elle s'installe alors par-dessus la version de test, sans rien perdre.
            signingConfig = signingConfigs.getByName(if (signingProps != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.libvlc)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.androidx.palette)
}
