package com.akhoonzadaholdings.school.relay

/**
 * Keeps outgoing SMS on plain GSM-7 encoding (160 chars/single SMS, 153/part when
 * concatenated) instead of falling back to UCS-2 (70/67) the moment a message
 * contains an emoji, a smart quote, or an Arabic honorific glyph like ﷺ.
 *
 * Encoding is decided per-message by whatever characters are in it — one stray
 * character anywhere in an otherwise plain-English message is enough to push the
 * WHOLE message into UCS-2, roughly tripling the part count. That's why a ~270
 * character holiday notice with a couple of emoji and a ﷺ symbol was splitting
 * into 5 parts instead of 2: it wasn't the length, it was the encoding. With
 * plain GSM-7 (153 chars/part), that same ~270 character message fits in 2 parts
 * on its own — no truncation needed to get there.
 *
 * This only cleans characters; it never shortens content. A message long enough
 * to need 3-4 parts even on GSM-7 is sent as 3-4 parts, in full — cutting content
 * to force a part count is worse than the extra part.
 *
 * This runs BEFORE SmsSender hands text to SmsManager.divideMessage(), so the
 * part count Android actually computes is already the GSM-7 one.
 */
object MessageSanitizer {

    // Common non-GSM characters that show up in real school messages, mapped to
    // GSM-7-safe equivalents. Anything not covered here just gets dropped (see
    // isGsm7Safe) rather than sent as-is and silently tripling the SMS cost.
    private val REPLACEMENTS: Map<Char, String> = mapOf(
        // Arabic honorific glyphs -> plain-text equivalents
        '\uFDFA' to " (PBUH)",   // ﷺ  (Sallallahu Alaihi Wasallam)
        '\uFDFB' to " (SWT)",    // ﷻ  (Subhanahu Wa Ta'ala)
        '\u0631' to "",          // stray Arabic letters occasionally pasted in from WhatsApp — dropped individually below anyway

        // Smart punctuation from Word/mobile keyboards
        '\u2018' to "'", '\u2019' to "'",   // ‘ ’
        '\u201C' to "\"", '\u201D' to "\"", // “ ”
        '\u2013' to "-", '\u2014' to "-",   // – —
        '\u2026' to "...",                   // …
        '\u00A0' to " ",                      // non-breaking space
        '\u2022' to "-"                        // •
    )

    /**
     * Returns a message safe for GSM-7 encoding. [wasModified] tells the caller
     * (for logging/notification text) whether anything was actually changed, so
     * an already-clean message isn't reported as "cleaned" for no reason.
     */
    data class Result(val text: String, val wasModified: Boolean)

    fun sanitize(original: String): Result {
        var changed = false

        val replaced = buildString(original.length) {
            for (ch in original) {
                val mapped = REPLACEMENTS[ch]
                when {
                    mapped != null -> {
                        append(mapped)
                        changed = true
                    }
                    isGsm7Safe(ch) -> append(ch)
                    else -> {
                        // Unknown symbol/emoji with no direct mapping — drop it rather
                        // than let it silently force UCS-2 for the entire message.
                        changed = true
                    }
                }
            }
        }
            // Collapse any double spaces left behind by dropped characters.
            .replace(Regex(" {2,}"), " ")
            .trim()

        return Result(replaced, changed)
    }

    /**
     * GSM 03.38 default alphabet, basic set only (the extended/escape set — €, [, ],
     * {, }, ~, etc. — is intentionally excluded here even though it's technically
     * still GSM-7, since each extended char costs 2 chars and it's simpler/safer to
     * just not rely on them for school notifications).
     */
    private fun isGsm7Safe(ch: Char): Boolean {
        if (ch == '\n' || ch == '\r') return true
        if (ch.code in 0x20..0x7E) return true // plain ASCII printable
        return ch in GSM7_EXTRAS
    }

    // A handful of accented Latin letters GSM-7's basic set supports directly,
    // common enough in names (Café, José-style spellings, etc.) to keep as-is
    // instead of stripping.
    private val GSM7_EXTRAS = setOf(
        'à', 'è', 'é', 'ì', 'ò', 'ù', 'Ä', 'Ö', 'Ñ', 'Ü', 'ä', 'ö', 'ñ', 'ü', 'ß', 'Å', 'å', 'Æ', 'æ', 'É'
    )
}