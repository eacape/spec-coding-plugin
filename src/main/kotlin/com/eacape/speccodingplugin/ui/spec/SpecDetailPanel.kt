package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.ValidationResult
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.eacape.speccodingplugin.ui.chat.MarkdownRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
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
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer

class SpecDetailPanel(
    private val onGenerate: (String) -> Unit,
    private val onClarificationConfirm: (String, String) -> Unit,
    private val onClarificationRegenerate: (String, String) -> Unit,
    private val onClarificationSkip: (String) -> Unit,
    private val onClarificationCancel: () -> Unit,
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
    private val clarificationQuestionsPane = JTextPane()
    private val clarificationPreviewPane = JTextPane()
    private val previewCardLayout = CardLayout()
    private val previewCardPanel = JPanel(previewCardLayout)
    private val previewModePanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
    private val previewModeButton = JButton(SpecCodingBundle.message("spec.detail.view.preview"))
    private val clarificationModeButton = JButton(SpecCodingBundle.message("spec.detail.view.clarify"))
    private val clarificationQuestionsLabel = JBLabel(SpecCodingBundle.message("spec.detail.clarify.questions.title"))
    private val clarificationPreviewLabel = JBLabel(SpecCodingBundle.message("spec.detail.clarify.preview.title"))
    private val editorArea = JBTextArea(14, 40)
    private val validationLabel = JBLabel("")
    private val inputArea = JBTextArea(3, 40)
    private lateinit var clarificationSplitPane: JSplitPane
    private lateinit var clarificationPreviewSection: JPanel

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
    private val confirmGenerateButton = JButton(SpecCodingBundle.message("spec.detail.clarify.confirmGenerate"))
    private val regenerateClarificationButton = JButton(SpecCodingBundle.message("spec.detail.clarify.regenerate"))
    private val skipClarificationButton = JButton(SpecCodingBundle.message("spec.detail.clarify.skip"))
    private val cancelClarificationButton = JButton(SpecCodingBundle.message("spec.detail.clarify.cancel"))

    private var currentWorkflow: SpecWorkflow? = null
    private var selectedPhase: SpecPhase? = null
    private var previewSourceText: String = ""
    private var generatingPercent: Int = 0
    private var generatingFrameIndex: Int = 0
    private var isGeneratingActive: Boolean = false
    private var isClarificationGenerating: Boolean = false
    private var generationAnimationTimer: Timer? = null

    private var isEditing: Boolean = false
    private var editingPhase: SpecPhase? = null
    private var activePreviewCard: String = CARD_PREVIEW
    private var clarificationState: ClarificationState? = null

    init {
        setupUI()
    }

    private data class ClarificationState(
        val phase: SpecPhase,
        val input: String,
        val questionsMarkdown: String,
    )

    private fun setupUI() {
        border = JBUI.Borders.empty(2)

        val topSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            dividerLocation = JBUI.scale(252)
            isContinuousLayout = true
            border = JBUI.Borders.empty()
            background = PANEL_BG
            SpecUiStyle.applySplitPaneDivider(this, dividerSize = JBUI.scale(8))
        }

        documentTree.isRootVisible = false
        documentTree.showsRootHandles = false
        documentTree.rowHeight = JBUI.scale(48)
        documentTree.border = JBUI.Borders.empty(8, 6, 8, 6)
        documentTree.cellRenderer = PhaseTreeCellRenderer()
        documentTree.putClientProperty("JTree.lineStyle", "None")
        documentTree.isOpaque = false
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
                viewport.isOpaque = false
                isOpaque = false
            },
            backgroundColor = TREE_SECTION_BG,
            borderColor = TREE_SECTION_BORDER,
        )

        val previewPanel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = true
            background = PREVIEW_COLUMN_BG
        }
        previewPane.isEditable = false
        previewPane.isOpaque = false
        previewPane.border = JBUI.Borders.empty(2, 2)

        previewCardPanel.isOpaque = false
        previewCardPanel.add(
            createSectionContainer(
                JBScrollPane(previewPane).apply {
                    border = JBUI.Borders.empty()
                },
                backgroundColor = PREVIEW_SECTION_BG,
                borderColor = PREVIEW_SECTION_BORDER,
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
                },
                backgroundColor = PREVIEW_SECTION_BG,
                borderColor = PREVIEW_SECTION_BORDER,
            ),
            CARD_EDIT,
        )
        previewCardPanel.add(createClarificationCard(), CARD_CLARIFY)
        switchPreviewCard(CARD_PREVIEW)
        configurePreviewModePanel()
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
            isOpaque = true
            background = INPUT_COLUMN_BG
            border = JBUI.Borders.emptyTop(8)
        }

        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = updateClarificationPreview()
                override fun removeUpdate(e: DocumentEvent?) = updateClarificationPreview()
                override fun changedUpdate(e: DocumentEvent?) = updateClarificationPreview()
            },
        )
        updateInputPlaceholder(null)
        val inputScroll = JBScrollPane(inputArea)
        inputScroll.border = JBUI.Borders.empty()
        inputScroll.preferredSize = java.awt.Dimension(0, JBUI.scale(80))
        bottomPanel.add(
            createSectionContainer(
                inputScroll,
                backgroundColor = INPUT_SECTION_BG,
                borderColor = INPUT_SECTION_BORDER,
            ),
            BorderLayout.CENTER,
        )

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
                backgroundColor = ACTIONS_SECTION_BG,
                borderColor = ACTIONS_SECTION_BORDER,
            ),
            BorderLayout.SOUTH,
        )

        val mainSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            topComponent = topSplit
            bottomComponent = bottomPanel
            resizeWeight = 0.67
            border = JBUI.Borders.empty()
            background = PANEL_BG
            SpecUiStyle.applySplitPaneDivider(this, dividerSize = JBUI.scale(8))
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
        styleActionButton(confirmGenerateButton)
        styleActionButton(regenerateClarificationButton)
        styleActionButton(skipClarificationButton)
        styleActionButton(cancelClarificationButton)
        saveButton.isVisible = false
        cancelEditButton.isVisible = false
        confirmGenerateButton.isVisible = false
        regenerateClarificationButton.isVisible = false
        skipClarificationButton.isVisible = false
        cancelClarificationButton.isVisible = false

        generateButton.addActionListener {
            val text = inputArea.text.trim()
            val phase = currentWorkflow?.currentPhase
            val allowBlank = phase == SpecPhase.DESIGN || phase == SpecPhase.IMPLEMENT
            if (text.isNotBlank() || allowBlank) {
                onGenerate(text)
                clearInput()
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
        confirmGenerateButton.addActionListener {
            val state = clarificationState ?: return@addActionListener
            val confirmed = normalizeContent(inputArea.text)
            if (confirmed.isBlank()) {
                validationLabel.text = SpecCodingBundle.message("spec.detail.clarify.detailsRequired")
                validationLabel.foreground = JBColor(Color(213, 52, 52), Color(255, 140, 140))
                return@addActionListener
            }
            onClarificationConfirm(state.input, confirmed)
        }
        regenerateClarificationButton.addActionListener {
            val state = clarificationState ?: return@addActionListener
            onClarificationRegenerate(state.input, normalizeContent(inputArea.text))
        }
        skipClarificationButton.addActionListener {
            val state = clarificationState ?: return@addActionListener
            onClarificationSkip(state.input)
        }
        cancelClarificationButton.addActionListener {
            exitClarificationMode(clearInput = false)
            onClarificationCancel()
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
        panel.add(confirmGenerateButton)
        panel.add(regenerateClarificationButton)
        panel.add(skipClarificationButton)
        panel.add(cancelClarificationButton)

        disableAllButtons()
    }

    private fun configurePreviewModePanel() {
        previewModePanel.isOpaque = false
        styleActionButton(previewModeButton)
        styleActionButton(clarificationModeButton)
        previewModeButton.toolTipText = SpecCodingBundle.message("spec.detail.view.preview.tooltip")
        clarificationModeButton.toolTipText = SpecCodingBundle.message("spec.detail.view.clarify.tooltip")
        previewModeButton.addActionListener {
            if (isEditing) {
                switchPreviewCard(CARD_EDIT)
            } else {
                switchPreviewCard(CARD_PREVIEW)
            }
        }
        clarificationModeButton.addActionListener {
            if (clarificationState != null) {
                switchPreviewCard(CARD_CLARIFY)
            } else {
                showClarificationEntryHint()
            }
        }
        previewModePanel.add(previewModeButton)
        previewModePanel.add(clarificationModeButton)
        updatePreviewModeButtons()
    }

    private fun createClarificationCard(): JPanel {
        clarificationQuestionsPane.isEditable = false
        clarificationQuestionsPane.isOpaque = false
        clarificationQuestionsPane.border = JBUI.Borders.empty(2, 2)
        styleClarificationSectionLabel(clarificationQuestionsLabel)

        clarificationPreviewPane.isEditable = false
        clarificationPreviewPane.isOpaque = false
        clarificationPreviewPane.border = JBUI.Borders.empty(2, 2)
        styleClarificationSectionLabel(clarificationPreviewLabel)

        val questionsSection = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(clarificationQuestionsLabel, BorderLayout.NORTH)
            add(
                createSectionContainer(
                    JBScrollPane(clarificationQuestionsPane).apply {
                        border = JBUI.Borders.empty()
                    },
                    backgroundColor = CLARIFICATION_QUESTIONS_BG,
                    borderColor = CLARIFICATION_QUESTIONS_BORDER,
                ),
                BorderLayout.CENTER,
            )
        }

        clarificationPreviewSection = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(clarificationPreviewLabel, BorderLayout.NORTH)
            add(
                createSectionContainer(
                    JBScrollPane(clarificationPreviewPane).apply {
                        border = JBUI.Borders.empty()
                    },
                    backgroundColor = CLARIFICATION_PREVIEW_BG,
                    borderColor = CLARIFICATION_PREVIEW_BORDER,
                ),
                BorderLayout.CENTER,
            )
        }

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = true
            background = CLARIFICATION_CARD_BG
            clarificationSplitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
                topComponent = questionsSection
                bottomComponent = clarificationPreviewSection
                resizeWeight = 0.58
                border = JBUI.Borders.empty()
                background = PANEL_BG
                SpecUiStyle.applySplitPaneDivider(this, dividerSize = JBUI.scale(8))
            }
            add(clarificationSplitPane, BorderLayout.CENTER)
        }
    }

    private fun styleClarificationSectionLabel(label: JBLabel) {
        label.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        label.foreground = SECTION_TITLE_FG
        label.border = JBUI.Borders.empty(0, 2, 2, 2)
    }

    private fun switchPreviewCard(card: String) {
        activePreviewCard = card
        previewCardLayout.show(previewCardPanel, card)
        updatePreviewModeButtons()
    }

    private fun updatePreviewModeButtons() {
        val clarifying = clarificationState != null
        clarificationModeButton.isEnabled = !isEditing
        val previewSelected = activePreviewCard != CARD_CLARIFY
        applyPreviewModeStyle(previewModeButton, selected = previewSelected)
        applyPreviewModeStyle(clarificationModeButton, selected = !previewSelected && clarifying)
    }

    private fun applyPreviewModeStyle(button: JButton, selected: Boolean) {
        button.background = if (selected) BUTTON_BG else STATUS_BG
        button.foreground = if (selected) BUTTON_FG else TREE_TEXT
    }

    private fun showClarificationEntryHint() {
        validationLabel.text = SpecCodingBundle.message("spec.detail.clarify.entry.hint")
        validationLabel.foreground = GENERATING_FG
    }

    private fun createSectionContainer(
        content: java.awt.Component,
        backgroundColor: Color = PANEL_BG,
        borderColor: Color = PANEL_BORDER,
    ): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = backgroundColor
            border = SpecUiStyle.roundedCardBorder(
                lineColor = borderColor,
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
        previewModeButton.text = SpecCodingBundle.message("spec.detail.view.preview")
        clarificationModeButton.text = SpecCodingBundle.message("spec.detail.view.clarify")
        previewModeButton.toolTipText = SpecCodingBundle.message("spec.detail.view.preview.tooltip")
        clarificationModeButton.toolTipText = SpecCodingBundle.message("spec.detail.view.clarify.tooltip")
        clarificationQuestionsLabel.text = SpecCodingBundle.message("spec.detail.clarify.questions.title")
        clarificationPreviewLabel.text = SpecCodingBundle.message("spec.detail.clarify.preview.title")
        generateButton.text = SpecCodingBundle.message("spec.detail.generate")
        nextPhaseButton.text = SpecCodingBundle.message("spec.detail.nextPhase")
        goBackButton.text = SpecCodingBundle.message("spec.detail.goBack")
        completeButton.text = SpecCodingBundle.message("spec.detail.complete")
        openEditorButton.text = SpecCodingBundle.message("spec.detail.openInEditor")
        historyDiffButton.text = SpecCodingBundle.message("spec.detail.historyDiff")
        editButton.text = SpecCodingBundle.message("spec.detail.edit")
        saveButton.text = SpecCodingBundle.message("spec.detail.save")
        cancelEditButton.text = SpecCodingBundle.message("spec.detail.cancel")
        confirmGenerateButton.text = SpecCodingBundle.message("spec.detail.clarify.confirmGenerate")
        regenerateClarificationButton.text = SpecCodingBundle.message("spec.detail.clarify.regenerate")
        skipClarificationButton.text = SpecCodingBundle.message("spec.detail.clarify.skip")
        cancelClarificationButton.text = SpecCodingBundle.message("spec.detail.clarify.cancel")
        updateInputPlaceholder(currentWorkflow?.currentPhase)
        pauseResumeButton.text = if (currentWorkflow?.status == WorkflowStatus.PAUSED) {
            SpecCodingBundle.message("spec.detail.resume")
        } else {
            SpecCodingBundle.message("spec.detail.pause")
        }
        styleActionButton(previewModeButton)
        styleActionButton(clarificationModeButton)
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
        styleActionButton(confirmGenerateButton)
        styleActionButton(regenerateClarificationButton)
        styleActionButton(skipClarificationButton)
        styleActionButton(cancelClarificationButton)
        updatePreviewModeButtons()
        if (isClarificationGenerating) {
            renderClarificationQuestions(SpecCodingBundle.message("spec.workflow.clarify.generating"))
        } else {
            clarificationState?.let { renderClarificationQuestions(it.questionsMarkdown) }
        }
        setClarificationPreviewVisible(!isClarificationGenerating)
        updateClarificationPreview()
        if (currentWorkflow == null) {
            validationLabel.text = SpecCodingBundle.message("spec.detail.noWorkflow")
        } else {
            selectedPhase?.let { showDocumentPreview(it) }
        }
    }

    fun updateWorkflow(workflow: SpecWorkflow?, followCurrentPhase: Boolean = false) {
        val previousWorkflowId = currentWorkflow?.id
        currentWorkflow = workflow
        if (workflow == null) {
            showEmpty()
            return
        }
        if (previousWorkflowId != workflow.id) {
            clarificationState = null
        }
        if (clarificationState?.phase != null && clarificationState?.phase != workflow.currentPhase) {
            clarificationState = null
        }
        isGeneratingActive = false
        isClarificationGenerating = false
        stopGeneratingAnimation()
        if (followCurrentPhase) {
            clearInput()
        }
        rebuildTree(workflow)
        updateInputPlaceholder(workflow.currentPhase)
        val preservedPhase = selectedPhase?.takeIf { !followCurrentPhase && previousWorkflowId == workflow.id }
        selectedPhase = preservedPhase ?: workflow.currentPhase
        updateTreeSelection(selectedPhase)
        updateButtonStates(workflow)
        if (clarificationState == null) {
            setClarificationPreviewVisible(true)
            switchPreviewCard(CARD_PREVIEW)
            showDocumentPreview(selectedPhase ?: workflow.currentPhase, keepGeneratingIndicator = false)
        } else {
            setClarificationPreviewVisible(!isClarificationGenerating)
            updateClarificationPreview()
            switchPreviewCard(CARD_CLARIFY)
        }
    }

    fun showEmpty() {
        isGeneratingActive = false
        isClarificationGenerating = false
        stopGeneratingAnimation()
        isEditing = false
        editingPhase = null
        selectedPhase = null
        clarificationState = null
        treeRoot.removeAllChildren()
        treeModel.reload()
        setClarificationPreviewVisible(true)
        switchPreviewCard(CARD_PREVIEW)
        documentTree.isEnabled = true
        previewSourceText = ""
        previewPane.text = ""
        clarificationQuestionsPane.text = ""
        clarificationPreviewPane.text = ""
        validationLabel.text = SpecCodingBundle.message("spec.detail.noWorkflow")
        validationLabel.foreground = JBColor.GRAY
        inputArea.isEnabled = true
        updateInputPlaceholder(null)
        generateButton.isVisible = true
        nextPhaseButton.isVisible = true
        goBackButton.isVisible = true
        completeButton.isVisible = true
        pauseResumeButton.isVisible = true
        openEditorButton.isVisible = true
        historyDiffButton.isVisible = true
        editButton.isVisible = true
        saveButton.isVisible = false
        cancelEditButton.isVisible = false
        confirmGenerateButton.isVisible = false
        regenerateClarificationButton.isVisible = false
        skipClarificationButton.isVisible = false
        cancelClarificationButton.isVisible = false
        disableAllButtons()
    }

    private fun updateInputPlaceholder(currentPhase: SpecPhase?) {
        val key = when {
            clarificationState != null -> "spec.detail.clarify.input.placeholder"
            currentPhase == SpecPhase.DESIGN -> "spec.detail.input.placeholder.design"
            currentPhase == SpecPhase.IMPLEMENT -> "spec.detail.input.placeholder.implement"
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
        if (isEditing || clarificationState != null) return
        val workflow = currentWorkflow ?: return
        val phase = selectedPhase ?: workflow.currentPhase
        val document = workflow.getDocument(phase)
        isEditing = true
        editingPhase = phase
        editorArea.text = document?.content.orEmpty()
        editorArea.caretPosition = 0
        switchPreviewCard(CARD_EDIT)
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
        switchPreviewCard(CARD_PREVIEW)
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

    fun showClarificationGenerating(
        phase: SpecPhase,
        input: String,
        suggestedDetails: String = input,
    ) {
        val generatingText = SpecCodingBundle.message("spec.workflow.clarify.generating")
        clarificationState = ClarificationState(
            phase = phase,
            input = input,
            questionsMarkdown = generatingText,
        )
        renderClarificationQuestions(generatingText)
        inputArea.text = suggestedDetails
        inputArea.caretPosition = 0
        updateInputPlaceholder(phase)
        setClarificationPreviewVisible(false)
        switchPreviewCard(CARD_CLARIFY)
        updateClarificationPreview()

        generatingPercent = 0
        isClarificationGenerating = true
        isGeneratingActive = true
        startGeneratingAnimation()
        updateGeneratingLabel()
        currentWorkflow?.let { updateButtonStates(it) } ?: disableAllButtons()
    }

    fun showClarificationDraft(
        phase: SpecPhase,
        input: String,
        questionsMarkdown: String,
        suggestedDetails: String = input,
    ) {
        isGeneratingActive = false
        isClarificationGenerating = false
        stopGeneratingAnimation()
        clarificationState = ClarificationState(
            phase = phase,
            input = input,
            questionsMarkdown = questionsMarkdown,
        )
        renderClarificationQuestions(questionsMarkdown)
        inputArea.text = suggestedDetails
        inputArea.caretPosition = 0
        updateInputPlaceholder(phase)
        setClarificationPreviewVisible(true)
        switchPreviewCard(CARD_CLARIFY)
        updateClarificationPreview()
        validationLabel.text = SpecCodingBundle.message("spec.workflow.clarify.hint")
        validationLabel.foreground = TREE_TEXT
        currentWorkflow?.let { updateButtonStates(it) } ?: disableAllButtons()
    }

    fun exitClarificationMode(clearInput: Boolean = false) {
        isGeneratingActive = false
        isClarificationGenerating = false
        stopGeneratingAnimation()
        clarificationState = null
        clarificationQuestionsPane.text = ""
        clarificationPreviewPane.text = ""
        setClarificationPreviewVisible(true)
        updateInputPlaceholder(currentWorkflow?.currentPhase)
        if (clearInput) {
            inputArea.text = ""
        }
        if (isEditing) {
            switchPreviewCard(CARD_EDIT)
        } else {
            switchPreviewCard(CARD_PREVIEW)
        }
        currentWorkflow?.let { updateButtonStates(it) } ?: disableAllButtons()
    }

    private fun renderClarificationQuestions(markdown: String) {
        val content = markdown.ifBlank {
            SpecCodingBundle.message("spec.workflow.clarify.noQuestions")
        }
        runCatching {
            MarkdownRenderer.render(clarificationQuestionsPane, content)
            clarificationQuestionsPane.caretPosition = 0
        }.onFailure {
            clarificationQuestionsPane.text = content
            clarificationQuestionsPane.caretPosition = 0
        }
    }

    private fun updateClarificationPreview() {
        if (clarificationState == null) {
            return
        }
        val content = inputArea.text.trim().ifBlank {
            SpecCodingBundle.message("spec.detail.clarify.preview.empty")
        }
        runCatching {
            MarkdownRenderer.render(clarificationPreviewPane, content)
            clarificationPreviewPane.caretPosition = 0
        }.onFailure {
            clarificationPreviewPane.text = content
            clarificationPreviewPane.caretPosition = 0
        }
    }

    private fun setClarificationPreviewVisible(visible: Boolean) {
        if (!::clarificationSplitPane.isInitialized) {
            return
        }
        if (visible) {
            if (clarificationSplitPane.bottomComponent == null) {
                clarificationSplitPane.bottomComponent = clarificationPreviewSection
            }
            clarificationPreviewSection.isVisible = true
            clarificationSplitPane.resizeWeight = 0.58
            clarificationSplitPane.dividerSize = JBUI.scale(8)
        } else {
            clarificationPreviewSection.isVisible = false
            clarificationSplitPane.bottomComponent = null
            clarificationSplitPane.resizeWeight = 1.0
            clarificationSplitPane.dividerSize = 0
        }
        clarificationSplitPane.revalidate()
        clarificationSplitPane.repaint()
    }

    fun showGenerating(progress: Double) {
        generatingPercent = (progress * 100).toInt().coerceIn(0, 100)
        isClarificationGenerating = false
        isGeneratingActive = true
        startGeneratingAnimation()
        updateGeneratingLabel()
        currentWorkflow?.let { updateButtonStates(it) }
    }

    fun showGenerationFailed() {
        isGeneratingActive = false
        isClarificationGenerating = false
        stopGeneratingAnimation()
        val workflow = currentWorkflow
        if (workflow == null) {
            validationLabel.text = SpecCodingBundle.message("spec.detail.validation.none")
            validationLabel.foreground = JBColor.GRAY
            return
        }
        updateButtonStates(workflow)
        if (clarificationState != null) {
            setClarificationPreviewVisible(!isClarificationGenerating)
            switchPreviewCard(CARD_CLARIFY)
            updateClarificationPreview()
        } else {
            val phase = selectedPhase ?: workflow.currentPhase
            showDocumentPreview(phase, keepGeneratingIndicator = false)
        }
    }

    fun showValidationFailureInteractive(
        phase: SpecPhase,
        validation: ValidationResult,
    ) {
        isGeneratingActive = false
        isClarificationGenerating = false
        stopGeneratingAnimation()
        renderPreviewMarkdown(buildValidationIssuesMarkdown(phase, validation))
        switchPreviewCard(CARD_PREVIEW)
        validationLabel.text = buildValidationFailureLabel(validation)
        validationLabel.foreground = JBColor(java.awt.Color(244, 67, 54), java.awt.Color(239, 83, 80))
        if (inputArea.text.isBlank()) {
            inputArea.text = buildValidationRepairTemplate(validation)
            inputArea.caretPosition = inputArea.text.length
        }
        currentWorkflow?.let { updateButtonStates(it) } ?: disableAllButtons()
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

    private fun buildValidationIssuesMarkdown(
        phase: SpecPhase,
        validation: ValidationResult,
    ): String {
        return buildString {
            appendLine("## ${SpecCodingBundle.message("spec.detail.validation.issues.title", phase.displayName)}")
            appendLine()
            if (validation.errors.isNotEmpty()) {
                appendLine("### ${SpecCodingBundle.message("spec.detail.validation.issues.errors")}")
                validation.errors.forEach { appendLine("- $it") }
                appendLine()
            }
            if (validation.warnings.isNotEmpty()) {
                appendLine("### ${SpecCodingBundle.message("spec.detail.validation.issues.warnings")}")
                validation.warnings.forEach { appendLine("- $it") }
                appendLine()
            }
            if (validation.suggestions.isNotEmpty()) {
                appendLine("### ${SpecCodingBundle.message("spec.detail.validation.issues.suggestions")}")
                validation.suggestions.forEach { appendLine("- $it") }
                appendLine()
            }
            appendLine(SpecCodingBundle.message("spec.detail.validation.issues.next"))
        }.trimEnd()
    }

    private fun buildValidationFailureLabel(validation: ValidationResult): String {
        val firstError = validation.errors.firstOrNull()
        if (firstError.isNullOrBlank()) {
            return SpecCodingBundle.message("spec.detail.validation.failed")
        }
        return if (validation.errors.size > 1) {
            SpecCodingBundle.message(
                "spec.detail.validation.failed.withMore",
                firstError,
                validation.errors.size - 1,
            )
        } else {
            SpecCodingBundle.message("spec.detail.validation.failed.withReason", firstError)
        }
    }

    private fun buildValidationRepairTemplate(validation: ValidationResult): String {
        val issues = validation.errors
            .ifEmpty { listOf(SpecCodingBundle.message("common.unknown")) }
            .joinToString("\n") { "- $it" }
        return SpecCodingBundle.message("spec.detail.validation.repair.template", issues)
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
        val text = if (isClarificationGenerating) {
            SpecCodingBundle.message("spec.workflow.clarify.generating")
        } else {
            SpecCodingBundle.message("spec.detail.generating.percent", generatingPercent)
        }
        validationLabel.text = "$text $frame"
        validationLabel.foreground = GENERATING_FG
    }

    private fun updateButtonStates(workflow: SpecWorkflow) {
        val inProgress = workflow.status == WorkflowStatus.IN_PROGRESS
        val allowEditing = !isGeneratingActive
        val clarifying = clarificationState != null
        val standardModeEnabled = inProgress && !isEditing && !clarifying

        generateButton.isVisible = !clarifying
        nextPhaseButton.isVisible = !clarifying
        goBackButton.isVisible = !clarifying
        completeButton.isVisible = !clarifying
        pauseResumeButton.isVisible = !clarifying
        openEditorButton.isVisible = !clarifying
        historyDiffButton.isVisible = !clarifying
        editButton.isVisible = !clarifying && !isEditing
        saveButton.isVisible = !clarifying && isEditing
        cancelEditButton.isVisible = !clarifying && isEditing

        confirmGenerateButton.isVisible = clarifying
        regenerateClarificationButton.isVisible = clarifying
        skipClarificationButton.isVisible = clarifying
        cancelClarificationButton.isVisible = clarifying

        generateButton.isEnabled = standardModeEnabled
        nextPhaseButton.isEnabled = workflow.canProceedToNext() && standardModeEnabled
        goBackButton.isEnabled = workflow.canGoBack() && standardModeEnabled
        completeButton.isEnabled = workflow.currentPhase == SpecPhase.IMPLEMENT
                && workflow.getDocument(SpecPhase.IMPLEMENT)?.validationResult?.valid == true
                && standardModeEnabled
        pauseResumeButton.isEnabled = !isEditing
        pauseResumeButton.text = if (workflow.status == WorkflowStatus.PAUSED) {
            SpecCodingBundle.message("spec.detail.resume")
        } else {
            SpecCodingBundle.message("spec.detail.pause")
        }
        styleActionButton(pauseResumeButton)
        openEditorButton.isEnabled = !isEditing && !clarifying && selectedPhase?.let { currentWorkflow?.documents?.containsKey(it) } == true
        historyDiffButton.isEnabled = !isEditing && !clarifying && selectedPhase?.let { currentWorkflow?.documents?.containsKey(it) } == true
        editButton.isEnabled = !isEditing && allowEditing && !clarifying
        saveButton.isEnabled = isEditing
        cancelEditButton.isEnabled = isEditing
        confirmGenerateButton.isEnabled = clarifying && inProgress && !isGeneratingActive
        regenerateClarificationButton.isEnabled = clarifying && inProgress && !isGeneratingActive
        skipClarificationButton.isEnabled = clarifying && inProgress && !isGeneratingActive
        cancelClarificationButton.isEnabled = clarifying && !isGeneratingActive
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
        confirmGenerateButton.isEnabled = false
        regenerateClarificationButton.isEnabled = false
        skipClarificationButton.isEnabled = false
        cancelClarificationButton.isEnabled = false
    }

    fun clearInput() {
        inputArea.text = ""
        inputArea.caretPosition = 0
        if (clarificationState != null) {
            updateClarificationPreview()
        }
    }

    internal fun currentPreviewTextForTest(): String {
        return previewSourceText
    }

    internal fun currentValidationTextForTest(): String {
        return validationLabel.text
    }

    internal fun currentInputTextForTest(): String {
        return inputArea.text
    }

    internal fun setInputTextForTest(text: String) {
        inputArea.text = text
        inputArea.caretPosition = inputArea.text.length
    }

    internal fun clickGenerateForTest() {
        generateButton.doClick()
    }

    internal fun isClarificationPreviewVisibleForTest(): Boolean {
        return ::clarificationSplitPane.isInitialized &&
            clarificationSplitPane.bottomComponent != null &&
            clarificationPreviewSection.isVisible
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
            "confirmGenerateEnabled" to confirmGenerateButton.isEnabled,
            "regenerateClarificationEnabled" to regenerateClarificationButton.isEnabled,
            "skipClarificationEnabled" to skipClarificationButton.isEnabled,
            "cancelClarificationEnabled" to cancelClarificationButton.isEnabled,
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

    private inner class PhaseTreeCellRenderer : JPanel(BorderLayout()), TreeCellRenderer {
        private val accentStrip = JPanel()
        private val headerRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        private val phaseLabel = JBLabel()
        private val statusInlineLabel = JBLabel()
        private val fileLabel = JBLabel()

        init {
            isOpaque = true
            accentStrip.isOpaque = true
            accentStrip.preferredSize = JBUI.size(2, 0)
            accentStrip.minimumSize = JBUI.size(2, 0)

            phaseLabel.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            phaseLabel.border = JBUI.Borders.empty()
            statusInlineLabel.font = JBUI.Fonts.smallFont().deriveFont(Font.PLAIN)
            statusInlineLabel.isOpaque = false
            fileLabel.font = JBUI.Fonts.smallFont()

            headerRow.isOpaque = false
            headerRow.add(phaseLabel)
            headerRow.add(statusInlineLabel)

            val content = JPanel(BorderLayout(0, JBUI.scale(3))).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 12, 6, 12)
                add(headerRow, BorderLayout.NORTH)
                add(fileLabel, BorderLayout.CENTER)
            }

            add(accentStrip, BorderLayout.WEST)
            add(content, BorderLayout.CENTER)
        }

        override fun getTreeCellRendererComponent(
            tree: JTree?,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ): Component {
            val phaseNode = (value as? DefaultMutableTreeNode)?.userObject as? PhaseNode
            if (phaseNode == null) {
                border = JBUI.Borders.empty()
                background = TREE_ROW_BG_NEUTRAL
                phaseLabel.text = ""
                fileLabel.text = ""
                statusInlineLabel.text = ""
                return this
            }

            val phase = phaseNode.phase
            val document = phaseNode.document
            val status = when {
                document?.validationResult?.valid == true -> PhaseDocStatus.DONE
                document != null -> PhaseDocStatus.DRAFT
                else -> PhaseDocStatus.PENDING
            }
            val badgeColor = phaseBadgeColor(phase, selected)

            phaseLabel.text = phase.displayName.lowercase()
            phaseLabel.foreground = badgeColor
            statusInlineLabel.text = "· ${status.badgeText}"
            statusInlineLabel.foreground = statusTextColor(status, selected)
            fileLabel.text = phase.outputFileName
            fileLabel.foreground = if (selected) TREE_FILE_TEXT_SELECTED else TREE_FILE_TEXT

            accentStrip.background = badgeColor
            background = phaseRowBackground(phase, selected)
            border = SpecUiStyle.roundedCardBorder(
                lineColor = phaseRowBorder(phase, selected),
                arc = JBUI.scale(10),
                top = 1,
                left = 1,
                bottom = 1,
                right = 1,
            )
            return this
        }
    }

    private enum class PhaseDocStatus(
        val badgeTextKey: String,
    ) {
        DONE(
            badgeTextKey = "spec.detail.tree.badge.done",
        ),
        DRAFT(
            badgeTextKey = "spec.detail.tree.badge.draft",
        ),
        PENDING(
            badgeTextKey = "spec.detail.tree.badge.pending",
        ),
        ;

        val badgeText: String
            get() = SpecCodingBundle.message(badgeTextKey)
    }

    private fun phaseBadgeColor(phase: SpecPhase, selected: Boolean): Color {
        return when (phase) {
            SpecPhase.SPECIFY -> if (selected) TREE_PHASE_SPECIFY_SELECTED else TREE_PHASE_SPECIFY
            SpecPhase.DESIGN -> if (selected) TREE_PHASE_DESIGN_SELECTED else TREE_PHASE_DESIGN
            SpecPhase.IMPLEMENT -> if (selected) TREE_PHASE_IMPLEMENT_SELECTED else TREE_PHASE_IMPLEMENT
        }
    }

    private fun phaseRowBackground(phase: SpecPhase, selected: Boolean): Color {
        return when (phase) {
            SpecPhase.SPECIFY -> if (selected) TREE_ROW_SPECIFY_BG_SELECTED else TREE_ROW_SPECIFY_BG
            SpecPhase.DESIGN -> if (selected) TREE_ROW_DESIGN_BG_SELECTED else TREE_ROW_DESIGN_BG
            SpecPhase.IMPLEMENT -> if (selected) TREE_ROW_IMPLEMENT_BG_SELECTED else TREE_ROW_IMPLEMENT_BG
        }
    }

    private fun phaseRowBorder(phase: SpecPhase, selected: Boolean): Color {
        return when (phase) {
            SpecPhase.SPECIFY -> if (selected) TREE_ROW_SPECIFY_BORDER_SELECTED else TREE_ROW_SPECIFY_BORDER
            SpecPhase.DESIGN -> if (selected) TREE_ROW_DESIGN_BORDER_SELECTED else TREE_ROW_DESIGN_BORDER
            SpecPhase.IMPLEMENT -> if (selected) TREE_ROW_IMPLEMENT_BORDER_SELECTED else TREE_ROW_IMPLEMENT_BORDER
        }
    }

    private fun statusTextColor(status: PhaseDocStatus, selected: Boolean): Color {
        return when (status) {
            PhaseDocStatus.DONE -> if (selected) TREE_STATUS_DONE_TEXT_SELECTED else TREE_STATUS_DONE_TEXT
            PhaseDocStatus.DRAFT -> if (selected) TREE_STATUS_DRAFT_TEXT_SELECTED else TREE_STATUS_DRAFT_TEXT
            PhaseDocStatus.PENDING -> if (selected) TREE_STATUS_PENDING_TEXT_SELECTED else TREE_STATUS_PENDING_TEXT
        }
    }

    private fun phaseDisplayText(phase: SpecPhase): String = phase.displayName.lowercase()

    companion object {
        private val GENERATING_FRAMES = listOf("◐", "◓", "◑", "◒")
        private val PANEL_BG = JBColor(Color(250, 252, 255), Color(51, 56, 64))
        private val PANEL_BORDER = JBColor(Color(205, 216, 234), Color(84, 92, 106))
        private val TREE_SECTION_BG = JBColor(Color(246, 249, 253), Color(60, 67, 78))
        private val TREE_SECTION_BORDER = JBColor(Color(214, 223, 236), Color(92, 103, 121))
        private val PREVIEW_COLUMN_BG = JBColor(Color(244, 249, 255), Color(55, 61, 71))
        private val PREVIEW_SECTION_BG = JBColor(Color(250, 253, 255), Color(49, 55, 64))
        private val PREVIEW_SECTION_BORDER = JBColor(Color(204, 217, 236), Color(83, 93, 109))
        private val INPUT_COLUMN_BG = JBColor(Color(239, 245, 253), Color(58, 65, 75))
        private val INPUT_SECTION_BG = JBColor(Color(247, 251, 255), Color(52, 58, 68))
        private val INPUT_SECTION_BORDER = JBColor(Color(200, 214, 234), Color(86, 97, 113))
        private val ACTIONS_SECTION_BG = JBColor(Color(236, 244, 255), Color(63, 70, 81))
        private val ACTIONS_SECTION_BORDER = JBColor(Color(190, 208, 234), Color(95, 108, 126))
        private val CLARIFICATION_CARD_BG = JBColor(Color(244, 249, 255), Color(56, 62, 72))
        private val CLARIFICATION_QUESTIONS_BG = JBColor(Color(249, 252, 255), Color(50, 56, 65))
        private val CLARIFICATION_QUESTIONS_BORDER = JBColor(Color(203, 216, 235), Color(84, 95, 110))
        private val CLARIFICATION_PREVIEW_BG = JBColor(Color(242, 248, 255), Color(60, 67, 78))
        private val CLARIFICATION_PREVIEW_BORDER = JBColor(Color(194, 210, 233), Color(92, 104, 121))
        private val STATUS_BG = JBColor(Color(235, 244, 255), Color(62, 68, 80))
        private val STATUS_BORDER = JBColor(Color(183, 199, 224), Color(98, 109, 125))
        private val GENERATING_FG = JBColor(Color(46, 90, 162), Color(171, 201, 248))
        private val TREE_TEXT = JBColor(Color(34, 54, 88), Color(214, 224, 238))
        private val TREE_ROW_BG_NEUTRAL = JBColor(Color(250, 252, 254), Color(64, 71, 82))
        private val TREE_ROW_SPECIFY_BG = JBColor(Color(248, 251, 255), Color(67, 74, 86))
        private val TREE_ROW_SPECIFY_BG_SELECTED = JBColor(Color(236, 243, 252), Color(79, 95, 120))
        private val TREE_ROW_SPECIFY_BORDER = JBColor(Color(219, 229, 242), Color(102, 118, 140))
        private val TREE_ROW_SPECIFY_BORDER_SELECTED = JBColor(Color(184, 201, 224), Color(128, 149, 179))
        private val TREE_ROW_DESIGN_BG = JBColor(Color(251, 249, 245), Color(70, 73, 80))
        private val TREE_ROW_DESIGN_BG_SELECTED = JBColor(Color(246, 239, 228), Color(84, 90, 103))
        private val TREE_ROW_DESIGN_BORDER = JBColor(Color(231, 221, 206), Color(111, 112, 121))
        private val TREE_ROW_DESIGN_BORDER_SELECTED = JBColor(Color(205, 184, 153), Color(140, 129, 109))
        private val TREE_ROW_IMPLEMENT_BG = JBColor(Color(247, 251, 248), Color(66, 74, 80))
        private val TREE_ROW_IMPLEMENT_BG_SELECTED = JBColor(Color(235, 243, 236), Color(78, 93, 102))
        private val TREE_ROW_IMPLEMENT_BORDER = JBColor(Color(214, 227, 218), Color(100, 117, 124))
        private val TREE_ROW_IMPLEMENT_BORDER_SELECTED = JBColor(Color(173, 202, 181), Color(123, 145, 128))
        private val TREE_FILE_TEXT = JBColor(Color(83, 97, 121), Color(184, 197, 218))
        private val TREE_FILE_TEXT_SELECTED = JBColor(Color(64, 78, 101), Color(217, 228, 243))
        private val TREE_PHASE_SPECIFY = JBColor(Color(93, 129, 176), Color(167, 197, 242))
        private val TREE_PHASE_SPECIFY_SELECTED = JBColor(Color(75, 112, 161), Color(198, 216, 246))
        private val TREE_PHASE_DESIGN = JBColor(Color(153, 126, 92), Color(226, 194, 149))
        private val TREE_PHASE_DESIGN_SELECTED = JBColor(Color(133, 109, 81), Color(242, 210, 165))
        private val TREE_PHASE_IMPLEMENT = JBColor(Color(85, 138, 106), Color(173, 217, 185))
        private val TREE_PHASE_IMPLEMENT_SELECTED = JBColor(Color(72, 121, 91), Color(201, 232, 209))
        private val TREE_STATUS_DONE_TEXT = JBColor(Color(108, 136, 115), Color(205, 232, 210))
        private val TREE_STATUS_DONE_TEXT_SELECTED = JBColor(Color(89, 122, 98), Color(220, 237, 223))
        private val TREE_STATUS_DRAFT_TEXT = JBColor(Color(144, 122, 95), Color(239, 213, 174))
        private val TREE_STATUS_DRAFT_TEXT_SELECTED = JBColor(Color(124, 102, 77), Color(246, 223, 187))
        private val TREE_STATUS_PENDING_TEXT = JBColor(Color(120, 132, 149), Color(196, 210, 230))
        private val TREE_STATUS_PENDING_TEXT_SELECTED = JBColor(Color(100, 113, 131), Color(215, 224, 239))
        private val BUTTON_BG = JBColor(Color(239, 246, 255), Color(64, 70, 81))
        private val BUTTON_BORDER = JBColor(Color(179, 197, 224), Color(102, 114, 132))
        private val BUTTON_FG = JBColor(Color(44, 68, 108), Color(204, 216, 236))
        private val SECTION_TITLE_FG = JBColor(Color(36, 60, 101), Color(212, 223, 241))
        private const val CARD_PREVIEW = "preview"
        private const val CARD_EDIT = "edit"
        private const val CARD_CLARIFY = "clarify"
    }
}
