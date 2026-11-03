package dev.smartrequest.http

enum class CurlFormat(val label: String) {
    LINUX("Linux / macOS"),
    POWERSHELL("PowerShell")
}

/**
 * Serializes a fully prepared request into a curl command. The request is already resolved
 * (environment variables, path parameters, globals, content type), so the copied command should
 * reproduce the same HTTP call outside the IDE.
 */
object CurlCommandBuilder {

    fun build(request: PreparedRequest, format: CurlFormat): String {
        val executable = if (format == CurlFormat.POWERSHELL) "curl.exe" else "curl"
        val lines = ArrayList<String>()
        if (request.method.equals("HEAD", ignoreCase = true)) {
            lines += "$executable --head"
        } else {
            lines += "$executable --request ${request.method.uppercase()}"
        }
        if (request.followRedirects) lines += "--location"
        lines += quote(request.url, format)
        request.headers.forEach { (name, value) ->
            lines += "--header ${quote("$name: $value", format)}"
        }
        if (request.body != null && !request.method.equals("HEAD", ignoreCase = true)) {
            lines += "--data-raw ${quote(request.body, format)}"
        }

        val separator = if (format == CurlFormat.POWERSHELL) " `\n  " else " \\\n  "
        return if (lines.size <= 2) {
            lines.joinToString(" ")
        } else {
            lines.joinToString(separator)
        }
    }

    private fun quote(value: String, format: CurlFormat): String = when (format) {
        CurlFormat.LINUX -> "'" + value.replace("'", "'\\''") + "'"
        CurlFormat.POWERSHELL -> "'" + value.replace("'", "''") + "'"
    }
}
