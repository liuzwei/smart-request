package dev.smartrequest.spring

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import dev.smartrequest.http.PathParameterResolver
import dev.smartrequest.model.BodyType
import dev.smartrequest.model.HttpRequestConfig
import dev.smartrequest.model.KeyValue

data class SpringGenerationPayload(
    val groupName: String,
    val className: String,
    val requests: List<HttpRequestConfig>
)

/**
 * Converts PSI controller/method mappings into editable request templates. It fills the parts that can
 * be inferred safely (method, URL, path/query parameter names) and leaves values for the user.
 */
object SpringRequestFactory {
    private const val REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam"
    private const val REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody"
    private const val OPERATION = "io.swagger.v3.oas.annotations.Operation"
    private const val API_OPERATION = "io.swagger.annotations.ApiOperation"

    private val NON_QUERY_ANNOTATIONS = setOf(
        "org.springframework.web.bind.annotation.PathVariable",
        "org.springframework.web.bind.annotation.RequestBody",
        "org.springframework.web.bind.annotation.RequestHeader",
        "org.springframework.web.bind.annotation.RequestPart",
        "org.springframework.web.bind.annotation.RequestAttribute",
        "org.springframework.web.bind.annotation.CookieValue",
        "org.springframework.web.bind.annotation.MatrixVariable",
        "org.springframework.web.bind.annotation.ModelAttribute"
    )

    private val SIMPLE_QUERY_TYPES = setOf(
        "String",
        "Boolean",
        "Byte",
        "Short",
        "Integer",
        "Long",
        "Float",
        "Double",
        "Character",
        "BigDecimal",
        "BigInteger",
        "Date",
        "LocalDate",
        "LocalDateTime",
        "LocalTime",
        "OffsetDateTime",
        "ZonedDateTime",
        "java.lang.String",
        "java.lang.Boolean",
        "java.lang.Byte",
        "java.lang.Short",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Float",
        "java.lang.Double",
        "java.lang.Character",
        "java.math.BigDecimal",
        "java.math.BigInteger",
        "java.util.Date",
        "java.time.LocalDate",
        "java.time.LocalDateTime",
        "java.time.LocalTime",
        "java.time.OffsetDateTime",
        "java.time.ZonedDateTime"
    )

    fun prepareClass(project: Project, psiClass: PsiClass): SpringGenerationPayload? {
        val endpoints = SpringEndpointScanner.endpointsForClass(project, psiClass)
        if (endpoints.isEmpty()) return null
        val groupName = psiClass.name ?: "Controller"
        return SpringGenerationPayload(
            groupName = groupName,
            className = psiClass.qualifiedName ?: groupName,
            requests = endpoints.map { buildRequest(project, psiClass, it) }
        )
    }

    fun prepareMethod(project: Project, psiClass: PsiClass, method: PsiMethod): SpringGenerationPayload? {
        val endpoints = SpringEndpointScanner.endpointsForMethod(project, psiClass, method)
        if (endpoints.isEmpty()) return null
        val groupName = psiClass.name ?: "Controller"
        return SpringGenerationPayload(
            groupName = groupName,
            className = psiClass.qualifiedName ?: groupName,
            requests = endpoints.map { buildRequest(project, psiClass, it) }
        )
    }

    private fun buildRequest(
        project: Project,
        psiClass: PsiClass,
        endpoint: DiscoveredEndpoint
    ): HttpRequestConfig {
        val method = psiClass.findMethodsByName(endpoint.methodName, false).firstOrNull()
        val request = HttpRequestConfig().apply {
            name = method?.let { displayName(project, it) } ?: endpoint.displayName
            this.method = endpoint.httpMethod
            url = "{{baseUrl}}${endpoint.path}"
            springKey = endpoint.springKey
            pathParams = PathParameterResolver.detectNames(endpoint.path)
                .map { KeyValue.of(it, "") }
                .toMutableList()
        }
        if (method != null) {
            request.params = queryParameters(project, method)
            val bodyParameter = method.parameterList.parameters.firstOrNull { parameter ->
                parameter.annotations.any { it.qualifiedName == REQUEST_BODY }
            }
            if (bodyParameter != null) {
                request.bodyType = BodyType.JSON.name
                request.body = SpringRequestBodyGenerator.forParameter(project, bodyParameter)
            }
        }
        return request
    }

    private fun queryParameters(project: Project, method: PsiMethod): MutableList<KeyValue> {
        val result = ArrayList<KeyValue>()
        method.parameterList.parameters.forEach { parameter ->
            val annotations = parameter.annotations.mapNotNull { it.qualifiedName }.toSet()
            if (annotations.any { it in NON_QUERY_ANNOTATIONS }) return@forEach
            val annotation = parameter.annotations.firstOrNull { it.qualifiedName == REQUEST_PARAM }
            if (annotation == null && !isSimpleQueryType(parameter)) return@forEach
            val type = parameter.type.canonicalText
            if (type.contains("MultipartFile") || type.endsWith("Map")) return@forEach
            val name = annotation?.let { stringAttribute(project, it, "name") }?.takeIf { it.isNotBlank() }
                ?: annotation?.let { stringAttribute(project, it, "value") }?.takeIf { it.isNotBlank() }
                ?: parameter.name
            if (name.isBlank()) return@forEach
            val defaultValue = annotation?.let { defaultValue(project, it) }.orEmpty()
            result += KeyValue.of(name, defaultValue)
        }
        return result
    }

    private fun isSimpleQueryType(parameter: PsiParameter): Boolean {
        if (parameter.type is PsiPrimitiveType) return true
        if (parameter.type.canonicalText in SIMPLE_QUERY_TYPES) return true
        val resolved = (parameter.type as? PsiClassType)?.resolve()
        if (resolved?.qualifiedName in SIMPLE_QUERY_TYPES) return true
        return resolved?.isEnum == true
    }

    private fun displayName(project: Project, method: PsiMethod): String {
        val annotations = method.annotations
        for (annotation in annotations) {
            val attributes = when (annotation.qualifiedName) {
                OPERATION -> listOf("summary", "description")
                API_OPERATION -> listOf("value", "notes")
                else -> continue
            }
            for (attribute in attributes) {
                val value = stringAttribute(project, annotation, attribute)
                if (!value.isNullOrBlank()) return value.trim()
            }
        }
        return method.name
    }

    /**
     * The declared default for a query parameter. `findAttributeValue` resolves the annotation's
     * default when the attribute is not written, and `@RequestParam.defaultValue` defaults to
     * Spring's `DEFAULT_NONE` sentinel rather than to an empty string (see [SpringRequestValues]).
     */
    private fun defaultValue(project: Project, annotation: PsiAnnotation): String {
        val value = stringAttribute(project, annotation, "defaultValue") ?: return ""
        if (value.isBlank() || SpringRequestValues.isDefaultNone(value)) return ""
        return value
    }

    private fun stringAttribute(
        project: Project,
        annotation: PsiAnnotation,
        name: String
    ): String? {
        val member = annotation.findAttributeValue(name) ?: return null
        return stringValue(project, member)
    }

    private fun stringValue(project: Project, member: PsiAnnotationMemberValue): String? {
        val evaluated = JavaPsiFacade.getInstance(project).constantEvaluationHelper
            .computeConstantExpression(member)
        if (evaluated is String) return evaluated
        val text = member.text.trim()
        if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) {
            return text.substring(1, text.length - 1)
        }
        return null
    }
}
