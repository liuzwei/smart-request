package dev.smartrequest.spring

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.search.GlobalSearchScope
import dev.smartrequest.model.KeyValue
import dev.smartrequest.util.JsonPrettyPrinter

/**
 * Builds a JSON request-body skeleton from a `@RequestBody` DTO or from query parameter values.
 * The result is intentionally a template: values default to type-safe placeholders, while Swagger
 * `example` / `defaultValue` annotations are used when present.
 */
object SpringRequestBodyGenerator {
    private const val JSON_PROPERTY = "com.fasterxml.jackson.annotation.JsonProperty"
    private const val JSON_IGNORE = "com.fasterxml.jackson.annotation.JsonIgnore"
    private const val SCHEMA = "io.swagger.v3.oas.annotations.media.Schema"
    private const val API_MODEL_PROPERTY = "io.swagger.annotations.ApiModelProperty"
    private const val MAX_DEPTH = 4
    private const val MAX_FIELDS = 30

    fun forParameter(project: Project, parameter: PsiParameter): String {
        val schemaClass = schemaImplementationClass(project, parameter)
        val type = unwrapGeneric(parameter.type)
        val psiClass = schemaClass ?: (type as? PsiClassType)?.resolve()
        if (psiClass == null) return "{}"
        if (psiClass.qualifiedName == "java.lang.String") return "\"\""
        return fromClass(project, psiClass)
    }

    fun fromClass(project: Project, psiClass: PsiClass): String {
        val compact = classJson(project, psiClass, 0, LinkedHashSet())
        return JsonPrettyPrinter.prettyPrintOrNull(compact) ?: compact
    }

    fun fromParams(params: List<KeyValue>): String {
        val entries = LinkedHashMap<String, String>()
        params.filter { it.enabled && it.key.isNotBlank() }.forEach { parameter ->
            entries[parameter.key.trim()] = scalarJson(parameter.value)
        }
        if (entries.isEmpty()) return "{}"
        val compact = entries.entries.joinToString(",", "{", "}") { (key, value) ->
            "${quote(key)}:$value"
        }
        return JsonPrettyPrinter.prettyPrintOrNull(compact) ?: compact
    }

    private fun classJson(
        project: Project,
        psiClass: PsiClass,
        depth: Int,
        visited: MutableSet<String>
    ): String {
        val className = psiClass.qualifiedName ?: psiClass.name
        if (depth > MAX_DEPTH || className == null || !visited.add(className)) return "{}"
        try {
            val entries = LinkedHashMap<String, String>()
            psiClass.allFields.forEach { field ->
                if (entries.size >= MAX_FIELDS) return@forEach
                if (field.hasModifierProperty(PsiModifier.STATIC) ||
                    field.hasModifierProperty(PsiModifier.TRANSIENT) ||
                    field.name == "serialVersionUID" ||
                    field.hasAnnotation(JSON_IGNORE)
                ) {
                    return@forEach
                }
                val name = jsonName(project, field) ?: field.name
                if (name.isBlank() || entries.containsKey(name)) return@forEach
                entries[name] = fieldValue(project, field, depth + 1, visited)
            }
            if (entries.isEmpty()) return "{}"
            return entries.entries.joinToString(",", "{", "}") { (key, value) ->
                "${quote(key)}:$value"
            }
        } finally {
            visited.remove(className)
        }
    }

    private fun fieldValue(
        project: Project,
        field: PsiField,
        depth: Int,
        visited: MutableSet<String>
    ): String {
        val example = swaggerExample(project, field)
        if (!example.isNullOrBlank()) return exampleValue(field.type, example)
        return typeJson(project, field.type, depth, visited)
    }

    private fun typeJson(
        project: Project,
        type: PsiType,
        depth: Int,
        visited: MutableSet<String>
    ): String {
        if (type is PsiPrimitiveType) {
            return when (type.canonicalText) {
                "boolean" -> "false"
                "char" -> "\"\""
                else -> "0"
            }
        }
        if (type is PsiArrayType) return "[]"
        if (type is PsiWildcardType) {
            return typeJson(project, type.extendsBound, depth, visited)
        }
        if (type is PsiClassType) {
            val raw = type.resolve()
            val qualified = raw?.qualifiedName
            val canonical = type.canonicalText
            if (qualified in COLLECTION_TYPES || canonical in COLLECTION_NAMES) return "[]"
            if (qualified in MAP_TYPES || canonical in MAP_NAMES) return "{}"
            if (qualified in OPTIONAL_TYPES) {
                return type.parameters.firstOrNull()?.let { typeJson(project, it, depth, visited) } ?: "{}"
            }
            if (qualified in STRING_TYPES || canonical in STRING_NAMES) return "\"\""
            if (qualified in BOOLEAN_TYPES || canonical in BOOLEAN_NAMES) return "false"
            if (qualified in NUMBER_TYPES || canonical in NUMBER_NAMES) return "0"
            if (qualified in DATE_TYPES || canonical in DATE_NAMES) return "\"2026-01-01T00:00:00\""
            if (raw != null && raw.isEnum) {
                return quote(raw.fields.firstOrNull()?.name ?: "")
            }
            if (raw != null) return classJson(project, raw, depth, visited)
            val rawName = canonical.substringBefore('<')
            return when {
                rawName in STRING_NAMES || rawName.endsWith("String") -> "\"\""
                rawName in BOOLEAN_NAMES || rawName.endsWith("Boolean") -> "false"
                rawName in NUMBER_NAMES || rawName.endsWith("Integer") || rawName.endsWith("Long") -> "0"
                rawName in COLLECTION_NAMES || rawName.endsWith("List") ||
                        rawName.endsWith("Set") || rawName.endsWith("Collection") -> "[]"
                rawName in MAP_NAMES || rawName.endsWith("Map") -> "{}"
                rawName in DATE_NAMES || rawName.endsWith("Date") -> "\"2026-01-01T00:00:00\""
                else -> "{}"
            }
        }
        return "{}"
    }

