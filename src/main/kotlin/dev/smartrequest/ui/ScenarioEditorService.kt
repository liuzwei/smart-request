package dev.smartrequest.ui

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.smartrequest.model.ScenarioConfig
import dev.smartrequest.service.SmartRequestProjectService
import java.nio.file.Files
import java.nio.file.Path

/**
 * Owns the editor tab of every scenario.
 *
 * IntelliJ 2026.2 no longer ships a production light-file class, so each scenario gets an empty
 * scratch file in the IDE temp directory (one directory per scenario, so two scenarios may share a
 * name). The file is only a handle for the tab: the scenario itself lives in `.idea/smart-request.xml`,
 * isReadOnly on [ScenarioFileType] keeps the platform from trying to save it, and the file name is
 * kept equal to the scenario name so the tab title follows renames.
 */
@Service(Service.Level.PROJECT)
class ScenarioEditorService(private val project: Project) : Disposable {

    private val service = project.service<SmartRequestProjectService>()
    private val files = HashMap<String, VirtualFile>()

    private val stateListener: () -> Unit = {
        files.forEach { (scenarioId, file) ->
            val scenario = service.scenario(scenarioId) ?: return@forEach
            syncFileName(file, scenario)
        }
        files.keys.filter { service.scenario(it) == null }.forEach { close(it) }
    }

    init {
        service.addChangeListener(stateListener)
    }

    /** Opens (or focuses) the editor tab of [scenarioId]. Must be called on the EDT. */
    fun open(scenarioId: String) {
        val scenario = service.scenario(scenarioId) ?: return
        val file = files.getOrPut(scenarioId) { createFile(scenario) }
        syncFileName(file, scenario)
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** Closes the tab, if open. Called when the scenario is deleted. */
    fun close(scenarioId: String) {
        val file = files.remove(scenarioId) ?: return
        FileEditorManager.getInstance(project).closeFile(file)
    }

    private fun createFile(scenario: ScenarioConfig): VirtualFile {
        val directory = Path.of(PathManager.getTempPath(), "smart-request-scenarios", scenario.id)
        Files.createDirectories(directory)
        val target = directory.resolve(fileName(scenario))
        if (!Files.exists(target)) {
            Files.writeString(target, "")
        }
        val path = target.toString().replace('\\', '/')
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            ?: error("Cannot create the scenario editor file at $path")
        file.putUserData(SCENARIO_ID, scenario.id)
        return file
    }

    private fun syncFileName(file: VirtualFile, scenario: ScenarioConfig) {
        val target = fileName(scenario)
        if (file.name == target) return
        val directory = Path.of(file.path).parent ?: return
        runCatching { Files.deleteIfExists(directory.resolve(target)) }
        ApplicationManager.getApplication().runWriteAction {
            runCatching { file.rename(this, target) }
        }
        FileEditorManager.getInstance(project).updateFilePresentation(file)
    }

    private fun fileName(scenario: ScenarioConfig): String {
        val base = scenario.name.trim()
            .map { character ->
                if (character.isLetterOrDigit() || character == ' ' || character == '-' || character == '_' ||
                    character == '.'
                ) {
                    character
                } else {
                    '_'
                }
            }
            .joinToString("")
            .trim()
            .ifEmpty { "Scenario" }
        return "$base.${ScenarioFileType.EXTENSION}"
    }

    /** Virtual file of [scenarioId] if this project already created one; test hook. */
    internal fun fileFor(scenarioId: String): VirtualFile? = files[scenarioId]

    override fun dispose() {
        service.removeChangeListener(stateListener)
        files.clear()
    }

    companion object {
        internal val SCENARIO_ID: Key<String> = Key.create("smart-request.scenario-id")

        fun getInstance(project: Project): ScenarioEditorService = project.service()
    }
}
