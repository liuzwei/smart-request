package dev.smartrequest.spring

/**
 * Spring stores "no default value" for `@RequestParam(defaultValue = ...)` as a sentinel constant
 * instead of an empty string:
 *
 * ```
 * // org.springframework.web.bind.annotation.ValueConstants
 * String DEFAULT_NONE = "\n\t\t\n\t\t\n\uE000\uE001\uE002\n\t\t\t\t\n";
 * ```
 *
 * `PsiAnnotation.findAttributeValue("defaultValue")` resolves the annotation's **default**, so a
 * parameter without an explicit default yields this sentinel rather than null/empty. The three
 * private-use characters render as icon-like glyphs in some fonts, which made generated requests
 * carry an invisible value of "🔍 ✓ ■" surrounded by whitespace.
 */
object SpringRequestValues {

    /** The exact sentinel value, without the surrounding formatting whitespace. */
    private const val DEFAULT_NONE_MARKER = "\uE000\uE001\uE002"

    /**
     * True for Spring's `DEFAULT_NONE` sentinel (and for an explicitly written
     * `ValueConstants.DEFAULT_NONE` that a PSI constant evaluation might leave as text).
     */
    fun isDefaultNone(value: String): Boolean =
        value.contains("DEFAULT_NONE") || value.trim() == DEFAULT_NONE_MARKER
}
