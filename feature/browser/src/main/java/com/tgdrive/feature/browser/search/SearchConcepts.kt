package com.tgdrive.feature.browser.search

import java.text.Normalizer

/**
 * Normalizes text for search: NFKD/NFD decomposition, strips combining diacritics,
 * lowercases, and trims whitespace. E.g. `"résumé"` -> `"resume"`, `"reçu"` -> `"recu"`.
 */
fun normalizeForSearch(text: String): String {
    if (text.isEmpty()) return ""
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
    val sb = StringBuilder(decomposed.length)
    for (ch in decomposed) {
        if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
            sb.append(ch.lowercaseChar())
        }
    }
    return sb.toString()
}

/**
 * Lightweight English/French suffix stemmer guarded against over-stemming short tokens.
 */
fun stemToken(rawToken: String): String {
    val t = normalizeForSearch(rawToken)
    if (t.length < 4) return t
    return when {
        t.length >= 5 && t.endsWith("ies") -> t.dropLast(3) + "y"
        t.length >= 5 && t.endsWith("es") && !t.endsWith("ses") && !t.endsWith("xes") -> t.dropLast(1)
        t.length >= 6 && t.endsWith("shes") -> t.dropLast(2)
        t.length >= 6 && t.endsWith("ches") -> t.dropLast(2)
        t.length >= 6 && t.endsWith("eaux") -> t.dropLast(1)
        t.length >= 6 && t.endsWith("ing") -> {
            val stem = t.dropLast(3)
            if (stem.length >= 4) stem else t
        }
        t.length >= 5 && t.endsWith("ed") -> {
            val stem = t.dropLast(2)
            if (stem.length >= 4) stem else t
        }
        t.endsWith("s") && !t.endsWith("ss") && !t.endsWith("us") && !t.endsWith("is") -> t.dropLast(1)
        else -> t
    }
}

data class AiTagParts(
    val visualTags: List<String>,
    val locations: List<String>,
    val audioMeta: List<String>,
)

/**
 * Curated bilingual (EN + FR + ML Kit label vocabulary) concept clusters and tag helpers
 * for zero-network semantic expansion and structured `ai_tags` presentation.
 */
object SearchConcepts {

    const val LOCATION_PREFIX = "loc:"
    const val AUDIO_PREFIX = "audio:"

    private val CONCEPT_CLUSTERS: List<Set<String>> = listOf(
        // Finance, billing, taxes
        setOf("invoice", "receipt", "bill", "facture", "recu", "ticket", "quittance", "relevé", "releve"),
        setOf("tax", "taxes", "impot", "impots", "fiscal", "declaration", "w2", "1099"),
        setOf("bank", "banking", "statement", "iban", "rib", "banque", "compte", "virement", "wire"),
        setOf("payment", "paid", "paiement", "paye", "transaction", "checkout", "order", "commande"),
        setOf("salary", "payslip", "payroll", "fiche", "paie", "salaire", "remuneration"),
        setOf("contract", "agreement", "lease", "bail", "contrat", "nda", "terms"),
        setOf("insurance", "assurance", "policy", "police", "attestation", "mutuelle"),

        // Identity, legal, travel
        setOf("passport", "passeport", "id", "identity", "identite", "cni", "carte", "license", "permis", "visa"),
        setOf("ticket", "boarding", "flight", "billet", "avion", "vol", "train", "sncf", "itinerary", "itineraire"),
        setOf("hotel", "booking", "reservation", "airbnb", "hebergement", "stay", "sejour"),
        setOf("travel", "trip", "vacation", "holiday", "vacances", "voyage", "tourism", "tourisme"),

        // Medical & health
        setOf("medical", "doctor", "medecin", "docteur", "hospital", "hopital", "clinic", "clinique", "health", "sante"),
        setOf("prescription", "ordonnance", "pharmacy", "pharmacie", "medicine", "medicament", "lab", "analyse"),

        // Education & career
        setOf("resume", "cv", "curriculum", "portfolio", "cover", "lettre", "motivation"),
        setOf("school", "university", "college", "ecole", "universite", "fac", "campus", "diploma", "diplome", "degree"),
        setOf("exam", "test", "quiz", "homework", "devoir", "cours", "lecture", "notes", "study", "etude", "slides", "presentation"),
        setOf("certificate", "certificat", "badge", "transcript", "bulletin"),

        // Vehicles & transport
        setOf("car", "vehicle", "auto", "automobile", "voiture", "vehicule", "sedan", "suv", "truck"),
        setOf("motorcycle", "bike", "bicycle", "moto", "velo", "scooter"),
        setOf("registration", "assurance", "vignette", "garage", "repair", "reparation", "mechanic"),

        // Nature, places, scenes (aligned with ML Kit image labels)
        setOf("beach", "ocean", "sea", "coast", "shore", "sand", "plage", "mer", "cote", "sable", "surf"),
        setOf("mountain", "hill", "peak", "alps", "montagne", "sommet", "randonnee", "hike", "hiking", "trail"),
        setOf("forest", "tree", "trees", "woods", "nature", "jungle", "foret", "arbre", "arbres", "bois", "park", "parc"),
        setOf("sunset", "sunrise", "sky", "clouds", "coucher", "soleil", "ciel", "nuages", "horizon"),
        setOf("night", "stars", "moon", "evening", "nuit", "etoiles", "lune", "soir"),
        setOf("snow", "winter", "ice", "neige", "hiver", "glace", "ski"),
        setOf("rain", "storm", "pluie", "orage", "weather", "meteo"),
        setOf("city", "street", "urban", "building", "architecture", "ville", "rue", "immeuble", "downtown"),
        setOf("river", "lake", "waterfall", "riviere", "lac", "cascade", "water", "eau"),
        setOf("flower", "flowers", "plant", "garden", "fleur", "fleurs", "plante", "jardin"),

        // People & animals
        setOf("dog", "puppy", "chien", "chiot", "canine"),
        setOf("cat", "kitten", "chat", "chaton", "feline"),
        setOf("pet", "animal", "bird", "oiseau", "wildlife", "animaux"),
        setOf("portrait", "selfie", "face", "person", "people", "visage", "personne", "gens", "smile", "sourire"),
        setOf("family", "famille", "baby", "bebe", "child", "kid", "kids", "enfant", "enfants"),
        setOf("friends", "amis", "group", "groupe", "team", "equipe"),

        // Events
        setOf("wedding", "mariage", "bride", "mariee", "ceremony", "ceremonie"),
        setOf("birthday", "anniversaire", "party", "fete", "celebration", "gift", "cadeau"),
        setOf("concert", "festival", "show", "spectacle", "stage", "scene"),

        // Food & drink
        setOf("food", "meal", "dinner", "lunch", "breakfast", "nourriture", "repas", "diner", "dejeuner", "cuisine"),
        setOf("restaurant", "cafe", "coffee", "tea", "bistro", "menu", "pizza", "burger", "cake", "gateau", "dessert"),

        // Tech, media & files
        setOf("screenshot", "screen", "capture", "ecran", "snap"),
        setOf("scan", "scanned", "scanner", "photocopy", "copie", "document", "paper", "papier", "page"),
        setOf("code", "source", "script", "program", "programming", "dev", "developer", "config", "json", "xml"),
        setOf("password", "secret", "credentials", "token", "key", "recovery", "backup", "sauvegarde", "coffre"),
        setOf("music", "song", "audio", "track", "album", "musique", "chanson", "son", "podcast", "voice"),
        setOf("book", "ebook", "novel", "manual", "guide", "livre", "manuel", "roman"),
        setOf("qr", "barcode", "codebarre", "coupon", "voucher", "promo"),
        setOf("house", "home", "apartment", "room", "maison", "appartement", "chambre", "interior", "interieur", "rent", "loyer"),
        setOf("work", "office", "meeting", "project", "travail", "bureau", "reunion", "projet", "report", "rapport"),
    )

