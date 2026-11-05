package dev.smartrequest.ui

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * File type of the scenario editor tabs. The file carries no meaningful text: it exists so a
 * scenario can live in a normal editor tab (split, reopen, close) while its data is stored in the
 * project state. Tabs are opened on empty scratch files in the IDE temp directory.
 */
class ScenarioFileType : FileType {

    override fun getName(): String = NAME

    override fun getDescription(): String = "Smart Request scenario orchestration flow"

    override fun getDefaultExtension(): String = EXTENSION

    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null

    override fun isBinary(): Boolean = false

    override fun isReadOnly(): Boolean = true

    override fun getIcon(): Icon? = null

    companion object {
        @JvmField
        val INSTANCE = ScenarioFileType()

        const val NAME = "Smart Request Scenario"
        const val EXTENSION = "smart-scenario"
    }
}
