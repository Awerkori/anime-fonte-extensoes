package eu.kanade.tachiyomi.animeextension.pt.anikyuu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.Normalizer
import java.util.Locale

data class CatalogEntry(
    val name: String,
    val link: String,
    val cover: String,
    val genres: String,
    val themes: String,
    val explicitGenres: String,
    val demographics: String,
    val altName: String,
)

data class LancamentoEntry(
    val title: String,
    val cover: String,
    val link: String,
    val episode: String,
    val audio: String,
    val isFixo: Boolean,
)

data class AnimeCard(
    val title: String,
    val cover: String,
    val url: String,
)

object AnikyuuHelper {

    private val ROMAJI_PARTICLES = setOf(
        "no", "wa", "de", "kara", "to", "ga", "ni", "e", "o", "wo",
        "tachi", "aru", "da", "shite", "san", "chan", "kun", "sama",
        "den", "a", "in", "the", "mo", "na", "dakedo", "yori",
    )

    fun parseCatalogCsv(text: String): List<CatalogEntry> {
        val rows = parseCsvRows(text)
        if (rows.isEmpty()) return emptyList()
        return rows.drop(1).mapNotNull { cols ->
            if (cols.size < 3) return@mapNotNull null
            val nome = cols.getOrNull(0)?.trim().orEmpty()
            val link = cols.getOrNull(1)?.trim().orEmpty()
            val capa = cols.getOrNull(2)?.trim().orEmpty()
            if (nome.isEmpty() || link.isEmpty() || capa.isEmpty()) return@mapNotNull null
            CatalogEntry(
                name = nome,
                link = link,
                cover = capa,
                genres = cols.getOrNull(3)?.trim().orEmpty(),
                themes = cols.getOrNull(4)?.trim().orEmpty(),
                explicitGenres = cols.getOrNull(5)?.trim().orEmpty(),
                demographics = cols.getOrNull(6)?.trim().orEmpty(),
                altName = cols.getOrNull(7)?.trim().orEmpty(),
            )
        }
    }

