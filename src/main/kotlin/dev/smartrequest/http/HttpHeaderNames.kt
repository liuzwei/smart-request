package dev.smartrequest.http

/**
 * Common HTTP header names offered by the key-column chooser of header tables. The list is only a
 * suggestion: any other header name can still be typed, and values are always free text.
 */
object HttpHeaderNames {
    val COMMON: List<String> = listOf(
        "Content-Type",
        "Content-Length",
        "Accept",
        "Accept-Encoding",
        "Accept-Language",
        "Authorization",
        "Cache-Control",
        "Cookie",
        "Connection",
        "Content-MD5",
        "Date",
        "Expect",
        "Forwarded",
        "From",
        "Host",
        "If-Match",
        "If-Modified-Since",
        "If-None-Match",
        "If-Range",
        "If-Unmodified-Since",
        "Max-Forwards",
        "Origin",
        "Pragma",
        "Proxy-Authorization",
        "Range",
        "Referer",
        "TE",
        "User-Agent",
        "Upgrade",
        "Via",
        "Warning"
    )
}