    private fun exampleValue(type: PsiType, example: String): String {
        val trimmed = example.trim()
        if ((trimmed.startsWith("{") || trimmed.startsWith("[")) &&
            JsonPrettyPrinter.prettyPrintOrNull(trimmed) != null
        ) {
            return trimmed
        }
        val canonical = type.canonicalText
        if (canonical == "boolean" || canonical == "java.lang.Boolean" || canonical == "Boolean") {
            if (trimmed == "true" || trimmed == "false") return trimmed
        }
        if (canonical in NUMBER_TYPES || canonical in NUMBER_NAMES) {
            if (trimmed.matches(Regex("-?\\d+(\\.\\d+)?"))) return trimmed
        }
        return quote(example)
    }

    private fun scalarJson(value: String): String {
        val trimmed = value.trim()
        if (trimmed == "true" || trimmed == "false") return trimmed
        if (trimmed.matches(Regex("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?"))) return trimmed
        if ((trimmed.startsWith("{") || trimmed.startsWith("[")) &&
            JsonPrettyPrinter.prettyPrintOrNull(trimmed) != null
        ) {
            return trimmed
        }
        return quote(value)
    }

    private fun jsonName(project: Project, field: PsiField): String? {
        field.annotations.forEach { annotation ->
            when (annotation.qualifiedName) {
                JSON_PROPERTY -> stringAttribute(project, annotation, "value")?.takeIf { it.isNotBlank() }
                    ?.let { return it }
                SCHEMA -> stringAttribute(project, annotation, "name")?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun swaggerExample(project: Project, field: PsiField): String? {
        field.annotations.forEach { annotation ->
            when (annotation.qualifiedName) {
                SCHEMA -> {
                    stringAttribute(project, annotation, "example")?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                    stringAttribute(project, annotation, "defaultValue")?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                }
                API_MODEL_PROPERTY -> {
                    stringAttribute(project, annotation, "example")?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                }
            }
        }
        return null
    }

    private fun schemaImplementationClass(project: Project, parameter: PsiParameter): PsiClass? {
        val schema = parameter.annotations.firstOrNull { it.qualifiedName == SCHEMA } ?: return null
        val value = schema.findAttributeValue("implementation")?.text ?: return null
        val className = value.removeSuffix(".class").trim()
        if (className.isBlank()) return null
        return JavaPsiFacade.getInstance(project).findClass(className, GlobalSearchScope.allScope(project))
    }

    private fun unwrapGeneric(type: PsiType): PsiType {
        if (type is PsiClassType) {
            val qualified = type.resolve()?.qualifiedName
            if (qualified in GENERIC_WRAPPERS) {
                return type.parameters.firstOrNull() ?: type
            }
        }
        return type
    }

    private fun stringAttribute(project: Project, annotation: PsiAnnotation, name: String): String? {
        val member = annotation.findAttributeValue(name) ?: return null
        val evaluated = JavaPsiFacade.getInstance(project).constantEvaluationHelper
            .computeConstantExpression(member)
        if (evaluated is String) return evaluated
        val text = member.text.trim()
        if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) {
            return text.substring(1, text.length - 1)
        }
        return null
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) {
                    append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    private fun PsiField.hasAnnotation(qualifiedName: String): Boolean =
        annotations.any { it.qualifiedName == qualifiedName }

    private val COLLECTION_TYPES = setOf(
        "java.util.List",
        "java.util.ArrayList",
        "java.util.Set",
        "java.util.HashSet",
        "java.util.Collection",
        "java.lang.Iterable"
    )
    private val MAP_TYPES = setOf(
        "java.util.Map",
        "java.util.HashMap",
        "java.util.LinkedHashMap"
    )
    private val OPTIONAL_TYPES = setOf("java.util.Optional")
    private val GENERIC_WRAPPERS = setOf(
        "org.springframework.http.HttpEntity",
        "org.springframework.http.ResponseEntity",
        "java.util.Optional"
    )
    private val STRING_TYPES = setOf("java.lang.String", "java.lang.CharSequence", "java.lang.Character")
    private val BOOLEAN_TYPES = setOf("java.lang.Boolean")
    private val NUMBER_TYPES = setOf(
        "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
        "java.lang.Float", "java.lang.Double", "java.math.BigDecimal", "java.math.BigInteger"
    )
    private val DATE_TYPES = setOf(
        "java.util.Date", "java.sql.Date", "java.sql.Timestamp",
        "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime",
        "java.time.OffsetDateTime", "java.time.ZonedDateTime"
    )
    private val COLLECTION_NAMES = setOf("List", "Set", "Collection", "Iterable")
    private val MAP_NAMES = setOf("Map", "HashMap")
    private val STRING_NAMES = setOf("String", "CharSequence", "Character")
    private val BOOLEAN_NAMES = setOf("Boolean")
    private val NUMBER_NAMES = setOf(
        "byte", "short", "int", "long", "float", "double",
        "Byte", "Short", "Integer", "Long", "Float", "Double",
        "BigDecimal", "BigInteger"
    )
    private val DATE_NAMES = setOf(
        "Date", "Timestamp", "LocalDate", "LocalDateTime", "LocalTime", "OffsetDateTime", "ZonedDateTime"
    )
}