    fun parseCsvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentField = StringBuilder()
        val currentRow = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        currentField.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRow.add(currentField.toString())
                        currentField.setLength(0)
                    }
                    '\r' -> {}
                    '\n' -> {
                        currentRow.add(currentField.toString())
                        currentField.setLength(0)
                        rows.add(currentRow.toList())
                        currentRow.clear()
                    }
                    else -> currentField.append(c)
                }
            }
            i++
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            rows.add(currentRow.toList())
        }
        return rows.filter { row -> row.any { it.isNotBlank() } }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseLancamentos(text: String): List<LancamentoEntry> {
        val jsonStart = text.indexOf('{')
        val jsonEnd = text.lastIndexOf('}')
        if (jsonStart == -1 || jsonEnd == -1) return emptyList()
        val jsonStr = text.substring(jsonStart, jsonEnd + 1)
        val root = json.decodeFromString<JsonObject>(jsonStr)
        val table = root["table"]?.jsonObject ?: return emptyList()
        val rows = table["rows"]?.jsonArray ?: return emptyList()

        val reversedRows = rows.asReversed()
        val fixos = mutableListOf<LancamentoEntry>()
        val normais = mutableListOf<LancamentoEntry>()

        for (rowElement in reversedRows) {
            val row = rowElement.jsonObject
            val c = row["c"]?.jsonArray ?: continue
            val foto = (c.getOrNull(0) as? JsonObject)?.get("v")?.jsonPrimitive?.contentOrNull.orEmpty()
            val nome = (c.getOrNull(1) as? JsonObject)?.get("v")?.jsonPrimitive?.contentOrNull.orEmpty()
            val episodio = (c.getOrNull(2) as? JsonObject)?.get("v")?.jsonPrimitive?.contentOrNull.orEmpty()
            val idioma = (c.getOrNull(3) as? JsonObject)?.get("v")?.jsonPrimitive?.contentOrNull.orEmpty()
            val link = (c.getOrNull(4) as? JsonObject)?.get("v")?.jsonPrimitive?.contentOrNull.orEmpty()
            val qualidade = (c.getOrNull(6) as? JsonObject)?.get("v")?.jsonPrimitive?.contentOrNull.orEmpty().uppercase()

            if (nome.isBlank() || link.isBlank()) continue

            val isFixo = qualidade.contains("FIXO")
            val entry = LancamentoEntry(
                title = nome.trim(),
                cover = foto,
                link = link,
                episode = episodio,
                audio = idioma,
                isFixo = isFixo,
            )
            if (isFixo) fixos.add(entry) else normais.add(entry)
        }

        return fixos + normais
    }

    fun extractSlugAndSeason(url: String): Pair<String, String> {
        if (url.contains("/anime/")) {
            val after = url.substringAfter("/anime/").trimStart('/')
            val parts = after.split('/')
            val slug = parts.getOrNull(0)?.substringBefore(".html").orEmpty()
            val season = parts.getOrNull(1)?.substringBefore(".html")?.takeIf(String::isNotBlank) ?: "tv-1"
            return Pair(slug, season)
        }
        val segments = url.toHttpUrlOrNull()?.pathSegments.orEmpty().filter(String::isNotBlank)
        val slug = segments.firstOrNull()?.substringBefore(".html").orEmpty()
        return Pair(slug, "tv-1")
    }

    fun getSlugVariants(slug: String): List<String> {
        val parts = slug.split('-')
        val titleWithParticles = parts.joinToString("-") { part ->
            val lower = part.lowercase()
            if (lower in ROMAJI_PARTICLES) lower else lower.replaceFirstChar(Char::titlecase)
        }
        val capitalizedAll = parts.joinToString("-") { it.lowercase().replaceFirstChar(Char::titlecase) }
        return listOf(
            slug,
            titleWithParticles,
            capitalizedAll,
            slug.lowercase(),
            slug.uppercase(),
        ).distinct()
    }

    fun buildMenuCandidates(
        slug: String,
        season: String,
        audio: String,
        originalUrl: String,
        baseUrl: String = "https://j-s-an.github.io",
    ): List<String> {
        val candidates = mutableListOf<String>()

        if (originalUrl.contains("/menu.html") && originalUrl.contains("/$audio/")) {
            candidates.add(originalUrl)
        } else if (originalUrl.contains("/episodio-") && originalUrl.contains("/$audio/")) {
            candidates.add(originalUrl.substringBeforeLast("/") + "/menu.html")
        }

        val slugVariants = getSlugVariants(slug)
        val seasonVariants = listOf(season.lowercase(), season, "tv-1")
        val audioVariants = listOf(audio.lowercase())

        for (s in slugVariants) {
            for (sea in seasonVariants) {
                for (a in audioVariants) {
                    candidates.add("$baseUrl/anime/$s/$sea/$a/menu.html")
                }
            }
        }
        return candidates.distinct()
    }

    fun extractEpisodeNumber(title: String, href: String): Float {
        val fromHref = Regex("""episodio-(\d+(?:\.\d+)?)""").find(href)?.groupValues?.get(1)?.toFloatOrNull()
        if (fromHref != null) return fromHref
        val fromTitle = Regex("""(?:episódio|episodio|ep\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .find(title)?.groupValues?.get(1)?.toFloatOrNull()
        if (fromTitle != null) return fromTitle
        val anyNumber = Regex("""\b(\d+(?:\.\d+)?)\b""").find(title)?.groupValues?.get(1)?.toFloatOrNull()
        return anyNumber ?: 1f
    }

    fun cleanEpisodeTitle(rawTitle: String, epNum: Float): String {
        val stripped = rawTitle
            .replace(Regex("""(?i)\s*[\(\[\-–—]?\s*(?:dublado|legendado)\s*[\)\]]?\s*$"""), "")
            .trim()
        val numStr = if (epNum % 1f == 0f) epNum.toInt().toString() else epNum.toString()
        return if (stripped.contains("episódio", ignoreCase = true) || stripped.contains("episodio", ignoreCase = true)) {
            stripped
        } else if (stripped.isNotBlank() && !stripped.matches(Regex("""^\d+(\.\d+)?$"""))) {
            "Episódio $numStr - $stripped"
        } else {
            "Episódio $numStr"
        }
    }

    fun getCanonicalAnimeUrl(url: String, baseUrl: String = "https://j-s-an.github.io"): String {
        val (slug, season) = extractSlugAndSeason(url)
        return if (slug.isNotBlank()) {
            val cleanSeason = if (season.isNotBlank()) season else "tv-1"
            "$baseUrl/anime/$slug/$cleanSeason"
        } else {
            url.substringBefore("/legendado").substringBefore("/dublado").substringBefore("/episodio")
        }
    }

    fun findInCatalog(
        link: String,
        title: String,
        catalog: List<CatalogEntry>,
    ): CatalogEntry? {
        // 0. Exact URL match
        if (link.isNotBlank()) {
            catalog.firstOrNull { it.link.equals(link, ignoreCase = true) }?.let { return it }
        }

        // 1. Slug and Season match, fallback to slug alone
        val (slug, season) = extractSlugAndSeason(link)
        if (slug.isNotBlank()) {
            // Slug + Season exact
            catalog.firstOrNull { entry ->
                val (entrySlug, entrySeason) = extractSlugAndSeason(entry.link)
                entrySlug.equals(slug, ignoreCase = true) &&
                    (entrySeason.equals(season, ignoreCase = true) || season.isBlank())
            }?.let { return it }

            // Slug alone
            catalog.firstOrNull { entry ->
                val (entrySlug, _) = extractSlugAndSeason(entry.link)
                entrySlug.equals(slug, ignoreCase = true)
            }?.let { return it }
        }

        // 2. Normalized name matching
        if (title.isNotBlank()) {
            val normTitle = normalize(title)
            val cleanTitle = normalize(title.replace(Regex("""(?i)\s*epis[oó]dio.*$"""), "").trim())

            catalog.firstOrNull {
                val n = normalize(it.name)
                n == normTitle || (cleanTitle.isNotBlank() && n == cleanTitle)
            }?.let { return it }

            // 3. Normalized altName fallback
            catalog.firstOrNull {
                if (it.altName.isBlank()) return@firstOrNull false
                val a = normalize(it.altName)
                a == normTitle || (cleanTitle.isNotBlank() && a == cleanTitle)
            }?.let { return it }
        }

        return null
    }

    fun resolveLancamentosToCards(
        entries: List<LancamentoEntry>,
        catalog: List<CatalogEntry>,
        baseUrl: String = "https://j-s-an.github.io",
    ): List<AnimeCard> {
        val seenUrls = mutableSetOf<String>()
        val cards = mutableListOf<AnimeCard>()

        for (entry in entries) {
            val matchedCatalog = findInCatalog(entry.link, entry.title, catalog)
            val canonicalUrl = matchedCatalog?.link
                ?: getCanonicalAnimeUrl(entry.link, baseUrl)
            if (canonicalUrl.isBlank() || !seenUrls.add(canonicalUrl.lowercase())) continue

            val card = if (matchedCatalog != null) {
                AnimeCard(
                    title = matchedCatalog.name,
                    cover = matchedCatalog.cover,
                    url = matchedCatalog.link,
                )
            } else {
                AnimeCard(
                    title = entry.title,
                    cover = "",
                    url = canonicalUrl,
                )
            }
            cards.add(card)
        }

        return cards
    }

    fun normalize(str: String): String = Normalizer.normalize(str, Normalizer.Form.NFD)
        .replace("\\p{M}".toRegex(), "")
        .lowercase(Locale.ROOT)
        .trim()
}
