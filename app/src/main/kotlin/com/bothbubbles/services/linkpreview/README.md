# Link Preview Service

## Purpose

Generate rich link previews for URLs in messages. Fetches Open Graph metadata, oEmbed data, and thumbnails.

## Files

| File | Description |
|------|-------------|
| `LinkPreviewModels.kt` | Data models for link previews |
| `LinkPreviewService.kt` | Main service orchestrating preview generation |
| `MapsPreviewHandler.kt` | Special handling for Google Maps/Apple Maps links |
| `OEmbedProvider.kt` | Fetch oEmbed data for supported sites |
| `OpenGraphParser.kt` | Parse Open Graph meta tags from HTML |
| `UrlResolver.kt` | Resolve shortened URLs and redirects |

## Architecture

```
Link Preview Flow:

URL → UrlResolver (follow redirects)
    → Check for special handlers (Maps, etc.)
    → Fetch HTML content
    → OpenGraphParser (extract og: meta tags)
    → OEmbedProvider (fallback for supported sites)
    → Generate thumbnail
    → Cache preview in database
```

## Required Patterns

### Preview Generation

```kotlin
class LinkPreviewService @Inject constructor(
    private val urlResolver: UrlResolver,
    private val openGraphParser: OpenGraphParser,
    private val oEmbedProvider: OEmbedProvider,
    private val mapsHandler: MapsPreviewHandler,
    private val linkPreviewDao: LinkPreviewDao
) {
    suspend fun generatePreview(url: String): LinkPreview? {
        // Check cache
        val cached = linkPreviewDao.getByUrl(url)
        if (cached != null) return cached.toLinkPreview()

        // Resolve final URL
        val resolvedUrl = urlResolver.resolve(url)

        // Check for special handlers
        mapsHandler.handle(resolvedUrl)?.let { return it }

        // Fetch and parse
        val html = fetchHtml(resolvedUrl)
        val preview = openGraphParser.parse(html, resolvedUrl)
            ?: oEmbedProvider.fetch(resolvedUrl)

        // Cache result
        preview?.let { linkPreviewDao.upsert(it.toEntity()) }

        return preview
    }
}
```

### Open Graph Parsing

```kotlin
class OpenGraphParser {
    fun parse(html: String, url: String): LinkPreview? {
        val doc = Jsoup.parse(html)
        return LinkPreview(
            url = url,
            title = doc.selectFirst("meta[property=og:title]")?.attr("content"),
            description = doc.selectFirst("meta[property=og:description]")?.attr("content"),
            imageUrl = doc.selectFirst("meta[property=og:image]")?.attr("content"),
            siteName = doc.selectFirst("meta[property=og:site_name]")?.attr("content")
        )
    }
}
```

## Best Practices

1. Cache previews to avoid repeated fetches
2. Handle redirects (shortened URLs)
3. Set timeouts for slow sites
4. Support special handlers for common services
5. Respect robots.txt and rate limits
