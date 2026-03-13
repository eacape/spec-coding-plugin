package com.eacape.speccodingplugin.ui.spec

import com.intellij.icons.AllIcons
import javax.swing.Icon

internal object SpecWorkflowIcons {
    val Advance: Icon = AllIcons.General.ArrowRight
    val Execute: Icon = AllIcons.Actions.Execute
    val Refresh: Icon = AllIcons.General.InlineRefresh
    val Complete: Icon = AllIcons.General.GreenCheckmark
    val Pause: Icon = AllIcons.General.InspectionsPause
    val Resume: Icon = AllIcons.Actions.Resume
    val OpenDocument: Icon = AllIcons.Actions.MenuOpen
    val History: Icon = AllIcons.Vcs.HistoryInline
    val Save: Icon = AllIcons.General.OpenDisk
    val Edit: Icon = AllIcons.Actions.Edit
    val Add: Icon = AllIcons.General.Add
    val Back: Icon = AllIcons.Actions.Back
    val Forward: Icon = AllIcons.Actions.Forward
    val Clone: Icon = AllIcons.Actions.Copy
    val Overflow: Icon = AllIcons.General.GearPlain
    val Close: Icon = AllIcons.Actions.Close
    val Branch: Icon = AllIcons.Vcs.Branch
    val OpenToolWindow: Icon = AllIcons.General.OpenInToolWindow

    fun debugId(icon: Icon?): String {
        return when (icon) {
            null -> ""
            Advance -> "advance"
            Execute -> "execute"
            Refresh -> "refresh"
            Complete -> "complete"
            Pause -> "pause"
            Resume -> "resume"
            OpenDocument -> "openDocument"
            History -> "history"
            Save -> "save"
            Edit -> "edit"
            Add -> "add"
            Back -> "back"
            Forward -> "forward"
            Clone -> "clone"
            Overflow -> "overflow"
            Close -> "close"
            Branch -> "branch"
            OpenToolWindow -> "openToolWindow"
            else -> icon::class.java.name
        }
    }
}
