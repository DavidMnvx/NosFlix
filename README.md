# NosFlix

Une appli Android pour regarder **ta propre bibliothèque Jellyfin**, sur le **téléphone** et sur la **TV** (Google TV, Android TV, projecteurs), avec une interface soignée façon plateforme de streaming.

NosFlix ne fournit aucun contenu : elle lit ce qui est sur **ton** serveur [Jellyfin](https://jellyfin.org).

> *English: NosFlix is a French-language Android client (phone + Google TV / Android TV) for your own Jellyfin server. Free software under the GPL-3.0. It ships no content and is not affiliated with Jellyfin, TMDB or any streaming service.*

## Ce qu'elle sait faire

**Films et séries**
- Accueil immersif sur TV (grande image du titre sélectionné), accueil adapté au téléphone.
- Reprendre la lecture, ajoutés récemment, une ligne par genre, grilles « Voir tout » (tous, pas vus, courts).
- Fiche détaillée : casting, saga, titres similaires, bande-annonce, où le regarder en France, saisons et épisodes.
- « Marquer comme vu » (film, épisode, saison, série).
- Recherche dans la bibliothèque, et sur TMDB pour les titres que tu n'as pas.
- Onglet **Actualité** (avec une clé TMDB) : tendances mondiales, à l'affiche, bientôt au cinéma, séries tendance.
- Liste « À voir plus tard ».

**Lecteur vidéo (VLC)**
- Lit tous les formats courants (MKV, HEVC, AV1, DTS, sous-titres ASS…).
- Pistes audio et sous-titres, décalages, sous-titres du serveur, format d'image, vitesse, égaliseur.
- Langues par défaut (français, version originale…), « Passer l'intro », « Épisode suivant ».
- Prévient quand l'appareil ne sait pas décoder un format en matériel (badge « AV1 »).

**Musique et livres audio**
- Musique façon Spotify : pastilles de style (jazz, rock, électro…), recherche par titre, album ou artiste, playlists, titres likés, file d'attente, lecture aléatoire.
- Livres audio : reprise où tu t'es arrêté, chapitres, vitesse, minuterie de mise en veille.
- Lecture en arrière-plan, écran verrouillé, pastille « en lecture » sur la TV.

**Pour toute la famille**
- Profils (un compte Jellyfin chacun), écran « Qui regarde ? », profil enfant avec les titres choisis par les parents.

**Où que tu sois**
- À la maison, l'appli passe par le réseau local. Ailleurs, elle bascule toute seule sur une **adresse hors de la maison** (par exemple [Tailscale](https://tailscale.com)).

L'interface est en français.

## Installer

1. Télécharge le fichier `NosFlix-xxxxxx.apk` de la dernière version dans [Releases](https://github.com/DavidMnvx/NosFlix/releases).
2. Ouvre-le sur l'appareil : Android demande une fois d'autoriser l'installation d'applis depuis cette source.
   - Sur une TV : copie l'APK sur une clé USB et ouvre-le avec un gestionnaire de fichiers, ou utilise l'appli « Downloader ».
3. Au premier lancement : l'adresse de ton serveur Jellyfin (par exemple `http://192.168.1.10:8096`), ton utilisateur et ton mot de passe.

Les mises à jour se proposent ensuite toutes seules au démarrage (Paramètres → « Mise à jour ? » pour vérifier).

## Réglages utiles

Dans **Paramètres** :

- **Adresse hors de la maison** : une autre adresse du même serveur, essayée quand celle de la maison ne répond pas. Le plus simple et le plus sûr : installer Tailscale sur le serveur et sur tes appareils, puis mettre ici l'adresse Tailscale du serveur (`http://100.x.x.x:8096`). Pas besoin d'ouvrir de port sur ta box.
- **Clé TMDB** (facultative) : active les tendances, les sorties ciné et les fiches enrichies. Elle est gratuite : crée un compte sur [themoviedb.org](https://www.themoviedb.org), puis Paramètres → API.

## Le petit service NosFlix (facultatif)

Sans rien installer de plus, tout fonctionne ; la liste « À voir plus tard » et les réglages des profils enfants restent alors sur chaque appareil.

Le script [`serveur/nosflix-serveur.py`](serveur/nosflix-serveur.py), lancé sur la machine de Jellyfin (port 8097), ajoute :
- la liste « À voir plus tard » partagée entre tous les appareils, par compte, et lisible dans un navigateur (`A-VOIR.txt`) ;
- les profils enfants partagés entre appareils ;
- un dossier de mises à jour sur ton propre serveur.

Il ne demande que Python 3.10 ou plus. Il vérifie chaque demande avec le jeton Jellyfin de l'appli. Exemple de service systemd (adapte les chemins ; les dossiers `/srv/nosflix-updates` et `/srv/nosflix-data` doivent exister et être accessibles en écriture) :

```ini
# /etc/systemd/system/nosflix.service
[Unit]
Description=NosFlix (liste partagée, profils enfants, mises à jour)
After=network-online.target jellyfin.service

[Service]
ExecStart=/usr/bin/python3 -I /opt/nosflix/nosflix-serveur.py
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

## Fabriquer l'appli soi-même

- Android Studio (avec son JDK 17), Android SDK 37.
- Copie `local.properties.example` en `local.properties` et remplis ce qui te concerne : rien de ce fichier n'est partagé.
- `gradlew assembleRelease` fabrique la version rapide (ARM). Ajoute `-Pemu` pour l'essayer sur un émulateur x86_64 du PC.

Organisation du code (`app/src/main/java/fr/nosflix/app/`) :

| Dossier | Contenu |
|---|---|
| `data/` | Client Jellyfin, TMDB, musique et livres audio, réglages, adresses du serveur |
| `ui/` | Écrans (accueil TV et téléphone, fiches, recherche, profils, paramètres), thème, logo |
| `ui/audio/` | Musique, livres audio, lecteur audio |
| `player/` | Lecteur vidéo VLC et ses options |
| `audio/` | Moteur de lecture audio (Media3) |
| `update/` | Mises à jour (service NosFlix ou GitHub) |

## Licence

NosFlix est un logiciel libre sous licence **GPL-3.0** : tu peux l'utiliser, l'étudier, le modifier et le partager, à condition que les versions modifiées restent libres sous la même licence. Voir [LICENSE](LICENSE).

Composants tiers :
- [libVLC](https://www.videolan.org/vlc/libvlc.html) (LGPL-2.1+), [AndroidX Media3](https://developer.android.com/media/media3), [OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/) (Apache-2.0) ;
- police [Inter](https://rsms.me/inter/) (SIL Open Font License 1.1, voir [licenses/Inter-OFL.txt](licenses/Inter-OFL.txt)).

Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB. NosFlix n'est affilié ni à Jellyfin, ni à TMDB, ni à aucune plateforme de streaming.
