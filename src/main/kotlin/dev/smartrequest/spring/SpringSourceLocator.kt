package dev.smartrequest.spring

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope

/**
 * Resolves the Spring handler method stored in a request's `springKey`.
 *
 * Imported requests keep the key `fully.qualified.Controller#method:HTTP:path`, which is stable across
 * re-imports and is enough to find the method again without storing a PSI pointer. Callers must run
 * this in a read action.
 */
object SpringSourceLocator {

    fun findMethod(project: Project, springKey: String): PsiMethod? {
        val className = springKey.substringBefore('#').trim().takeIf { it.isNotEmpty() } ?: return null
        val methodName = springKey.substringAfter('#', "")
            .substringBefore(':')
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: return null
        return findMethod(project, className, methodName)
    }

    fun findMethod(project: Project, className: String, methodName: String): PsiMethod? {
        val psiClass: PsiClass = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.allScope(project))
            ?: return null
        val candidates = psiClass.findMethodsByName(methodName, false)
        return candidates.firstOrNull { SpringEndpointScanner.isMappingMethod(it) }
            ?: candidates.firstOrNull()
    }
}
