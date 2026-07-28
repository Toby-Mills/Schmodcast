package com.schmodcast.data.remote

import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class RssItem(
    val guid: String,
    val title: String,
    val pubDate: String?,
    val audioUrl: String,
    val durationSeconds: Long?,
)

// A deliberately small, non-namespace-aware RSS 2.0 reader: it only looks at the
// handful of <item> child tags a podcast feed actually needs (title, pubDate, guid,
// enclosure, itunes:duration) and ignores everything else, including malformed feeds
// that would trip up a stricter parser.
object RssFeedParser {
    fun parse(input: InputStream): List<RssItem> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        parser.setInput(input, null)

        val items = mutableListOf<RssItem>()
        var currentTag: String? = null
        var inItem = false
        var title: String? = null
        var pubDate: String? = null
        var guid: String? = null
        var audioUrl: String? = null
        var durationSeconds: Long? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (currentTag) {
                        "item" -> {
                            inItem = true
                            title = null
                            pubDate = null
                            guid = null
                            audioUrl = null
                            durationSeconds = null
                        }
                        "enclosure" -> if (inItem) {
                            audioUrl = parser.getAttributeValue(null, "url")
                        }
                    }
                }

                XmlPullParser.TEXT -> if (inItem) {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        when (currentTag) {
                            "title" -> title = text
                            "pubDate" -> pubDate = text
                            "guid" -> guid = text
                            "itunes:duration" -> durationSeconds = parseDurationToSeconds(text)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "item" && inItem) {
                        inItem = false
                        val url = audioUrl
                        val itemTitle = title
                        if (url != null && itemTitle != null) {
                            items += RssItem(guid ?: url, itemTitle, pubDate, url, durationSeconds)
                        }
                    }
                    currentTag = null
                }
            }
            eventType = parser.next()
        }
        return items
    }

    private fun parseDurationToSeconds(raw: String): Long? {
        val parts = raw.split(":").mapNotNull { it.trim().toLongOrNull() }
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0L) { acc, part -> acc * 60 + part }
    }
}

private val PUB_DATE_FORMAT = DateTimeFormatter.RFC_1123_DATE_TIME

// RFC_1123_DATE_TIME only understands numeric zone offsets (and GMT/UT) - but plenty of
// feeds (WordPress/PowerPress-generated ones especially) emit US zone abbreviations like
// "PDT" instead, which otherwise silently fail to parse and drop every episode in the feed.
private val ZONE_ABBREVIATIONS = mapOf(
    "PST" to "-0800", "PDT" to "-0700",
    "MST" to "-0700", "MDT" to "-0600",
    "CST" to "-0600", "CDT" to "-0500",
    "EST" to "-0500", "EDT" to "-0400",
)

fun parsePubDate(raw: String?): java.time.Instant? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim()
    val normalized = ZONE_ABBREVIATIONS.entries
        .firstOrNull { (abbr, _) -> trimmed.endsWith(abbr) }
        ?.let { (abbr, offset) -> trimmed.removeSuffix(abbr) + offset }
        ?: trimmed
    return runCatching { ZonedDateTime.parse(normalized, PUB_DATE_FORMAT).toInstant() }.getOrNull()
}
