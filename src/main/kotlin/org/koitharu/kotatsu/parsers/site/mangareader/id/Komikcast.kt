package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("KOMIKCAST", "KomikCast", "id")
internal class Komikcast(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKCAST, pageSize = 12) {

	override val configKeyDomain = ConfigKey.Domain("v1.komikcast.fit")

	private val apiUrl = "https://be.komikcast.fit"

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.add("Origin", "https://$domain")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val host = request.url.host
		if (host == "be.komikcast.fit") {
			return chain.proceed(
				request.newBuilder()
					.header("Referer", "https://$domain/")
					.header("Origin", "https://$domain")
					.header("Accept", "application/json")
					.header("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
					.build(),
			)
		}
		if (request.header("Referer") == null) {
			return chain.proceed(
				request.newBuilder()
					.header("Referer", "https://$domain/")
					.build(),
			)
		}
		return chain.proceed(request)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.RATING,
		SortOrder.UPDATED,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = getGenres(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "$apiUrl/series".toHttpUrl().newBuilder().apply {
			addQueryParameter("includeMeta", "true")
			addQueryParameter("take", pageSize.toString())
			addQueryParameter("page", page.toString())

			val (sort, sortOrder) = when (order) {
				SortOrder.POPULARITY -> "popularity" to "desc"
				SortOrder.NEWEST -> "latest" to "desc"
				SortOrder.RATING -> "rating" to "desc"
				SortOrder.UPDATED -> "latest" to "desc"
				else -> "latest" to "desc"
			}
			addQueryParameter("sort", sort)
			addQueryParameter("sortOrder", sortOrder)

			if (!filter.query.isNullOrEmpty()) {
				addQueryParameter("filter", "title=like=\"${filter.query}\",nativeTitle=like=\"${filter.query}\"")
			}

			filter.states.forEach {
				addQueryParameter("status", when (it) {
					MangaState.ONGOING -> "ongoing"
					MangaState.FINISHED -> "completed"
					MangaState.PAUSED -> "hiatus"
					MangaState.ABANDONED -> "cancelled"
					else -> ""
				})
			}

			filter.types.forEach {
				addQueryParameter("format", when (it) {
					ContentType.MANGA -> "manga"
					ContentType.MANHWA -> "manhwa"
					ContentType.MANHUA -> "manhua"
					else -> ""
				})
			}

			filter.tags.forEach { addQueryParameter("genreIds", it.key) }
			filter.tagsExclude.forEach { addQueryParameter("genreIds", "-${it.key}") }
		}.build()

		val response = webClient.httpGet(url).parseJson()
		return response.getJSONArray("data").mapJSON { it.toManga() }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast("/")
		val detailsResponse = webClient.httpGet("$apiUrl/series/$slug").parseJson()
		val seriesItem = detailsResponse.getJSONObject("data")

		val chaptersResponse = webClient.httpGet("$apiUrl/series/$slug/chapters").parseJson()
		val chapters = chaptersResponse.getJSONArray("data").mapChapters(reversed = true) { _, jo ->
			val chapterData = jo.getJSONObject("data")
			val chapterIndex = chapterData.getFloat("index")
			val createdAt = jo.getStringOrNull("createdAt") ?: jo.getStringOrNull("updatedAt") ?: ""
			val relativeUrl = "/series/$slug/chapters/$chapterIndex"
			MangaChapter(
				id = generateUid(relativeUrl),
				title = chapterData.getStringOrNull("title"),
				number = chapterIndex,
				volume = 0,
				url = relativeUrl,
				scanlator = null,
				uploadDate = parseChapterDate(createdAt),
				branch = null,
				source = source,
			)
		}

		return seriesItem.toManga().copy(
			description = seriesItem.getJSONObject("data").getStringOrNull("synopsis"),
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val segments = chapter.url.split("/")
		val slug = segments[2]
		val chapterIndex = segments[4]

		val response = webClient.httpGet("$apiUrl/series/$slug/chapters/$chapterIndex").parseJson()
		val dataImages = response.getJSONObject("data").optJSONObject("dataImages") ?: return emptyList()

		val sortedKeys = dataImages.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
		return sortedKeys.map { key ->
			val imageUrl = dataImages.getString(key)
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun JSONObject.toManga(): Manga {
		val seriesData = getJSONObject("data")
		val slug = seriesData.getString("slug")
		val relativeUrl = "/series/$slug"
		return Manga(
			id = generateUid(relativeUrl),
			title = seriesData.getString("title"),
			altTitles = emptySet(),
			url = relativeUrl,
			publicUrl = "https://$domain$relativeUrl",
			rating = RATING_UNKNOWN,
			contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			coverUrl = seriesData.getStringOrNull("coverImage"),
			tags = seriesData.optJSONArray("genres")?.mapJSONToSet { jo ->
				val genreInfo = jo.getJSONObject("data")
				val name = genreInfo.getString("name")
				MangaTag(name, name, source)
			} ?: emptySet(),
			state = when (seriesData.getStringOrNull("status")?.lowercase()) {
				"ongoing", "on going" -> MangaState.ONGOING
				"completed", "complete" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				"cancelled", "canceled" -> MangaState.ABANDONED
				else -> null
			},
			authors = setOfNotNull(seriesData.getStringOrNull("author")),
			source = source,
		)
	}

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)

	private fun parseChapterDate(dateString: String): Long {
		if (dateString.isBlank()) return 0L
		return dateFormat.parseSafe(dateString)
	}

	private fun getGenres(): Set<MangaTag> = setOf(
		"4-Koma", "Adventure", "Cooking", "Game", "Gore", "Harem", "Historical", "Horror", "Isekai", "Josei", "Magic",
		"Martial Arts", "Mature", "Mecha", "Medical", "Military", "Music", "Mystery", "One-Shot", "Police",
		"Psychological", "Reincarnation", "Romance", "School", "School Life", "Sci-Fi", "Seinen", "Shoujo", "Shoujo Ai",
		"Action", "Comedy", "Demons", "Drama", "Ecchi", "Fantasy", "Gender Bender", "Shounen", "Shounen Ai",
		"Slice of Life", "Sports", "Super Power", "Supernatural", "Thriller", "Tragedy", "Vampire", "Webtoons", "Yuri",
	).mapTo(mutableSetOf()) { MangaTag(it, it, source) }
}
