package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.ui.chat.MarkdownRenderer
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextPane
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.Timer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

class SpecDetailPanel(
    private val onGenerate: (String) -> Unit,
    private val onNextPhase: () -> Unit,
    private val onGoBack: () -> Unit,
    private val onComplete: () -> Unit,
    private val onPauseResume: () -> Unit,
    private val onOpenInEditor: (SpecPhase) -> Unit,
    private val onShowHistoryDiff: (SpecPhase) -> Unit,
    private val onSaveDocument: (SpecPhase, String, (Result<SpecWorkflow>) -> Unit) -> Unit,
) : JPanel(BorderLayout()) {

    private val treeRoot = DefaultMutableTreeNode(SpecCodingBundle.message("spec.detail.documents"))
    private val treeModel = DefaultTreeModel(treeRoot)
    private val documentTree = JTree(treeModel)

    private val previewPane = JTextPane()
    private val previewCardLayout = CardLayout()
    private val previewCardPanel = JPanel(previewCardLayout)
    private val editorArea = JBTextArea(14, 40)
    private val validationLabel = JBLabel("")
    private val inputArea = JBTextArea(3, 40)

    private val generateButton = JButton(SpecCodingBundle.message("spec.detail.generate"))
    private val nextPhaseButton = JButton(SpecCodingBundle.message("spec.detail.nextPhase"))
    private val goBackButton = JButton(SpecCodingBundle.message("spec.detail.goBack"))
    private val completeButton = JButton(SpecCodingBundle.message("spec.detail.complete"))
    private val pauseResumeButton = JButton(SpecCodingBundle.message("spec.detail.pause"))
    private val openEditorButton = JButton(SpecCodingBundle.message("spec.detail.openInEditor"))
    private val historyDiffButton = JButton(SpecCodingBundle.message("spec.detail.historyDiff"))
    private val editButton = JButton(SpecCodingBundle.message("spec.detail.edit"))
    private val saveButton = JButton(SpecCodingBundle.message("spec.detail.save"))
    private val cancelEditButton = JButton(SpecCodingBundle.message("spec.detail.cancel"))

    private var currentWorkflow: SpecWorkflow? = null
    private var selectedPhase: SpecPhase? = null
    private var previewSourceText: String = ""
    private var generatingPercent: Int = 0
    private var generatingFrameIndex: Int = 0
    private var isGeneratingActive: Boolean = false
    private var generationAnimationTimer: Timer? = null

    private var isEditing: Boolean = false
    private var editingPhase: SpecPhase? = null

    init {
        setupUI()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(2)

        val topSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            dividerLocation = 176
            dividerSize = JBUI.scale(6)
            isContinuousLayout = true
            border = JBUI.Borders.empty()
            background = PANEL_BG
        }

        documentTree.isRootVisible = false
        documentTree.showsRootHandles = false
        documentTree.rowHeight = JBUI.scale(22)
        documentTree.border = JBUI.Borders.empty(2, 0)
        documentTree.cellRenderer = PhaseTreeCellRenderer()
        documentTree.putClientProperty("JTree.lineStyle", "None")
        documentTree.addTreeSelectionListener {
            val node = documentTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val phase = node?.userObject as? PhaseNode
            if (phase != null) {
                selectedPhase = phase.phase
                showDocumentPreview(phase.phase)
            }
        }
        topSplit.leftComponent = createSectionContainer(
            JBScrollPane(documentTree).apply {
                border = JBUI.Borders.empty()
            }
        )

        val previewPanel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
        }
        previewPane.isEditable = false
        previewPane.isOpaque = false
        previewPane.border = JBUI.Borders.empty(2, 2)

        previewCardPanel.isOpaque = false
        previewCardPanel.add(
            createSectionContainer(
                JBScrollPane(previewPane).apply {
                    border = JBUI.Borders.empty()
                }
            ),
            CARD_PREVIEW,
        )
        editorArea.lineWrap = false
        editorArea.wrapStyleWord = false
        previewCardPanel.add(
            createSectionContainer(
                JBScrollPane(editorArea).apply {
                    border = JBUI.Borders.empty()
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                }
            ),
            CARD_EDIT,
        )
        previewCardLayout.show(previewCardPanel, CARD_PREVIEW)
        previewPanel.add(
            previewCardPanel,
            BorderLayout.CENTER,
        )
        validationLabel.border = JBUI.Borders.empty(3, 2)
        validationLabel.font = JBUI.Fonts.smallFont()
        previewPanel.add(
            JPanel(BorderLayout()).apply {
                isOpaque = true
                background = STATUS_BG
                border = SpecUiStyle.roundedCardBorder(
                    lineColor = STATUS_BORDER,
                    arc = JBUI.scale(10),
                    top = 0,
                    left = 6,
                    bottom = 0,
                    right = 6,
                )
                add(validationLabel, BorderLayout.CENTER)
            },
            BorderLayout.SOUTH,
        )
        topSplit.rightComponent = previewPanel

        val bottomPanel = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
        }

        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        updateInputPlaceholder(null)
        val inputScroll = JBScrollPane(inputArea)
        inputScroll.border = JBUI.Borders.empty()
        inputScroll.preferredSize = java.awt.Dimension(0, JBUI.scale(80))
        bottomPanel.add(createSectionContainer(inputScroll), BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(JBUI.scale(8))
        }
        setupButtons(buttonPanel)
        bottomPanel.add(
            createSectionContainer(
                JBScrollPane(buttonPanel).apply {
                    border = JBUI.Borders.empty(6, 4)
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                    viewport.isOpaque = false
                    isOpaque = false
                    SpecUiStyle.applySlimHorizontalScrollBar(this, height = 7)
                },
            ),
            BorderLayout.SOUTH,
        )

        val mainSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            topComponent = topSplit
            bottomComponent = bottomPanel
            resizeWeight = 0.67
            dividerSize = JBUI.scale(6)
            border = JBUI.Borders.empty()
            background = PANEL_BG
        }
        add(mainSplit, BorderLayout.CENTER)
    }

    private fun setupButtons(panel: JPanel) {
        styleActionButton(generateButton)
        styleActionButton(nextPhaseButton)
        styleActionButton(goBackButton)
        styleActionButton(completeButton)
        styleActionButton(pauseResumeButton)
        styleActionButton(openEditorButton)
        styleActionButton(historyDiffButton)
        styleActionButton(editButton)
        styleActionButton(saveButton)
        styleActionButton(cancelEditButton)
        saveButton.isVisible = false
        cancelEditButton.isVisible = false

        generateButton.addActionListener {
            val text = inputArea.text.trim()
            val phase = currentWorkflow?.currentPhase
            val allowBlank = phase == SpecPhase.DESIGN || phase == SpecPhase.IMPLEMENT
            if (text.isNotBlank() || allowBlank) {
                onGenerate(text)
            }
        }
        nextPhaseButton.addActionListener { onNextPhase() }
        goBackButton.addActionListener { onGoBack() }
        completeButton.addActionListener { onComplete() }
        pauseResumeButton.addActionListener { onPauseResume() }
        openEditorButton.addActionListener {
            selectedPhase?.let { onOpenInEditor(it) }
        }
        historyDiffButton.addActionListener {
            selectedPhase?.let { onShowHistoryDiff(it) }
        }
        editButton.addActionListener {
            startEditing()
        }
        saveButton.addActionListener {
            saveEditing()
        }
        cancelEditButton.addActionListener {
            stopEditing(keepText = false)
        }

        panel.add(generateButton)
        panel.add(nextPhaseButton)
        panel.add(goBackButton)
        panel.add(completeButton)
        panel.add(pauseResumeButton)
        panel.add(openEditorButton)
        panel.add(historyDiffButton)
        panel.add(editButton)
        panel.add(saveButton)
        panel.add(cancelEditButton)

        disableAllButtons()
    }

    private fun createSectionContainer(content: java.awt.Component): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = PANEL_BG
            border = SpecUiStyle.roundedCardBorder(
                lineColor = PANEL_BORDER,
                arc = JBUI.scale(14),
                top = 2,
                left = 2,
                bottom = 2,
                right = 2,
            )
            add(content, BorderLayout.CENTER)
        }
    }

    private fun styleActionButton(button: JButton) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = JBUI.insets(1, 4, 1, 4)
        button.isOpaque = true
        button.foreground = BUTTON_FG
        button.background = BUTTON_BG
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
            JBUI.Borders.empty(1, 5, 1, 5),
        )
        SpecUiStyle.applyRoundRect(button, arc = 10)
        val textWidth = button.getFontMetrics(button.font).stringWidth(button.text ?: "")
        val insets = button.insets
        val lafWidth = button.preferredSize?.width ?: 0
        val width = maxOf(
            lafWidth,
            textWidth + insets.left + insets.right + JBUI.scale(10),
            JBUI.scale(40),
        )
        button.preferredSize = JBUI.size(width, JBUI.scale(28))
        button.minimumSize = button.preferredSize
    }

    fun refreshLocalizedTexts() {
        treeRoot.userObject = SpecCodingBundle.message("spec.detail.documents")
        treeModel.reload()
        generateButton.text = SpecCodingBundle.message("spec.detail.generate")
        nextPhaseButton.text = SpecCodingBundle.message("spec.detail.nextPhase")
        goBackButton.text = SpecCodingBundle.message("spec.detail.goBack")
        completeButton.text = SpecCodingBundle.message("spec.detail.complete")
        openEditorButton.text = SpecCodingBundle.message("spec.detail.openInEditor")
        historyDiffButton.text = SpecCodingBundle.message("spec.detail.historyDiff")
        editButton.text = SpecCodingBundle.message("spec.detail.edit")
        saveButton.text = SpecCodingBundle.message("spec.detail.save")
        cancelEditButton.text = SpecCodingBundle.message("spec.detail.cancel")
        updateInputPlaceholder(currentWorkflow?.currentPhase)
        pauseResumeButton.text = if (currentWorkflow?.status == WorkflowStatus.PAUSED) {
            SpecCodingBundle.message("spec.detail.resume")
        } else {
            SpecCodingBundle.message("spec.detail.pause")
        }
        styleActionButton(generateButton)
        styleActionButton(nextPhaseButton)
        styleActionButton(goBackButton)
        styleActionButton(completeButton)
        styleActionButton(pauseResumeButton)
        styleActionButton(openEditorButton)
        styleActionButton(historyDiffButton)
        styleActionButton(editButton)
        styleActionButton(saveButton)
        styleActionButton(cancelEditButton)
        if (currentWorkflow == null) {
            validationLabel.text = SpecCodingBundle.message("spec.detail.noWorkflow")
        } else {
            selectedPhase?.let { showDocumentPreview(it) }
        }
    }

    fun updateWorkflow(workflow: SpecWorkflow?) {
        val previousWorkflowId = currentWorkflow?.id
        currentWorkflow = workflow
        if (workflow == null) {
            showEmpty()
            return
        }
        isGeneratingActive = false
        stopGeneratingAnimation()
        rebuildTree(workflow)
        updateInputPlaceholder(workflow.currentPhase)
        val preservedPhase = selectedPhase?.takeIf { previousWorkflowId == workflow.id }
        selectedPhase = preservedPhase ?: workflow.currentPhase
        updateTreeSelection(selectedPhase)
        updateButtonStates(workflow)
        showDocumentPreview(selectedPhase ?: workflow.currentPhase, keepGeneratingIndicator = false)
    }

    fun showEmpty() {
        isGeneratingActive = false
        stopGeneratingAnimation()
        isEditing = false
        editingPhase = null
        selectedPhase = null
        treeRoot.removeAllChildren()
        treeModel.reload()
        previewCardLayout.show(previewCardPanel, CARD_PREVIEW)
        documentTree.isEnabled = true
        previewSourceText = ""
        previewPane.text = ""
        validationLabel.text = SpecCodingBundle.message("spec.detail.noWorkflow")
        validationLabel.foreground = JBColor.GRAY
        inputArea.isEnabled = true
        editButton.isVisible = true
        saveButton.isVisible = false
        cancelEditButton.isVisible = false
        disableAllButtons()
    }

    private fun updateInputPlaceholder(currentPhase: SpecPhase?) {
        val key = when (currentPhase) {
            SpecPhase.DESIGN -> "spec.detail.input.placeholder.design"
            SpecPhase.IMPLEMENT -> "spec.detail.input.placeholder.implement"
            else -> "spec.detail.input.placeholder"
        }
        inputArea.emptyText.setText(SpecCodingBundle.message(key))
    }

    private fun updateTreeSelection(phase: SpecPhase?) {
        val targetPhase = phase ?: return
        for (row in 0 until documentTree.rowCount) {
            val path = documentTree.getPathForRow(row) ?: continue
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
            val phaseNode = node.userObject as? PhaseNode ?: continue
            if (phaseNode.phase == targetPhase) {
                documentTree.selectionPath = path
                break
            }
        }
    }

    private fun startEditing() {
        if (isEditing) return
        val workflow = currentWorkflow ?: return
        val phase = selectedPhase ?: workflow.currentPhase
        val document = workflow.getDocument(phase)
        isEditing = true
        editingPhase = phase
        editorArea.text = document?.content.orEmpty()
        editorArea.caretPosition = 0
        previewCardLayout.show(previewCardPanel, CARD_EDIT)
        documentTree.isEnabled = false
        inputArea.isEnabled = false
        updateButtonStates(workflow)
    }

    private fun stopEditing(keepText: Boolean) {
        val workflow = currentWorkflow ?: return
        if (!keepText) {
            editorArea.text = ""
        }
        isEditing = false
        editingPhase = null
        previewCardLayout.show(previewCardPanel, CARD_PREVIEW)
        documentTree.isEnabled = true
        inputArea.isEnabled = true
        updateButtonStates(workflow)
        selectedPhase?.let { showDocumentPreview(it, keepGeneratingIndicator = false) }
    }

    private fun saveEditing() {
        val workflow = currentWorkflow ?: return
        val phase = editingPhase ?: return
        val normalized = normalizeContent(editorArea.text)
        if (normalized.isBlank()) {
            Messages.showWarningDialog(
                SpecCodingBundle.message("spec.detail.edit.emptyNotAllowed"),
                SpecCodingBundle.message("spec.detail.save"),
            )
            return
        }
        saveButton.isEnabled = false
        cancelEditButton.isEnabled = false
        onSaveDocument(phase, normalized) { result ->
            saveButton.isEnabled = true
            cancelEditButton.isEnabled = true
            result.onSuccess { updated ->
                currentWorkflow = updated
                stopEditing(keepText = false)
                updateWorkflow(updated)
            }.onFailure {
                updateButtonStates(workflow)
            }
        }
    }

    private fun normalizeContent(content: String): String {
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    fun showGenerating(progress: Double) {
        generatingPercent = (progress * 100).toInt().coerceIn(0, 100)
        isGeneratingActive = true
        startGeneratingAnimation()
        updateGeneratingLabel()
        generateButton.isEnabled = false
    }

    fun showGenerationFailed() {
        isGeneratingActive = false
        stopGeneratingAnimation()
        val workflow = currentWorkflow
        if (workflow == null) {
            validationLabel.text = SpecCodingBundle.message("spec.detail.validation.none")
            validationLabel.foreground = JBColor.GRAY
            return
        }
        updateButtonStates(workflow)
        val phase = selectedPhase ?: workflow.currentPhase
        showDocumentPreview(phase, keepGeneratingIndicator = false)
    }

    private fun rebuildTree(workflow: SpecWorkflow) {
        treeRoot.removeAllChildren()
        for (phase in SpecPhase.entries) {
            val doc = workflow.documents[phase]
            val node = PhaseNode(phase, doc)
            treeRoot.add(DefaultMutableTreeNode(node))
        }
        treeModel.reload()
        documentTree.expandRow(0)
    }

    private fun showDocumentPreview(phase: SpecPhase, keepGeneratingIndicator: Boolean = true) {
        if (!keepGeneratingIndicator || !isGeneratingActive) {
            stopGeneratingAnimation()
        }
        val doc = currentWorkflow?.documents?.get(phase)
        if (doc != null) {
            renderPreviewMarkdown(doc.content)
            val vr = doc.validationResult
            if (isGeneratingActive && keepGeneratingIndicator) {
                updateGeneratingLabel()
            } else {
                if (vr != null) {
                    validationLabel.text = if (vr.valid) {
                        SpecCodingBundle.message("spec.detail.validation.passed")
                    } else {
                        SpecCodingBundle.message("spec.detail.validation.failed")
                    }
                    validationLabel.foreground = if (vr.valid)
                        JBColor(java.awt.Color(76, 175, 80), java.awt.Color(76, 175, 80))
                    else
                        JBColor(java.awt.Color(244, 67, 54), java.awt.Color(239, 83, 80))
                } else {
                    validationLabel.text = SpecCodingBundle.message("spec.detail.validation.none")
                    validationLabel.foreground = JBColor.GRAY
                }
            }
        } else {
            renderPreviewMarkdown(SpecCodingBundle.message("spec.detail.noDocumentForPhase", phaseDisplayText(phase)))
            if (isGeneratingActive && keepGeneratingIndicator) {
                updateGeneratingLabel()
            } else {
                validationLabel.text = ""
                validationLabel.foreground = JBColor.GRAY
            }
        }
    }

    private fun renderPreviewMarkdown(content: String) {
        previewSourceText = content
        runCatching {
            MarkdownRenderer.render(previewPane, content)
            previewPane.caretPosition = 0
        }.onFailure {
            previewPane.text = content
            previewPane.caretPosition = 0
        }
    }

    private fun startGeneratingAnimation() {
        if (generationAnimationTimer != null) return
        generationAnimationTimer = Timer(220) {
            generatingFrameIndex = (generatingFrameIndex + 1) % GENERATING_FRAMES.size
            updateGeneratingLabel()
        }.apply {
            isRepeats = true
            start()
        }
    }

    private fun stopGeneratingAnimation() {
        generationAnimationTimer?.stop()
        generationAnimationTimer = null
        generatingFrameIndex = 0
    }

    private fun updateGeneratingLabel() {
        val frame = GENERATING_FRAMES[generatingFrameIndex]
        validationLabel.text = "${SpecCodingBundle.message("spec.detail.generating.percent", generatingPercent)} $frame"
        validationLabel.foreground = GENERATING_FG
    }

    private fun updateButtonStates(workflow: SpecWorkflow) {
        val inProgress = workflow.status == WorkflowStatus.IN_PROGRESS
        val allowEditing = !isGeneratingActive
        generateButton.isEnabled = inProgress && !isEditing
        nextPhaseButton.isEnabled = workflow.canProceedToNext() && inProgress && !isEditing
        goBackButton.isEnabled = workflow.canGoBack() && inProgress && !isEditing
        completeButton.isEnabled = workflow.currentPhase == SpecPhase.IMPLEMENT
                && workflow.getDocument(SpecPhase.IMPLEMENT)?.validationResult?.valid == true
                && inProgress
                && !isEditing
        pauseResumeButton.isEnabled = !isEditing
        pauseResumeButton.text = if (workflow.status == WorkflowStatus.PAUSED) {
            SpecCodingBundle.message("spec.detail.resume")
        } else {
            SpecCodingBundle.message("spec.detail.pause")
        }
        styleActionButton(pauseResumeButton)
        openEditorButton.isEnabled = !isEditing && selectedPhase?.let { currentWorkflow?.documents?.containsKey(it) } == true
        historyDiffButton.isEnabled = !isEditing && selectedPhase?.let { currentWorkflow?.documents?.containsKey(it) } == true
        editButton.isVisible = !isEditing
        editButton.isEnabled = !isEditing && allowEditing
        saveButton.isVisible = isEditing
        saveButton.isEnabled = isEditing
        cancelEditButton.isVisible = isEditing
        cancelEditButton.isEnabled = isEditing
    }

    private fun disableAllButtons() {
        generateButton.isEnabled = false
        nextPhaseButton.isEnabled = false
        goBackButton.isEnabled = false
        completeButton.isEnabled = false
        pauseResumeButton.isEnabled = false
        openEditorButton.isEnabled = false
        historyDiffButton.isEnabled = false
        editButton.isEnabled = false
        saveButton.isEnabled = false
        cancelEditButton.isEnabled = false
    }

    internal fun currentPreviewTextForTest(): String {
        return previewSourceText
    }

    internal fun currentValidationTextForTest(): String {
        return validationLabel.text
    }

    internal fun buttonStatesForTest(): Map<String, Any> {
        return mapOf(
            "generateEnabled" to generateButton.isEnabled,
            "nextEnabled" to nextPhaseButton.isEnabled,
            "goBackEnabled" to goBackButton.isEnabled,
            "completeEnabled" to completeButton.isEnabled,
            "pauseResumeEnabled" to pauseResumeButton.isEnabled,
            "pauseResumeText" to pauseResumeButton.text,
            "openEditorEnabled" to openEditorButton.isEnabled,
            "historyDiffEnabled" to historyDiffButton.isEnabled,
        )
    }

    private data class PhaseNode(val phase: SpecPhase, val document: SpecDocument?) {
        override fun toString(): String {
            val status = when {
                document?.validationResult?.valid == true -> SpecCodingBundle.message("spec.detail.tree.status.done")
                document != null -> SpecCodingBundle.message("spec.detail.tree.status.draft")
                else -> ""
            }
            return if (status.isBlank()) {
                SpecCodingBundle.message("spec.detail.tree.node.noStatus", phase.displayName.lowercase(), phase.outputFileName)
            } else {
                SpecCodingBundle.message("spec.detail.tree.node.withStatus", phase.displayName.lowercase(), phase.outputFileName, status)
            }
        }
    }

    private inner class PhaseTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree?,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ): java.awt.Component {
            val component = super.getTreeCellRendererComponent(
                tree,
                value,
                selected,
                expanded,
                leaf,
                row,
                hasFocus,
            ) as DefaultTreeCellRenderer

            val node = value as? DefaultMutableTreeNode
            val phaseNode = node?.userObject as? PhaseNode
            if (phaseNode != null) {
                icon = when {
                    phaseNode.document?.validationResult?.valid == true -> AllIcons.General.InspectionsOK
                    else -> AllIcons.FileTypes.Text
                }
                foreground = if (selected) TREE_TEXT_SELECTED else TREE_TEXT
            } else {
                icon = null
            }
            border = JBUI.Borders.empty(1, 0, 1, 0)
            iconTextGap = JBUI.scale(6)
            return component
        }
    }

    private fun phaseDisplayText(phase: SpecPhase): String = phase.displayName.lowercase()

    companion object {
        private val GENERATING_FRAMES = listOf("◐", "◓", "◑", "◒")
        private val PANEL_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val PANEL_BORDER = JBColor(Color(205, 216, 234), Color(84, 92, 106))
        private val STATUS_BG = JBColor(Color(235, 244, 255), Color(62, 68, 80))
        private val STATUS_BORDER = JBColor(Color(183, 199, 224), Color(98, 109, 125))
        private val GENERATING_FG = JBColor(Color(46, 90, 162), Color(171, 201, 248))
        private val TREE_TEXT = JBColor(Color(34, 54, 88), Color(214, 224, 238))
        private val TREE_TEXT_SELECTED = JBColor(Color(24, 43, 75), Color(235, 242, 252))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private const val CARD_PREVIEW = "preview"
        private const val CARD_EDIT = "edit"
    }
}
