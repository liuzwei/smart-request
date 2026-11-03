package dev.smartrequest.spring

object SpringPathUtils {

    fun joinPaths(classPath: String?, methodPath: String?): String {
        val parts = listOfNotNull(classPath, methodPath)
            .flatMap { it.split('/') }
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return "/"
        return "/" + parts.joinToString("/")
    }

    /**
     * Spring allows `{id:\\d+}` and even nested regex quantifiers such as `{id:[0-9]{1,3}}`.
     * A request editor needs a normal path variable, so everything after the first `:` is removed
     * while the variable name is kept: `{id}`.
     */
    fun normalizePath(path: String): String {
        val result = StringBuilder(path.length)
        var index = 0
        while (index < path.length) {
            val c = path[index]
            if (c != '{') {
                result.append(c)
                index++
                continue
            }
            val end = findMatchingBrace(path, index)
            if (end < 0) {
                result.append(path.substring(index))
                break
            }
            val expression = path.substring(index + 1, end)
            val name = expression.substringBefore(':').trim()
            if (name.isNotEmpty()) {
                result.append('{').append(name).append('}')
            } else {
                result.append(path, index, end + 1)
            }
            index = end + 1
        }

        val collapsed = result.toString().replace(Regex("/{2,}"), "/")
        return if (collapsed.isBlank()) "" else "/" + collapsed.trim('/')
    }

    private fun findMatchingBrace(value: String, start: Int): Int {
        var depth = 0
        var index = start
        while (index < value.length) {
            when (value[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return -1
    }
}
