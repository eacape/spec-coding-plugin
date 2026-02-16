package com.eacape.speccodingplugin.ui.completion

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.context.ContextItem
import com.eacape.speccodingplugin.context.ContextType
import com.eacape.speccodingplugin.prompt.PromptManager
import com.eacape.speccodingplugin.skill.SkillRegistry
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.search.PsiShortNamesCache

/**
 * 补全数据提供者（Project-level Service）
 * 根据触发类型提供补全候选项
 */
@Service(Service.Level.PROJECT)
class CompletionProvider internal constructor(
    private val project: Project,
    private val skillRegistryProvider: () -> SkillRegistry,
    private val promptManagerProvider: () -> PromptManager,
    private val fileCompletionsProvider: (String) -> List<CompletionItem>,
    private val isDumbModeProvider: () -> Boolean,
    private val classNamesProvider: () -> Array<String>,
) {

    constructor(project: Project) : this(
        project = project,
        skillRegistryProvider = { SkillRegistry.getInstance(project) },
        promptManagerProvider = { PromptManager.getInstance(project) },
        fileCompletionsProvider = { query -> defaultFileCompletions(project, query) },
        isDumbModeProvider = { DumbService.isDumb(project) },
        classNamesProvider = { PsiShortNamesCache.getInstance(project).allClassNames },
    )

    private val skillRegistry by lazy { skillRegistryProvider() }
    private val promptManager by lazy { promptManagerProvider() }

    /**
     * 根据触发解析结果获取补全项
     */
    fun getCompletions(trigger: TriggerParseResult): List<CompletionItem> {
        return when (trigger.triggerType) {
            TriggerType.SLASH -> getSlashCompletions(trigger.query)
            TriggerType.AT -> getFileCompletions(trigger.query)
            TriggerType.HASH -> getSymbolCompletions(trigger.query)
            TriggerType.ANGLE -> getTemplateCompletions(trigger.query)
        }
    }

    private fun getSlashCompletions(query: String): List<CompletionItem> {
        return skillRegistry.searchSkills(query).map { skill ->
            CompletionItem(
                displayText = "/${skill.slashCommand}",
                insertText = "/${skill.slashCommand}",
                description = skill.description,
            )
        }
    }

    private fun getFileCompletions(query: String): List<CompletionItem> {
        return fileCompletionsProvider(query)
    }

    private fun getSymbolCompletions(query: String): List<CompletionItem> {
        if (query.length < 2) return emptyList()
        if (isDumbModeProvider()) return emptyList()

        val results = mutableListOf<CompletionItem>()
        val limit = 20

        val classNames = classNamesProvider()
            .filter { it.lowercase().contains(query.lowercase()) }
            .take(limit)

        for (name in classNames) {
            if (results.size >= limit) break
            results.add(
                CompletionItem(
                    displayText = name,
                    insertText = "#$name",
                    description = SpecCodingBundle.message("completion.symbol.description.class"),
                ),
            )
        }

        return results
    }

    private fun getTemplateCompletions(query: String): List<CompletionItem> {
        val templates = promptManager.listPromptTemplates()
        val lowerQuery = query.lowercase()

        return templates
            .filter { lowerQuery.isBlank() || it.name.lowercase().contains(lowerQuery) }
            .map { template ->
                CompletionItem(
                    displayText = template.name,
                    insertText = template.content,
                    description = SpecCodingBundle.message("completion.template.description.prompt"),
                )
            }
    }

    companion object {
        private const val MAX_DEPTH = 6

        private val IGNORED_DIRS = setOf(
            ".git", ".idea", ".gradle", "build", "out",
            "node_modules", ".spec-coding", "__pycache__",
        )

        private fun defaultFileCompletions(project: Project, query: String): List<CompletionItem> {
            val basePath = project.basePath ?: return emptyList()
            val baseDir = com.intellij.openapi.vfs.LocalFileSystem
                .getInstance().findFileByPath(basePath) ?: return emptyList()

            val results = mutableListOf<CompletionItem>()
            val lowerQuery = query.lowercase()
            val limit = 20

            VfsUtilCore.visitChildrenRecursively(
                baseDir,
                object : VirtualFileVisitor<Unit>(limit(MAX_DEPTH)) {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (results.size >= limit) return false
                        if (file.isDirectory) {
                            if (lowerQuery.isNotBlank() && matchesQuery(file.name, lowerQuery)) {
                                results.add(directoryToCompletionItem(file, basePath))
                            }
                            return !isIgnoredDir(file.name)
                        }
                        if (matchesQuery(file.name, lowerQuery)) {
                            results.add(fileToCompletionItem(file, basePath))
                        }
                        return true
                    }
                },
            )

            return results
        }

        private fun fileToCompletionItem(file: VirtualFile, basePath: String): CompletionItem {
            val relativePath = file.path.removePrefix(basePath).removePrefix("/")
            return CompletionItem(
                displayText = file.name,
                insertText = "@$relativePath",
                description = relativePath,
                contextItem = ContextItem(
                    type = ContextType.REFERENCED_FILE,
                    label = file.name,
                    content = "", // content loaded on selection
                    filePath = file.path,
                    priority = 60,
                ),
            )
        }

        private fun directoryToCompletionItem(dir: VirtualFile, basePath: String): CompletionItem {
            val relativePath = dir.path.removePrefix(basePath).removePrefix("/")
            val display = if (dir.name.endsWith("/")) dir.name else "${dir.name}/"
            val pathText = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
            return CompletionItem(
                displayText = display,
                insertText = "@$pathText",
                description = pathText,
                contextItem = ContextItem(
                    type = ContextType.PROJECT_STRUCTURE,
                    label = display,
                    content = "",
                    filePath = dir.path,
                    priority = 58,
                ),
            )
        }

        private fun matchesQuery(fileName: String, lowerQuery: String): Boolean {
            if (lowerQuery.isBlank()) return true
            return fileName.lowercase().contains(lowerQuery)
        }

        private fun isIgnoredDir(name: String): Boolean {
            return name in IGNORED_DIRS
        }

        fun getInstance(project: Project): CompletionProvider {
            return project.getService(CompletionProvider::class.java)
        }
    }
}
