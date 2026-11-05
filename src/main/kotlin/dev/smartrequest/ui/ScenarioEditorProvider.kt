package dev.smartrequest.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import javax.swing.JComponent

/**
 * Opens a scenario in a normal editor tab. The provider only accepts the scratch files created by
 * [ScenarioEditorService], so no other file can end up in this editor by accident.
 */
class ScenarioEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.fileType.name == ScenarioFileType.NAME &&
                file.getUserData(ScenarioEditorService.SCENARIO_ID) != null

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val scenarioId = file.getUserData(ScenarioEditorService.SCENARIO_ID).orEmpty()
        return ScenarioFileEditor(project, scenarioId)
    }

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        const val EDITOR_TYPE_ID = "smart-request.scenario-editor"
    }
}

internal class ScenarioFileEditor(
    private val project: Project,
    scenarioId: String
) : UserDataHolderBase(), FileEditor {

    private val panel = ScenarioEditorPanel(project, scenarioId)
    private val changeSupport = PropertyChangeSupport(this)

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocusComponent()

    override fun getName(): String = "Scenario"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = !project.isDisposed && panel.isAlive()

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        changeSupport.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        changeSupport.removePropertyChangeListener(listener)
    }

    override fun dispose() {
        panel.dispose()
    }
}
