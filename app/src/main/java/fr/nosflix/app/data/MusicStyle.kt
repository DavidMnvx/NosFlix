package fr.nosflix.app.data

import java.text.Normalizer

/**
 * Grandes familles de styles pour les pastilles de la page Musique.
 *
 * Les genres des fichiers sont très variés (« jazz », « Jazz », « post-bop », « Électronique, Techno »…) :
 * on les range ici dans une quinzaine de familles. Un album peut en avoir plusieurs (« jazz-funk »).
 *
 * Les mots entourés d'espaces doivent être des mots entiers (« rock » mais pas « rocksteady »).
 * [fallback] ne sert que si le genre n'entre dans aucune famille (« Alternative » seul → Rock,
 * mais « alternative hip hop » → Rap).
 */
enum class MusicStyle(
    val label: String,
    private val words: List<String>,
    private val fallback: List<String> = emptyList(),
    private val unless: List<String> = emptyList(),
) {
    Jazz("Jazz", listOf("jazz", " bop ", "bebop", " big band ", " swing jazz ", " modern creative ")),
    Rock(
        "Rock",
        listOf(" rock ", " grunge ", " new wave ", " shoegaze ", " britpop "),
        fallback = listOf(" alternative ", " indie ", " progressive ", "psychedel", " psych "),
    ),
    Electro(
        "Électro",
        listOf(
            "electro", " techno ", " house ", " ambient ", " downtempo ", " trip hop ", " idm ", " dance ", "eurodance",
            " trance ", " drum and bass ", " drum n bass ", " jungle ", " breakbeat ", " hardstyle ", " glitch ",
            " leftfield ", " dubstep ", " synth", " edm ", " chillout ", " lounge ",
        ),
        fallback = listOf(" industrial ", " new age "),
    ),
    Pop("Pop", listOf(" pop ", " variete ", " chanson ", " ballad ", " easy listening ", " francophone ", " new romantic ", " britpop ")),
    Rap("Rap & hip-hop", listOf(" hip hop ", " hiphop ", " rap ", " boom bap ", " gangsta ", " dirty south ", " conscious ", " grime ", " trap ", " drill ")),
    Soul("Soul, funk & R&B", listOf(" soul ", "funk", " r b ", " r n b ", " rnb ", " disco ", " gospel ", " new jack swing ", " quiet storm ", " motown ")),
    Metal("Metal", listOf("metal", " grindcore ", " nwobhm ", " blackgaze ", " deathcore ", " djent ")),
    Punk("Punk", listOf("punk", " hardcore ", " screamo ", " crust ", " emo ", " oi "), unless = listOf(" hip hop ", " techno ", " happy hardcore ")),
    Reggae("Reggae & dub", listOf(" reggae ", " dub ", " ska ", " rocksteady ", " dancehall ")),
    Classical(
        "Classique",
        listOf("classical", "classique", "baroque", " opera ", " orchestral ", " symphony ", " chamber music ", " minimalism "),
        unless = listOf(" rock ", " metal ", " pop "),
    ),
    Blues("Blues", listOf(" blues ")),
    Folk("Folk & country", listOf("folk", " country ", " americana ", " bluegrass ", " singer songwriter ", " honky tonk ")),
    World(
        "Musiques du monde",
        listOf(
            " latin ", "afro", " salsa ", " bolero ", " son cubano ", " danzon ", " flamenco ", " bhangra ", " rai ",
            " juju ", " world", " bossa ", " samba ", " cumbia ", " celtic ", " indian ", " fado ", " tango ",
        ),
    ),
    Soundtrack("Bandes originales", listOf("soundtrack", " bandes originales ", " bande originale ", " film ", " stage screen ", " score ", " ost ", " tv series ")),
    Experimental("Expérimental", listOf("experimental", " avant garde ", " noise ", " drone ", " free improvisation ", " musique concrete ")),
    Spoken("Podcasts & humour", listOf(" podcast", " comedy ", " humour ", " humor ", " spoken word ", " non music ", " stand up ")),

    /** Pas de genre, ou un genre qu'on ne sait pas ranger. */
    Other("Autres", emptyList());

    companion object {
        private val Accents = Regex("\\p{Mn}+")
        private val NotWord = Regex("[^a-z0-9]+")

        /** Familles d'un album d'après ses genres ; « Autres » s'il n'en a aucune. */
        fun of(genres: List<String>): Set<MusicStyle> {
            val out = linkedSetOf<MusicStyle>()
            for (genre in genres) {
                val text = normalize(genre)
                if (text.isBlank()) continue
                val hits = entries.filter { s -> s.words.any { it in text } && s.unless.none { it in text } }
                out += hits.ifEmpty { entries.filter { s -> s.fallback.any { it in text } } }
            }
            if (out.isEmpty()) out += Other
            return out
        }

        /** « Électronique, Trip-Hop » → «  electronique trip hop  » (avec un espace de chaque côté). */
        private fun normalize(text: String): String {
            val plain = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Accents, "")
            return " " + plain.replace(NotWord, " ").trim() + " "
        }

        /** Les familles présentes, de la plus fournie à la moins fournie (« Autres » à la fin). */
        fun count(albums: List<AudioAlbum>): List<Pair<MusicStyle, Int>> {
            val counts = mutableMapOf<MusicStyle, Int>()
            for (a in albums) for (s in a.styles) counts[s] = (counts[s] ?: 0) + 1
            return counts.entries
                .sortedWith(compareBy<Map.Entry<MusicStyle, Int>> { it.key == Other }.thenByDescending { it.value })
                .map { it.key to it.value }
        }
    }
}
