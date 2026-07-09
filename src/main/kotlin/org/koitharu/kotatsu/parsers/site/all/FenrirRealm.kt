package org.koitharu.kotatsu.parsers.site.all

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import org.koitharu.kotatsu.parsers.util.json.toStringSet
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("FENRIRREALM", "Fenrir Realm", locale = "en", type = ContentType.NOVEL)
internal class FenrirRealmParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.FENRIRREALM, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("fenrirealm.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	private val genres = suspendLazy(soft = true) {
		val url = urlBuilder()
			.addPathSegment("api")
			.addPathSegment("new")
			.addPathSegment("v2")
			.addPathSegment("series")
			.addQueryParameter("per_page", "1")
			.build()
		val json = webClient.httpGet(url).parseJson()
		val typesJson = json.getJSONArray("data").getJSONObject(0).getJSONArray("genres")
		typesJson.mapJSONToSet { tagJson ->
			MangaTag(
				title = tagJson.getString("name"),
				key = tagJson.getString("slug"),
				source = source,
			)
		}
	}

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = genres.get(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder()
			.addPathSegment("api")
			.addPathSegment("new")
			.addPathSegment("v2")
			.addPathSegment("series")
			.addQueryParameter("page", page.toString())
			.addQueryParameter("per_page", pageSize.toString())

		if (!filter.query.isNullOrEmpty()) {
			url.addQueryParameter("search", filter.query.urlEncoded())
		}

		url.addQueryParameter("sort", when (order) {
			SortOrder.POPULARITY -> "popular"
			SortOrder.RATING -> "rating"
			SortOrder.NEWEST -> "latest"
			SortOrder.ALPHABETICAL -> "alphabetical"
			SortOrder.RELEVANCE -> "updated"
			else -> "updated"
		})

		val json = webClient.httpGet(url.build()).parseJson()
		return json.getJSONArray("data").mapJSON { jo ->
			jo.toManga()
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast('/')
		val url = urlBuilder()
			.addPathSegment("api")
			.addPathSegment("new")
			.addPathSegment("v2")
			.addPathSegment("series")
			.addPathSegment(slug)
			.build()
		val json = webClient.httpGet(url).parseJson()

		val tags = json.getJSONArray("genres").mapJSONToSet { tagJson ->
			MangaTag(
				title = tagJson.getString("name"),
				key = tagJson.getString("slug"),
				source = source,
			)
		}

		val author = json.getJSONObject("user").getStringOrNull("name")
		val description = json.getStringOrNull("description")
		val cover = json.getStringOrNull("cover")
		val altTitle = json.getStringOrNull("alt_title")
		val type = json.optString("type")

		val chapters = getChapters(slug)

		return manga.copy(
			title = json.getString("title"),
			altTitles = setOfNotNull(altTitle),
			description = description,
			rating = RATING_UNKNOWN,
			coverUrl = cover?.toAbsoluteUrl(domain),
			largeCoverUrl = null,
			tags = tags,
			authors = setOfNotNull(author),
			contentRating = when {
				type == "mature" -> ContentRating.ADULT
				else -> null
			},
			state = when (json.optString("status")) {
				"Ongoing" -> MangaState.ONGOING
				"Completed" -> MangaState.FINISHED
				else -> null
			},
			chapters = chapters,
		)
	}

	private suspend fun getChapters(slug: String): List<MangaChapter> {
		val url = urlBuilder()
			.addPathSegment("api")
			.addPathSegment("new")
			.addPathSegment("v2")
			.addPathSegment("series")
			.addPathSegment(slug)
			.addPathSegment("chapters")
			.build()
		val jsonArray = webClient.httpGet(url).parseJsonArray()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

		return jsonArray.mapJSON { ch ->
			val chapterSlug = ch.getString("slug")
			val chapterUrl = "/series/$slug/$chapterSlug"
			MangaChapter(
				id = generateUid(chapterUrl),
				title = ch.optString("title").nullIfEmpty() ?: ch.optString("name").nullIfEmpty(),
				number = ch.getDouble("number").toFloat(),
				volume = 0,
				url = chapterUrl,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(ch.getString("created_at")),
				branch = null,
				source = source,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val content = doc.selectFirst("#reader-area")?.html() ?: return emptyList()
		// ponytail: single page per chapter; split into multiple pages if site ever paginates
		return listOf(
			MangaPage(
				id = generateUid(chapter.url),
				url = chapter.url,
				preview = null,
				source = source,
			)
		)
	}

	private fun JSONObject.toManga(): Manga {
		val slug = getString("slug")
		val relativeUrl = "/series/$slug"
		val cover = getStringOrNull("cover")
		val altTitle = getStringOrNull("alt_title")

		return Manga(
			id = generateUid(relativeUrl),
			title = getString("title"),
			altTitles = setOfNotNull(altTitle),
			url = relativeUrl,
			publicUrl = relativeUrl.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = cover?.toAbsoluteUrl(domain),
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			largeCoverUrl = null,
			description = null,
			chapters = null,
			source = source,
		)
	}
}
