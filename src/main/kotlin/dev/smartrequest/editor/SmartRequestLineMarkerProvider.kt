package dev.smartrequest.editor

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.SmartPointerManager
import com.intellij.util.Function
import dev.smartrequest.spring.SpringEndpointScanner
import dev.smartrequest.spring.SpringGenerationPayload
import dev.smartrequest.spring.SpringRequestFactory
import dev.smartrequest.spring.SpringRequestImporter
import javax.swing.Icon

/**
 * Adds a Smart Request gutter icon next to Spring controller class names and mapping method names.
 *
 * Class icon: import/update the whole controller as one group.
 * Method icon: import/update just that handler method.
 *
 * The line marker uses the platform's standard navigation-handler API. Implementing a custom
 * GutterIconRenderer is not supported here because LineMarkerInfo internally casts its renderer.
 */
class SmartRequestLineMarkerProvider : LineMarkerProvider, DumbAware {

    private val icon: Icon by lazy {
        IconLoader.getIcon("/icons/smartRequest.svg", SmartRequestLineMarkerProvider::class.java)
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null
        val parent = element.parent
        if (parent is PsiClass && parent.nameIdentifier == element) {
            if (!SpringEndpointScanner.isController(parent) ||
                !SpringEndpointScanner.hasMappedMethods(parent)
            ) {
                return null
            }
            return marker(
                element,
                parent,
                "Generate Smart Request for ${parent.name ?: "this controller"}"
            )
        }
        if (parent is PsiMethod && parent.nameIdentifier == element) {
            val controller = parent.containingClass ?: return null
            if (!SpringEndpointScanner.isController(controller) ||
                !SpringEndpointScanner.isMappingMethod(parent)
            ) {
                return null
            }
            return marker(element, parent, "Generate Smart Request for ${parent.name}()")
        }
        return null
    }

    private fun marker(
        identifier: PsiIdentifier,
        target: PsiElement,
        tooltip: String
    ): LineMarkerInfo<PsiIdentifier> {
        val project = target.project
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(target)
        val tooltipProvider = Function<PsiIdentifier, String> { tooltip }
        val navigationHandler = GutterIconNavigationHandler<PsiIdentifier> { _, _ ->
            generateRequests(project, pointer)
        }
        return LineMarkerInfo(
            identifier,
            identifier.textRange,
            icon,
            tooltipProvider,
            navigationHandler,
            GutterIconRenderer.Alignment.LEFT
        )
    }

    private fun generateRequests(project: Project, pointer: SmartPsiElementPointer<out PsiElement>) {
        if (project.isDisposed) return
        val element = pointer.element ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Smart Request")
        toolWindow?.show()

        val payload = try {
            ReadAction.compute<SpringGenerationPayload?, RuntimeException> {
                when (element) {
                    is PsiClass -> SpringRequestFactory.prepareClass(project, element)
                    is PsiMethod -> element.containingClass?.let {
                        SpringRequestFactory.prepareMethod(project, it, element)
                    }
                    else -> null
                }
            }
        } catch (error: Throwable) {
            notify(
                project,
                "Could not generate request: ${error.message ?: error.javaClass.simpleName}",
                NotificationType.ERROR
            )
            return
        }

        if (payload == null) {
            notify(project, "No Spring mapping endpoints were found for this element.", NotificationType.WARNING)
            return
        }

        val result = SpringRequestImporter.apply(project, payload)
        toolWindow?.activate(null)
        notify(
            project,
            "Generated ${payload.requests.size} request(s) in group '${result.groupName}': " +
                    "${result.added} new, ${result.updated} updated.",
            NotificationType.INFORMATION
        )
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Smart Request")
            .createNotification("Smart Request", message, type)
            .notify(project)
    }
}