    private val TERM_TO_CLUSTER_TERMS: Map<String, Set<String>> by lazy {
        val normalizedClusters = CONCEPT_CLUSTERS.map { cluster ->
            cluster.mapTo(LinkedHashSet()) { normalizeForSearch(it) }
        }
        val map = HashMap<String, MutableSet<String>>(256)
        for (cluster in normalizedClusters) {
            for (term in cluster) {
                val dest = map.getOrPut(term) { LinkedHashSet() }
                dest.addAll(cluster)
                val stemmed = stemToken(term)
                if (stemmed != term && stemmed.length >= 3) {
                    val stemDest = map.getOrPut(stemmed) { LinkedHashSet() }
                    stemDest.addAll(cluster)
                }
            }
        }
        map.mapValues { (key, set) ->
            set.filterTo(LinkedHashSet()) { it != key }
        }
    }

    fun expandConcepts(rawToken: String): Set<String> {
        val norm = normalizeForSearch(rawToken)
        if (norm.length < 2) return emptySet()
        val direct = TERM_TO_CLUSTER_TERMS[norm]
        if (!direct.isNullOrEmpty()) return direct
        val stemmed = stemToken(norm)
        return if (stemmed.length >= 3) TERM_TO_CLUSTER_TERMS[stemmed].orEmpty() else emptySet()
    }

    fun parseAiTagParts(aiTags: String?): AiTagParts {
        if (aiTags.isNullOrBlank()) return AiTagParts(emptyList(), emptyList(), emptyList())
        val visual = ArrayList<String>()
        val locs = ArrayList<String>()
        val audio = ArrayList<String>()
        for (raw in aiTags.split(',')) {
            val tag = raw.trim()
            if (tag.isEmpty()) continue
            val lower = tag.lowercase()
            when {
                lower.startsWith(LOCATION_PREFIX) -> {
                    val value = tag.substring(LOCATION_PREFIX.length).trim()
                    if (value.isNotEmpty() && locs.none { it.equals(value, ignoreCase = true) }) {
                        locs.add(value)
                    }
                }
                lower.startsWith(AUDIO_PREFIX) -> {
                    val value = tag.substring(AUDIO_PREFIX.length).trim()
                    if (value.isNotEmpty() && audio.none { it.equals(value, ignoreCase = true) }) {
                        audio.add(value)
                    }
                }
                else -> {
                    if (visual.none { it.equals(tag, ignoreCase = true) }) {
                        visual.add(tag)
                    }
                }
            }
        }
        return AiTagParts(visualTags = visual, locations = locs, audioMeta = audio)
    }
}
