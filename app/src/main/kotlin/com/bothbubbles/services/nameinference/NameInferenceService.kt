package com.bothbubbles.services.nameinference

import timber.log.Timber
import com.bothbubbles.data.local.db.dao.HandleDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service responsible for inferring contact names from self-introduction patterns
 * in incoming messages (e.g., "Hey it's John", "Hi this is Sarah").
 *
 * When a name is inferred, it will be displayed as "Maybe: [Name]" in the conversation
 * list until the user saves the contact.
 */
@Singleton
class NameInferenceService @Inject constructor(
    private val handleDao: HandleDao
) {
    companion object {
        // Case-insensitive name pattern - captures letters, apostrophes, hyphens
        // Supports: John, O'Brien, Mary-Kate, John Smith, De Marco
        private const val NAME_PATTERN = """([a-zA-Z][a-zA-Z''\-]*(?:\s+(?:[dD]e\s+|[vV]an\s+|[vV]on\s+|[oO]'|[mM]c|[mM]ac)?[a-zA-Z][a-zA-Z''\-]*)?)"""

        private val INTRODUCTION_PATTERNS = listOf(
            // "Hey it's John" / "Hi it's Sarah" / "Yo its Mike"
            Regex("""^(?:hey|hi|hello|yo|sup|hiya)?[,!]?\s*(?:it'?s|its)\s+$NAME_PATTERN""", RegexOption.IGNORE_CASE),
            // "This is Mike" / "this is Sarah"
            Regex("""^(?:hey|hi|hello)?[,!]?\s*this\s+is\s+$NAME_PATTERN""", RegexOption.IGNORE_CASE),
            // "I'm Jessica" / "I am David" / "im john"
            Regex("""^(?:hey|hi|hello)?[,!]?\s*(?:i'?m|i\s+am)\s+$NAME_PATTERN""", RegexOption.IGNORE_CASE),
            // "This is Mike from work" / "it's Sarah from the gym"
            Regex("""^(?:hey|hi)?[,!]?\s*(?:this\s+is|it'?s)\s+$NAME_PATTERN\s+(?:from|at|with)\s+""", RegexOption.IGNORE_CASE),
            // "John here" / "sarah here!"
            Regex("""^$NAME_PATTERN\s+here[!.]?(?:\s|$)""", RegexOption.IGNORE_CASE),
            // "My name is John" / "my name's Sarah"
            Regex("""my\s+name(?:'?s|\s+is)\s+$NAME_PATTERN""", RegexOption.IGNORE_CASE),
            // "It's me, John" / "its me john"
            Regex("""^(?:hey|hi)?[,!]?\s*it'?s\s+me[,!]?\s+$NAME_PATTERN""", RegexOption.IGNORE_CASE),
            // "Hey, John here" / "yo, mike here"
            Regex("""^(?:hey|hi|yo)[,!]\s+$NAME_PATTERN\s+here""", RegexOption.IGNORE_CASE),
            // "This is John's new number" / "this is sarah's phone"
            Regex("""^this\s+is\s+$NAME_PATTERN(?:'?s)?\s+(?:new\s+)?(?:number|phone|cell)""", RegexOption.IGNORE_CASE),
            // "You reached John" / "you've reached Sarah"
            Regex("""^you(?:'ve)?\s+(?:reached|got|texted)\s+$NAME_PATTERN""", RegexOption.IGNORE_CASE),
            // "John speaking" / "Sarah here speaking"
            Regex("""^$NAME_PATTERN\s+(?:here\s+)?speaking""", RegexOption.IGNORE_CASE),
            // "It's your boy John" / "its ya girl sarah" (casual)
            Regex("""^it'?s\s+(?:yo(?:ur)?|ya)\s+(?:boy|girl|man|dude|pal)\s+$NAME_PATTERN""", RegexOption.IGNORE_CASE),
        )

        private val EXCLUSION_PATTERNS = listOf(
            // Weather/status - "it's raining", "it's cold"
            Regex("""^it'?s\s+(?:raining|snowing|cold|hot|warm|sunny|cloudy|nice|great|fine|okay|ok|good|bad|late|early|time|about|been|going|getting|over|done|true|false|not|so|too|very|a\s|the\s)""", RegexOption.IGNORE_CASE),
            // URLs
            Regex("""https?://""", RegexOption.IGNORE_CASE),
            // Questions ending with ?
            Regex("""\?$"""),
        )

        private val INVALID_NAMES = setOf(
            "me", "my", "it", "this", "that", "here", "there", "just", "only",
            "nice", "great", "good", "bad", "fine", "cool", "thanks", "sorry",
            "hey", "hi", "hello", "yo", "sup", "yeah", "yep", "nope", "yes", "no",
            "someone", "anyone", "everyone", "nobody", "something", "nothing",
            "mom", "dad", "bro", "sis", "dude", "man", "girl", "boy", "friend"
        )
    }

    /**
     * Attempts to extract a name from a self-introduction message.
     * Returns the properly capitalized name or null if no valid name is found.
     */
    fun extractNameFromMessage(messageText: String?): String? {
        if (messageText.isNullOrBlank() || messageText.length < 5 || messageText.length > 200) return null

        val trimmed = messageText.trim()

        // Check exclusion patterns
        if (EXCLUSION_PATTERNS.any { it.containsMatchIn(trimmed) }) return null

        // Try each introduction pattern
        for (pattern in INTRODUCTION_PATTERNS) {
            pattern.find(trimmed)?.let { match ->
                val rawName = match.groupValues.getOrNull(1)?.trim() ?: return@let
                if (isValidName(rawName)) {
                    return capitalizeName(rawName)
                }
            }
        }
        return null
    }

    /**
     * Capitalize name properly: "john smith" -> "John Smith", "o'brien" -> "O'Brien"
     */
    private fun capitalizeName(name: String): String {
        return name.split(" ").joinToString(" ") { part ->
            // Handle prefixes like O'Brien, McDonald, etc.
            when {
                part.contains("'") -> {
                    part.split("'").joinToString("'") {
                        it.lowercase().replaceFirstChar { c -> c.uppercase() }
                    }
                }
                part.lowercase().startsWith("mc") && part.length > 2 -> {
                    "Mc" + part.substring(2).lowercase().replaceFirstChar { it.uppercase() }
                }
                part.lowercase().startsWith("mac") && part.length > 3 -> {
                    "Mac" + part.substring(3).lowercase().replaceFirstChar { it.uppercase() }
                }
                else -> {
                    part.lowercase().replaceFirstChar { it.uppercase() }
                }
            }
        }
    }

    private fun isValidName(name: String): Boolean {
        val lower = name.lowercase()
        return name.length in 2..30 &&
               lower !in INVALID_NAMES &&
               name.first().isLetter() &&
               !name.contains(Regex("[0-9]"))
    }

    /**
     * Process an incoming message for name inference.
     * Only infers name if: no saved contact AND no existing inference (first wins).
     */
    suspend fun processIncomingMessage(handleId: Long, messageText: String?) {
        val handle = handleDao.getHandleById(handleId) ?: return

        // Don't override saved contact names or existing inference (first inference wins)
        if (handle.cachedDisplayName != null || handle.inferredName != null) return

        extractNameFromMessage(messageText)?.let { name ->
            Timber.i("Inferred name '$name' for handle ${handle.address}")
            handleDao.updateInferredName(handleId, name)
        }
    }
}
