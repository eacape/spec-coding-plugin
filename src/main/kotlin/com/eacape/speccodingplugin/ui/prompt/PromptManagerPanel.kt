package com.eacape.speccodingplugin.ui.prompt

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.core.SpecCodingProjectService
import com.eacape.speccodingplugin.i18n.LocaleChangedEvent
import com.eacape.speccodingplugin.i18n.LocaleChangedListener
import com.eacape.speccodingplugin.prompt.PromptManager
import com.eacape.speccodingplugin.prompt.PromptTemplate
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Point
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.border.AbstractBorder
import javax.swing.border.CompoundBorder

/**
 * Prompt 管理面板
 * 展示 Prompt 列表，支持新建、编辑、删除操作
 */
class PromptManagerPanel(
    private val project: Project,
) : JPanel(BorderLayout()), Disposable {

    private val promptManager = PromptManager.getInstance(project)
    private val projectService: SpecCodingProjectService = project.getService(SpecCodingProjectService::class.java)
    private val listModel = DefaultListModel<PromptTemplate>()
    private val promptList = JBList(listModel)
    private val promptJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val newBtn = JButton()
    private val aiDraftBtn = JButton()

    @Volatile
    private var isDisposed = false

    init {
        border = JBUI.Borders.empty(8)
        setupUI()
        subscribeToLocaleEvents()
        refreshLocalizedTexts()
        refresh()
    }

    private fun setupUI() {
        // 顶部标题条移除，仅保留操作按钮
        styleActionButton(newBtn)
        styleActionButton(aiDraftBtn)

        // 列表
        promptList.selectionMode =
            ListSelectionModel.SINGLE_SELECTION
        promptList.cellRenderer = PromptListCellRenderer()
        promptList.isOpaque = false
        promptList.fixedCellHeight = -1
        promptList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    handleListClick(e.point, e.clickCount)
                }

                override fun mouseExited(e: MouseEvent) {
                    promptList.cursor = Cursor.getDefaultCursor()
                }
            },
        )
        promptList.addMouseMotionListener(
            object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    updateListCursor(e.point)
                }
            },
        )

        val scrollPane = JBScrollPane(promptList).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            isOpaque = false
        }
        val listCard = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = LIST_SECTION_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LIST_SECTION_BORDER, 1),
                JBUI.Borders.empty(2),
            )
            add(scrollPane, BorderLayout.CENTER)
        }

        // 按钮事件
        newBtn.addActionListener { onNew() }
        aiDraftBtn.addActionListener { onAiDraft() }

        val footerActions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
            add(aiDraftBtn)
            add(newBtn)
        }

        add(listCard, BorderLayout.CENTER)
        add(footerActions, BorderLayout.SOUTH)
    }

    fun refresh() {
        listModel.clear()
        promptManager.listPromptTemplates().forEach { listModel.addElement(it) }
    }

    private fun refreshLocalizedTexts() {
        aiDraftBtn.text = SpecCodingBundle.message("prompt.manager.ai")
        newBtn.text = SpecCodingBundle.message("prompt.manager.new")
        promptList.repaint()
    }

    private fun subscribeToLocaleEvents() {
        project.messageBus.connect(this).subscribe(
            LocaleChangedListener.TOPIC,
            object : LocaleChangedListener {
                override fun onLocaleChanged(event: LocaleChangedEvent) {
                    invokeLaterSafe {
                        refreshLocalizedTexts()
                        refresh()
                    }
                }
            },
        )
    }

    private fun updateListCursor(point: Point) {
        val index = promptList.locationToIndex(point)
        if (index < 0) {
            promptList.cursor = Cursor.getDefaultCursor()
            return
        }
        val cellBounds = promptList.getCellBounds(index, index) ?: run {
            promptList.cursor = Cursor.getDefaultCursor()
            return
        }
        val action = PromptListCellRenderer.resolveRowAction(cellBounds, point)
        promptList.cursor = if (action != null) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun handleListClick(point: Point, clickCount: Int) {
        val index = promptList.locationToIndex(point)
        if (index < 0) {
            return
        }
        val cellBounds = promptList.getCellBounds(index, index) ?: return
        if (!cellBounds.contains(point)) {
            return
        }
        val selected = listModel.getElementAt(index)
        promptList.selectedIndex = index
        when (PromptListCellRenderer.resolveRowAction(cellBounds, point)) {
            PromptListCellRenderer.RowAction.EDIT -> onEdit(selected)
            PromptListCellRenderer.RowAction.DELETE -> onDelete(selected)
            null -> if (clickCount >= 2) onEdit(selected)
        }
    }

    private fun styleActionButton(button: JButton) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.font = button.font.deriveFont(java.awt.Font.BOLD, 11f)
        button.margin = JBUI.emptyInsets()
        button.isOpaque = true
        button.isContentAreaFilled = true
        button.background = ACTION_BUTTON_BG
        button.foreground = ACTION_BUTTON_FG
        button.border = CompoundBorder(
            RoundedLineBorder(
                lineColor = ACTION_BUTTON_BORDER,
                arc = JBUI.scale(12),
            ),
            JBUI.Borders.empty(3, 10, 3, 10),
        )
        button.preferredSize = JBUI.size(0, 30)
        button.minimumSize = JBUI.size(0, 30)
        button.putClientProperty("JButton.buttonType", "roundRect")
        button.putClientProperty("JComponent.roundRectArc", JBUI.scale(12))
    }

    private fun onNew() {
        val existingIds = promptManager.listPromptTemplates().map { it.id }.toSet()
        val dialog = PromptEditorDialog(existingPromptIds = existingIds)
        if (dialog.showAndGet()) {
            val template = dialog.result ?: return
            promptManager.upsertTemplate(template)
            refresh()
        }
    }

    private fun onEdit(template: PromptTemplate? = promptList.selectedValue) {
        val selected = template ?: return
        val existingIds = promptManager.listPromptTemplates().map { it.id }.toSet()
        val dialog = PromptEditorDialog(existing = selected, existingPromptIds = existingIds)
        if (dialog.showAndGet()) {
            val template = dialog.result ?: return
            promptManager.upsertTemplate(template)
            refresh()
        }
    }

    private fun onDelete(template: PromptTemplate? = promptList.selectedValue) {
        val selected = template ?: return
        promptManager.deleteTemplate(selected.id)
        refresh()
    }

    private fun onAiDraft() {
        val requirement = Messages.showMultilineInputDialog(
            project,
            SpecCodingBundle.message("prompt.manager.ai.requirement.message"),
            SpecCodingBundle.message("prompt.manager.ai.requirement.title"),
            "",
            null,
            null,
        )?.trim().orEmpty()
        if (requirement.isBlank()) {
            return
        }
        setToolbarBusy(true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runBlocking { generatePromptDraftWithAi(requirement) }
            invokeLaterSafe {
                setToolbarBusy(false)
                if (result.usedFallback && !result.reason.isNullOrBlank()) {
                    Messages.showWarningDialog(
                        project,
                        SpecCodingBundle.message("prompt.manager.ai.fallback", result.reason),
                        SpecCodingBundle.message("prompt.manager.ai"),
                    )
                }
                val existingIds = promptManager.listPromptTemplates().map { it.id }.toSet()
                val dialog = PromptEditorDialog(
                    existingPromptIds = existingIds,
                    initialName = result.draft.name,
                    initialContent = result.draft.content,
                )
                if (dialog.showAndGet()) {
                    val template = dialog.result ?: return@invokeLaterSafe
                    promptManager.upsertTemplate(template)
                    refresh()
                }
            }
        }
    }

    private fun setToolbarBusy(busy: Boolean) {
        newBtn.isEnabled = !busy
        aiDraftBtn.isEnabled = !busy
    }

    private suspend fun generatePromptDraftWithAi(requirement: String): PromptDraftResult {
        val fallback = buildFallbackPromptDraft(requirement)
        val providers = projectService.availableProviders()
        val providerId = providers.firstOrNull()
            ?: return PromptDraftResult(
                draft = fallback,
                usedFallback = true,
                reason = SpecCodingBundle.message("prompt.manager.ai.reason.providerUnavailable"),
            )
        val prompt = buildString {
            appendLine("You are generating a reusable prompt template for an IDE coding assistant.")
            appendLine("Return ONLY strict JSON, no markdown fences, no explanation.")
            appendLine("Required JSON keys: name, content.")
            appendLine("Rules:")
            appendLine("1) name: concise and human-readable.")
            appendLine("2) content: a direct instruction template for the assistant.")
            appendLine("3) Keep content practical and implementation-oriented.")
            appendLine()
            appendLine("User requirement:")
            appendLine(requirement)
        }
        val responseText = StringBuilder()
        val callResult = runCatching {
            projectService.chat(
                providerId = providerId,
                userInput = prompt,
                planExecuteVerifySections = false,
            ) { chunk ->
                if (chunk.delta.isNotBlank()) {
                    responseText.append(chunk.delta)
                }
            }
        }
        if (callResult.isFailure) {
            return PromptDraftResult(
                draft = fallback,
                usedFallback = true,
                reason = callResult.exceptionOrNull()?.message
                    ?: SpecCodingBundle.message("common.unknown"),
            )
        }
        val rawResponse = responseText.toString().trim()
        if (rawResponse.isBlank()) {
            return PromptDraftResult(
                draft = fallback,
                usedFallback = true,
                reason = SpecCodingBundle.message("prompt.manager.ai.reason.emptyResponse"),
            )
        }
        val draft = runCatching { parsePromptDraft(rawResponse, requirement) }.getOrNull()
            ?: return PromptDraftResult(
                draft = fallback,
                usedFallback = true,
                reason = SpecCodingBundle.message("prompt.manager.ai.reason.invalidJson"),
            )
        return PromptDraftResult(
            draft = draft,
            usedFallback = false,
            reason = null,
        )
    }

    private fun parsePromptDraft(raw: String, requirement: String): GeneratedPromptDraft {
        val root = parsePromptJson(raw)
        val name = root["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.ifBlank { null }
            ?: requirement
                .replace("\\s+".toRegex(), " ")
                .trim()
                .take(40)
                .ifBlank { SpecCodingBundle.message("prompt.manager.ai.defaultName") }
        val content = root["content"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.ifBlank { null }
            ?: throw IllegalArgumentException(SpecCodingBundle.message("prompt.manager.ai.reason.invalidJson"))
        return GeneratedPromptDraft(name = name, content = content)
    }

    private fun parsePromptJson(raw: String): JsonObject {
        val trimmed = raw.trim()
        val direct = runCatching { promptJson.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
        if (direct != null) {
            return direct
        }

        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            val candidate = trimmed.substring(firstBrace, lastBrace + 1)
            val extracted = runCatching { promptJson.parseToJsonElement(candidate) as? JsonObject }.getOrNull()
            if (extracted != null) {
                return extracted
            }
        }
        throw IllegalArgumentException(SpecCodingBundle.message("prompt.manager.ai.reason.invalidJson"))
    }

    private fun buildFallbackPromptDraft(requirement: String): GeneratedPromptDraft {
        val normalizedRequirement = requirement
            .replace("\\s+".toRegex(), " ")
            .trim()
            .ifBlank { SpecCodingBundle.message("prompt.manager.ai.defaultName") }
        val fallbackName = normalizedRequirement.take(40)
        val fallbackContent = buildString {
            appendLine("你是项目开发助手。")
            appendLine("围绕以下目标执行：")
            appendLine(normalizedRequirement)
            appendLine()
            appendLine("输出要求：")
            appendLine("1. 先澄清边界与约束。")
            appendLine("2. 再给出可执行步骤。")
            appendLine("3. 涉及代码时给出关键改动点。")
            appendLine("4. 最后附上验证建议。")
        }.trim()
        return GeneratedPromptDraft(name = fallbackName, content = fallbackContent)
    }

    private fun invokeLaterSafe(action: () -> Unit) {
        if (isDisposed) {
            return
        }
        ApplicationManager.getApplication().invokeLater {
            if (!isDisposed && !project.isDisposed) {
                action()
            }
        }
    }

    override fun dispose() {
        isDisposed = true
    }

    private data class GeneratedPromptDraft(
        val name: String,
        val content: String,
    )

    private data class PromptDraftResult(
        val draft: GeneratedPromptDraft,
        val usedFallback: Boolean,
        val reason: String?,
    )

    private class RoundedLineBorder(
        private val lineColor: Color,
        private val arc: Int,
        private val thickness: Int = 1,
    ) : AbstractBorder() {
        override fun paintBorder(
            c: java.awt.Component?,
            g: Graphics?,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            val graphics = g as? Graphics2D ?: return
            val g2 = graphics.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g2.color = lineColor
                val safeThickness = thickness.coerceAtLeast(1)
                repeat(safeThickness) { index ->
                    val offset = index + 0.5f
                    val drawWidth = width - index * 2 - 1f
                    val drawHeight = height - index * 2 - 1f
                    if (drawWidth <= 0f || drawHeight <= 0f) return@repeat
                    val arcSize = (arc - index * 2).coerceAtLeast(2).toFloat()
                    g2.draw(
                        RoundRectangle2D.Float(
                            x + offset,
                            y + offset,
                            drawWidth,
                            drawHeight,
                            arcSize,
                            arcSize,
                        ),
                    )
                }
            } finally {
                g2.dispose()
            }
        }

        override fun getBorderInsets(c: java.awt.Component?): Insets = Insets(
            thickness,
            thickness,
            thickness,
            thickness,
        )

        override fun getBorderInsets(c: java.awt.Component?, insets: Insets): Insets {
            insets.set(
                thickness,
                thickness,
                thickness,
                thickness,
            )
            return insets
        }
    }

    companion object {
        private val LIST_SECTION_BG = JBColor(Color(247, 249, 252), Color(44, 48, 55))
        private val LIST_SECTION_BORDER = JBColor(Color(209, 219, 234), Color(79, 86, 97))
        private val ACTION_BUTTON_BG = JBColor(Color(245, 248, 253), Color(62, 67, 77))
        private val ACTION_BUTTON_BORDER = JBColor(Color(194, 206, 224), Color(95, 106, 123))
        private val ACTION_BUTTON_FG = JBColor(Color(58, 78, 107), Color(199, 211, 230))
    }
}
