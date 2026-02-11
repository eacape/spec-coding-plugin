package com.eacape.speccodingplugin.window

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

@Suppress("OVERRIDE_DEPRECATION")
class WindowRegistryProjectManagerListener : ProjectManagerListener {

    override fun projectOpened(project: Project) {
        WindowRegistry.getInstance().registerWindow(project)
    }

    override fun projectClosed(project: Project) {
        WindowRegistry.getInstance().unregisterWindow(project)
    }
}
