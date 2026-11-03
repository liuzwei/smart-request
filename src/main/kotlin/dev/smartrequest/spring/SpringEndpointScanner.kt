package dev.smartrequest.spring

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch

data class DiscoveredEndpoint(
    val httpMethod: String,
    val path: String,
    val controllerClass: String,
    val methodName: String,
    val springKey: String
) {
    val displayName: String get() = "$httpMethod $path"
}

data class SpringScanResult(
    val endpoints: List<DiscoveredEndpoint>,
    val springDetected: Boolean
)

/**
 * Finds Spring MVC handler methods. It intentionally only reads annotations and paths: parameters, bodies,
 * and parameter descriptions can be added later without changing the import model.
 */
object SpringEndpointScanner {
    private const val REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController"
    private const val CONTROLLER = "org.springframework.stereotype.Controller"
    private const val REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping"

    private val SHORTCUT_ANNOTATIONS = mapOf(
        "org.springframework.web.bind.annotation.GetMapping" to "GET",
        "org.springframework.web.bind.annotation.PostMapping" to "POST",
        "org.springframework.web.bind.annotation.PutMapping" to "PUT",
        "org.springframework.web.bind.annotation.PatchMapping" to "PATCH",
        "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE"
    )

    private val ALL_MAPPINGS = SHORTCUT_ANNOTATIONS.keys + REQUEST_MAPPING

    fun isController(psiClass: PsiClass): Boolean =
        psiClass.annotations.any { it.qualifiedName == REST_CONTROLLER || it.qualifiedName == CONTROLLER }

    fun isMappingMethod(method: com.intellij.psi.PsiMethod): Boolean =
        method.annotations.any { it.qualifiedName in ALL_MAPPINGS }

    fun hasMappedMethods(psiClass: PsiClass): Boolean =
        psiClass.methods.any { method ->
            !method.hasModifierProperty(PsiModifier.PRIVATE) &&
                    !method.hasModifierProperty(PsiModifier.STATIC) &&
                    isMappingMethod(method)
        }

    fun endpointsForClass(project: Project, psiClass: PsiClass): List<DiscoveredEndpoint> {
        if (!isController(psiClass)) return emptyList()
        return scanController(project, psiClass)
    }

    fun endpointsForMethod(
        project: Project,
        psiClass: PsiClass,
        method: com.intellij.psi.PsiMethod
    ): List<DiscoveredEndpoint> {
        if (!isController(psiClass) || !isMappingMethod(method)) return emptyList()
        return scanController(project, psiClass).filter { it.methodName == method.name }
    }

    fun scan(project: Project, indicator: ProgressIndicator? = null): SpringScanResult {
        val facade = JavaPsiFacade.getInstance(project)
        val allScope = GlobalSearchScope.allScope(project)
        val annotationClasses = listOf(REST_CONTROLLER, CONTROLLER)
            .mapNotNull { facade.findClass(it, allScope) }
        if (annotationClasses.isEmpty()) {
            return SpringScanResult(emptyList(), springDetected = false)
        }

        val controllers = LinkedHashSet<PsiClass>()
        val projectScope = GlobalSearchScope.projectScope(project)
        for (annotationClass in annotationClasses) {
            if (indicator?.isCanceled == true) return SpringScanResult(emptyList(), true)
            AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope)
                .findAll()
                .forEach { controllers += it }
        }

        val endpoints = LinkedHashSet<DiscoveredEndpoint>()
        for (controller in controllers) {
            if (indicator?.isCanceled == true) break
            if (!controller.isValid) continue
            endpoints += scanController(project, controller)
        }

        return SpringScanResult(
            endpoints.sortedWith(compareBy({ it.path }, { it.httpMethod })),
            springDetected = true
        )
    }

    private fun scanController(project: Project, controller: PsiClass): List<DiscoveredEndpoint> {
        val className = controller.qualifiedName ?: controller.name ?: return emptyList()
        val classMapping = controller.annotations.firstOrNull { it.qualifiedName == REQUEST_MAPPING }
        val classPaths = classMapping?.let { pathsOf(project, it) }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("")
        val result = ArrayList<DiscoveredEndpoint>()

        controller.methods.forEach { method ->
            if (method.hasModifierProperty(PsiModifier.PRIVATE) || method.hasModifierProperty(PsiModifier.STATIC)) {
                return@forEach
            }
            val mapping = method.annotations.firstOrNull { it.qualifiedName in ALL_MAPPINGS } ?: return@forEach
            val qualifiedName = mapping.qualifiedName ?: return@forEach
            val httpMethods = if (qualifiedName == REQUEST_MAPPING) {
                methodsOf(project, mapping)
            } else {
                listOfNotNull(SHORTCUT_ANNOTATIONS[qualifiedName])
            }.ifEmpty { listOf("GET") }
            val methodPaths = pathsOf(project, mapping).ifEmpty { listOf("") }

            for (classPath in classPaths) {
                for (methodPath in methodPaths) {
                    val fullPath = SpringPathUtils.normalizePath(SpringPathUtils.joinPaths(classPath, methodPath))
                    for (httpMethod in httpMethods) {
                        result += DiscoveredEndpoint(
                            httpMethod = httpMethod,
                            path = fullPath,
                            controllerClass = className,
                            methodName = method.name,
                            springKey = "$className#${method.name}:$httpMethod:$fullPath"
                        )
                    }
                }
            }
        }
        return result
    }

    private fun pathsOf(project: Project, annotation: PsiAnnotation): List<String> {
        val values = annotation.findAttributeValue("value")?.let { stringsOf(project, it) }.orEmpty()
        if (values.isNotEmpty()) return values
        return annotation.findAttributeValue("path")?.let { stringsOf(project, it) }.orEmpty()
    }

    private fun methodsOf(project: Project, annotation: PsiAnnotation): List<String> {
        val attribute = annotation.findAttributeValue("method") ?: return emptyList()
        val values = if (attribute is PsiArrayInitializerMemberValue) {
            attribute.initializers.toList()
        } else {
            listOf(attribute)
        }
        return values.mapNotNull { value ->
            val text = (JavaPsiFacade.getInstance(project).constantEvaluationHelper
                .computeConstantExpression(value) as? String)
                ?: value.text
            text.substringAfterLast('.').trim().trim('"').takeIf { it in HTTP_METHODS_SET }
        }
    }

    private fun stringsOf(project: Project, value: PsiAnnotationMemberValue): List<String> {
        if (value is PsiArrayInitializerMemberValue) {
            return value.initializers.flatMap { stringsOf(project, it) }
        }
        val evaluated = JavaPsiFacade.getInstance(project).constantEvaluationHelper
            .computeConstantExpression(value)
        if (evaluated is String) return listOf(evaluated)
        if (value is PsiLiteralExpression) {
            (value.value as? String)?.let { return listOf(it) }
        }
        val text = value.text.trim()
        if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) {
            return listOf(text.substring(1, text.length - 1))
        }
        return emptyList()
    }

    private val HTTP_METHODS_SET = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
}
