package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecMarkdownSanitizer
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.ValidationResult
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
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextPane
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.BadLocationException
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
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
    private val onClarificationDraftAutosave: (String, String, String, List<String>) -> Unit,
) : JPanel(BorderLayout()) {

    private val treeRoot = DefaultMutableTreeNode(SpecCodingBundle.message("spec.detail.documents"))
    private val treeModel = DefaultTreeModel(treeRoot)
    private val documentTree = JTree(treeModel)

    private val previewPane = JTextPane()
    private val clarificationQuestionsPane = JTextPane()
    private val clarificationPreviewPane = JTextPane()
    private val processTimelinePane = JTextPane()
    private val previewCardLayout = CardLayout()
    private val previewCardPanel = JPanel(previewCardLayout)
    private val previewModePanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
    private val previewModeButton = JButton(SpecCodingBundle.message("spec.detail.view.preview"))
    private val clarificationModeButton = JButton(SpecCodingBundle.message("spec.detail.view.clarify"))
    private val processTimelineLabel = JBLabel(SpecCodingBundle.message("spec.detail.process.title"))
    private val clarificationQuestionsLabel = JBLabel(SpecCodingBundle.message("spec.detail.clarify.questions.title"))
    private val clarificationChecklistHintLabel = JBLabel(SpecCodingBundle.message("spec.detail.clarify.checklist.hint"))
    private val clarificationPreviewLabel = JBLabel(SpecCodingBundle.message("spec.detail.clarify.preview.title"))
    private val clarificationQuestionsCardLayout = CardLayout()
    private val clarificationQuestionsCardPanel = JPanel(clarificationQuestionsCardLayout)
    private val clarificationChecklistPanel = JPanel()
    private val editorArea = JBTextArea(14, 40)
    private val validationLabel = JBLabel("")
    private val inputArea = JBTextArea(3, 40)
    private lateinit var clarificationSplitPane: JSplitPane
    private lateinit var clarificationPreviewSection: JPanel
    private lateinit var clarificationQuestionsBodyContainer: JPanel
    private lateinit var clarificationPreviewBodyContainer: JPanel
    private lateinit var clarificationQuestionsToggleButton: JButton
    private lateinit var clarificationPreviewToggleButton: JButton
    private lateinit var processTimelineSection: JPanel
    private lateinit var processTimelineBodyContainer: JPanel
    private lateinit var processTimelineToggleButton: JButton
    private lateinit var inputSectionContainer: JPanel
    private lateinit var bottomPanelContainer: JPanel
    private lateinit var mainSplitPane: JSplitPane

    private val generateButton = JButton()
    private val nextPhaseButton = JButton()
    private val goBackButton = JButton()
    private val completeButton = JButton()
    private val pauseResumeButton = JButton()
    private val openEditorButton = JButton()
    private val historyDiffButton = JButton()
    private val editButton = JButton()
    private val saveButton = JButton()
    private val cancelEditButton = JButton()
    private val confirmGenerateButton = JButton()
    private val regenerateClarificationButton = JButton()
    private val skipClarificationButton = JButton()
    private val cancelClarificationButton = JButton()

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
    private var activeChecklistDetailIndex: Int? = null
    private var isClarificationChecklistReadOnly: Boolean = false
    private var isBottomCollapsedForChecklist: Boolean = false
    private var isProcessTimelineExpanded: Boolean = true
    private var isClarificationQuestionsExpanded: Boolean = true
    private var isClarificationPreviewExpanded: Boolean = true
    private var isClarificationPreviewContentVisible: Boolean = true
    private val processTimelineEntries = mutableListOf<ProcessTimelineEntry>()

    init {
        setupUI()
    }

    private data class ClarificationState(
        val phase: SpecPhase,
        val input: String,
        val questionsMarkdown: String,
        val structuredQuestions: List<String> = emptyList(),
        val questionDecisions: Map<Int, ClarificationQuestionDecision> = emptyMap(),
        val questionDetails: Map<Int, String> = emptyMap(),
    )

    private enum class ClarificationQuestionDecision {
        UNDECIDED,
        CONFIRMED,
        NOT_APPLICABLE,
    }

    enum class ProcessTimelineState {
        INFO,
        ACTIVE,
        DONE,
        FAILED,
    }

    data class ProcessTimelineEntry(
        val text: String,
        val state: ProcessTimelineState = ProcessTimelineState.INFO,
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
        processTimelineSection = createProcessTimelineSection()
        setProcessTimelineVisible(false)
        previewPanel.add(processTimelineSection, BorderLayout.NORTH)
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

        bottomPanelContainer = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = true
            background = INPUT_COLUMN_BG
            border = JBUI.Borders.emptyTop(6)
        }

        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = onClarificationInputEdited()
                override fun removeUpdate(e: DocumentEvent?) = onClarificationInputEdited()
                override fun changedUpdate(e: DocumentEvent?) = onClarificationInputEdited()
            },
        )
        updateInputPlaceholder(null)
        val inputScroll = JBScrollPane(inputArea)
        inputScroll.border = JBUI.Borders.empty()
        inputScroll.preferredSize = java.awt.Dimension(0, JBUI.scale(80))
        inputSectionContainer = createSectionContainer(
            inputScroll,
            backgroundColor = INPUT_SECTION_BG,
            borderColor = INPUT_SECTION_BORDER,
        )
        bottomPanelContainer.add(inputSectionContainer, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(JBUI.scale(2))
        }
        setupButtons(buttonPanel)
        bottomPanelContainer.add(
            createSectionContainer(
                JBScrollPane(buttonPanel).apply {
                    border = JBUI.Borders.empty(2, 3)
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

        mainSplitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            topComponent = topSplit
            bottomComponent = bottomPanelContainer
            resizeWeight = 0.67
            border = JBUI.Borders.empty()
            background = PANEL_BG
            SpecUiStyle.applySplitPaneDivider(this, dividerSize = JBUI.scale(8))
        }
        add(mainSplitPane, BorderLayout.CENTER)
    }

    private fun setupButtons(panel: JPanel) {
        applyActionButtonPresentation()
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
            } else {
                showInputRequiredHint(phase)
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
            if (state.structuredQuestions.isNotEmpty()) {
                val firstMissingDetail = state.questionDecisions.entries
                    .asSequence()
                    .filter { it.value == ClarificationQuestionDecision.CONFIRMED }
                    .mapNotNull { (index, _) ->
                        val question = state.structuredQuestions.getOrNull(index)?.trim().orEmpty()
                        if (question.isBlank()) {
                            null
                        } else {
                            question to state.questionDetails[index].orEmpty().trim()
                        }
                    }
                    .firstOrNull { (_, detail) -> detail.isBlank() }
                if (firstMissingDetail != null) {
                    validationLabel.text = SpecCodingBundle.message(
                        "spec.detail.clarify.checklist.detail.required",
                        firstMissingDetail.first,
                    )
                    validationLabel.foreground = JBColor(Color(213, 52, 52), Color(255, 140, 140))
                    return@addActionListener
                }
            }
            val confirmed = resolveClarificationConfirmedContext(state)
            val allowBlank = state.phase == SpecPhase.DESIGN || state.phase == SpecPhase.IMPLEMENT
            if (confirmed.isBlank() && !allowBlank) {
                validationLabel.text = SpecCodingBundle.message("spec.detail.clarify.detailsRequired")
                validationLabel.foreground = JBColor(Color(213, 52, 52), Color(255, 140, 140))
                return@addActionListener
            }
            setClarificationChecklistReadOnly(true)
            onClarificationConfirm(state.input, confirmed)
        }
        regenerateClarificationButton.addActionListener {
            val state = clarificationState ?: return@addActionListener
            onClarificationRegenerate(state.input, resolveClarificationConfirmedContext(state))
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

    private fun applyActionButtonPresentation() {
        configureIconActionButton(
            button = generateButton,
            icon = AllIcons.Actions.Execute,
            tooltipKey = "spec.detail.generate",
        )
        configureIconActionButton(
            button = nextPhaseButton,
            icon = AllIcons.General.ArrowRight,
            tooltipKey = "spec.detail.nextPhase",
        )
        configureIconActionButton(
            button = goBackButton,
            icon = AllIcons.General.ArrowLeft,
            tooltipKey = "spec.detail.goBack",
        )
        configureIconActionButton(
            button = completeButton,
            icon = AllIcons.General.GreenCheckmark,
            tooltipKey = "spec.detail.complete",
        )
        configureIconActionButton(
            button = openEditorButton,
            icon = AllIcons.General.OpenInToolWindow,
            tooltipKey = "spec.detail.openInEditor",
        )
        configureIconActionButton(
            button = historyDiffButton,
            icon = AllIcons.Vcs.HistoryInline,
            tooltipKey = "spec.detail.historyDiff",
        )
        configureIconActionButton(
            button = editButton,
            icon = AllIcons.General.Inline_edit,
            tooltipKey = "spec.detail.edit",
        )
        configureIconActionButton(
            button = saveButton,
            icon = AllIcons.General.OpenDisk,
            tooltipKey = "spec.detail.save",
        )
        configureIconActionButton(
            button = cancelEditButton,
            icon = AllIcons.Actions.Close,
            tooltipKey = "spec.detail.cancel",
        )
        configureIconActionButton(
            button = confirmGenerateButton,
            icon = AllIcons.Actions.Execute,
            tooltipKey = "spec.detail.clarify.confirmGenerate",
        )
        configureIconActionButton(
            button = regenerateClarificationButton,
            icon = AllIcons.General.InlineRefresh,
            tooltipKey = "spec.detail.clarify.regenerate",
        )
        configureIconActionButton(
            button = skipClarificationButton,
            icon = AllIcons.Actions.Forward,
            tooltipKey = "spec.detail.clarify.skip",
        )
        configureIconActionButton(
            button = cancelClarificationButton,
            icon = AllIcons.Actions.Close,
            tooltipKey = "spec.detail.clarify.cancel",
        )
        updatePauseResumeButtonPresentation(currentWorkflow?.status)
    }

    private fun configureIconActionButton(button: JButton, icon: Icon, tooltipKey: String) {
        val tooltip = SpecCodingBundle.message(tooltipKey)
        button.icon = icon
        button.text = ""
        button.toolTipText = tooltip
        button.accessibleContext.accessibleName = tooltip
    }

    private fun updatePauseResumeButtonPresentation(status: WorkflowStatus?) {
        val isPaused = status == WorkflowStatus.PAUSED
        val tooltipKey = if (isPaused) "spec.detail.resume" else "spec.detail.pause"
        val icon = if (isPaused) AllIcons.Actions.Resume else AllIcons.General.InspectionsPause
        configureIconActionButton(
            button = pauseResumeButton,
            icon = icon,
            tooltipKey = tooltipKey,
        )
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
        clarificationChecklistHintLabel.font = JBUI.Fonts.smallFont()
        clarificationChecklistHintLabel.foreground = TREE_FILE_TEXT
        clarificationChecklistHintLabel.border = JBUI.Borders.empty(0, 2, 2, 2)

        clarificationChecklistPanel.layout = BoxLayout(clarificationChecklistPanel, BoxLayout.Y_AXIS)
        clarificationChecklistPanel.isOpaque = false
        val checklistHeaderPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(clarificationChecklistHintLabel, BorderLayout.CENTER)
        }

        clarificationQuestionsCardPanel.isOpaque = false
        clarificationQuestionsCardPanel.add(
            JBScrollPane(clarificationQuestionsPane).apply {
                border = JBUI.Borders.empty()
            },
            CLARIFY_QUESTIONS_CARD_MARKDOWN,
        )
        clarificationQuestionsCardPanel.add(
            JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                isOpaque = false
                add(checklistHeaderPanel, BorderLayout.NORTH)
                add(
                    JBScrollPane(clarificationChecklistPanel).apply {
                        border = JBUI.Borders.empty()
                        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                    },
                    BorderLayout.CENTER,
                )
            },
            CLARIFY_QUESTIONS_CARD_CHECKLIST,
        )

        clarificationPreviewPane.isEditable = false
        clarificationPreviewPane.isOpaque = false
        clarificationPreviewPane.border = JBUI.Borders.empty(2, 2)
        styleClarificationSectionLabel(clarificationPreviewLabel)

        val questionsCollapsible = createCollapsibleSection(
            titleLabel = clarificationQuestionsLabel,
            content = createSectionContainer(
                clarificationQuestionsCardPanel,
                backgroundColor = CLARIFICATION_QUESTIONS_BG,
                borderColor = CLARIFICATION_QUESTIONS_BORDER,
            ),
            expanded = isClarificationQuestionsExpanded,
        ) { expanded ->
            isClarificationQuestionsExpanded = expanded
            refreshClarificationSectionsLayout()
        }
        clarificationQuestionsBodyContainer = questionsCollapsible.bodyContainer
        clarificationQuestionsToggleButton = questionsCollapsible.toggleButton
        val questionsSection = questionsCollapsible.root

        val previewCollapsible = createCollapsibleSection(
            titleLabel = clarificationPreviewLabel,
            content = createSectionContainer(
                JBScrollPane(clarificationPreviewPane).apply {
                    border = JBUI.Borders.empty()
                },
                backgroundColor = CLARIFICATION_PREVIEW_BG,
                borderColor = CLARIFICATION_PREVIEW_BORDER,
            ),
            expanded = isClarificationPreviewExpanded,
        ) { expanded ->
            isClarificationPreviewExpanded = expanded
            refreshClarificationSectionsLayout()
        }
        clarificationPreviewSection = previewCollapsible.root
        clarificationPreviewBodyContainer = previewCollapsible.bodyContainer
        clarificationPreviewToggleButton = previewCollapsible.toggleButton

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
            refreshClarificationSectionsLayout()
        }
    }

    private fun createProcessTimelineSection(): JPanel {
        processTimelinePane.isEditable = false
        processTimelinePane.isOpaque = false
        processTimelinePane.border = JBUI.Borders.empty(2, 2)
        styleClarificationSectionLabel(processTimelineLabel)

        val timelineCollapsible = createCollapsibleSection(
            titleLabel = processTimelineLabel,
            content = createSectionContainer(
                JBScrollPane(processTimelinePane).apply {
                    border = JBUI.Borders.empty()
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                    preferredSize = JBUI.size(0, JBUI.scale(96))
                },
                backgroundColor = PROCESS_SECTION_BG,
                borderColor = PROCESS_SECTION_BORDER,
            ),
            expanded = isProcessTimelineExpanded,
        ) { expanded ->
            isProcessTimelineExpanded = expanded
            applyProcessTimelineCollapseState()
        }
        processTimelineBodyContainer = timelineCollapsible.bodyContainer
        processTimelineToggleButton = timelineCollapsible.toggleButton
        return timelineCollapsible.root.apply {
            border = JBUI.Borders.emptyBottom(JBUI.scale(2))
            applyProcessTimelineCollapseState()
        }
    }

    private data class CollapsibleSectionWidgets(
        val root: JPanel,
        val bodyContainer: JPanel,
        val toggleButton: JButton,
    )

    private fun createCollapsibleSection(
        titleLabel: JBLabel,
        content: Component,
        expanded: Boolean,
        onToggle: (Boolean) -> Unit,
    ): CollapsibleSectionWidgets {
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            isVisible = expanded
            add(content, BorderLayout.CENTER)
        }
        val toggleButton = JButton().apply {
            styleCollapsibleToggleButton(this)
            addActionListener {
                val nextExpanded = !body.isVisible
                body.isVisible = nextExpanded
                onToggle(nextExpanded)
            }
        }
        val header = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.WEST)
            add(toggleButton, BorderLayout.EAST)
        }
        val root = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
        updateCollapseToggleButton(toggleButton, expanded = expanded, enabled = true)
        return CollapsibleSectionWidgets(root = root, bodyContainer = body, toggleButton = toggleButton)
    }

    private fun styleCollapsibleToggleButton(button: JButton) {
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = false
        button.isOpaque = false
        button.isBorderPainted = false
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.foreground = TREE_FILE_TEXT
        button.margin = JBUI.insets(0, 4, 0, 4)
        button.preferredSize = JBUI.size(JBUI.scale(52), JBUI.scale(20))
        button.minimumSize = JBUI.size(JBUI.scale(46), JBUI.scale(20))
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun updateCollapseToggleButton(button: JButton, expanded: Boolean, enabled: Boolean) {
        val key = if (expanded && enabled) {
            "spec.detail.toggle.collapse"
        } else {
            "spec.detail.toggle.expand"
        }
        button.text = SpecCodingBundle.message(key)
        button.toolTipText = button.text
        button.isEnabled = enabled
        button.foreground = when {
            !enabled -> TREE_STATUS_PENDING_TEXT
            expanded -> COLLAPSE_TOGGLE_TEXT_ACTIVE
            else -> TREE_FILE_TEXT
        }
        button.cursor = if (enabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
        val targetWidth = maxOf(
            JBUI.scale(46),
            button.getFontMetrics(button.font).stringWidth(button.text) + JBUI.scale(8),
        )
        button.preferredSize = JBUI.size(targetWidth, JBUI.scale(20))
    }

    private fun refreshCollapsibleToggleTexts() {
        if (::processTimelineToggleButton.isInitialized) {
            updateCollapseToggleButton(
                processTimelineToggleButton,
                expanded = isProcessTimelineExpanded,
                enabled = true,
            )
        }
        if (::clarificationQuestionsToggleButton.isInitialized) {
            updateCollapseToggleButton(
                clarificationQuestionsToggleButton,
                expanded = isClarificationQuestionsExpanded,
                enabled = true,
            )
        }
        if (::clarificationPreviewToggleButton.isInitialized) {
            updateCollapseToggleButton(
                clarificationPreviewToggleButton,
                expanded = isClarificationPreviewExpanded,
                enabled = isClarificationPreviewContentVisible,
            )
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
        refreshActionButtonCursors()
    }

    private fun applyPreviewModeStyle(button: JButton, selected: Boolean) {
        button.background = if (selected) BUTTON_BG else STATUS_BG
        button.foreground = if (selected) BUTTON_FG else TREE_TEXT
    }

    private fun showClarificationEntryHint() {
        validationLabel.text = SpecCodingBundle.message("spec.detail.clarify.entry.hint")
        validationLabel.foreground = GENERATING_FG
    }

    private fun showInputRequiredHint(phase: SpecPhase?) {
        if (phase != SpecPhase.SPECIFY) return
        validationLabel.text = SpecCodingBundle.message("spec.detail.input.required")
        validationLabel.foreground = JBColor(Color(213, 52, 52), Color(255, 140, 140))
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
        val iconOnly = button.icon != null && button.text.isNullOrBlank()
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = true
        button.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
        button.margin = if (iconOnly) JBUI.insets(0, 0, 0, 0) else JBUI.insets(1, 4, 1, 4)
        button.isOpaque = true
        button.foreground = BUTTON_FG
        button.background = BUTTON_BG
        button.border = BorderFactory.createCompoundBorder(
            SpecUiStyle.roundedLineBorder(BUTTON_BORDER, JBUI.scale(10)),
            if (iconOnly) JBUI.Borders.empty(1, 1, 1, 1) else JBUI.Borders.empty(1, 5, 1, 5),
        )
        SpecUiStyle.applyRoundRect(button, arc = 10)
        if (iconOnly) {
            val size = JBUI.scale(24)
            button.preferredSize = JBUI.size(size, size)
            button.minimumSize = button.preferredSize
        } else {
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
        updateButtonCursor(button)
    }

    private fun updateButtonCursor(button: JButton) {
        button.cursor = if (button.isEnabled) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }

    private fun refreshActionButtonCursors() {
        listOf(
            previewModeButton,
            clarificationModeButton,
            generateButton,
            nextPhaseButton,
            goBackButton,
            completeButton,
            pauseResumeButton,
            openEditorButton,
            historyDiffButton,
            editButton,
            saveButton,
            cancelEditButton,
            confirmGenerateButton,
            regenerateClarificationButton,
            skipClarificationButton,
            cancelClarificationButton,
        ).forEach(::updateButtonCursor)
    }

    fun refreshLocalizedTexts() {
        treeRoot.userObject = SpecCodingBundle.message("spec.detail.documents")
        treeModel.reload()
        previewModeButton.text = SpecCodingBundle.message("spec.detail.view.preview")
        clarificationModeButton.text = SpecCodingBundle.message("spec.detail.view.clarify")
        previewModeButton.toolTipText = SpecCodingBundle.message("spec.detail.view.preview.tooltip")
        clarificationModeButton.toolTipText = SpecCodingBundle.message("spec.detail.view.clarify.tooltip")
        processTimelineLabel.text = SpecCodingBundle.message("spec.detail.process.title")
        clarificationQuestionsLabel.text = SpecCodingBundle.message("spec.detail.clarify.questions.title")
        clarificationChecklistHintLabel.text = SpecCodingBundle.message("spec.detail.clarify.checklist.hint")
        clarificationPreviewLabel.text = SpecCodingBundle.message("spec.detail.clarify.preview.title")
        applyActionButtonPresentation()
        updateInputPlaceholder(currentWorkflow?.currentPhase)
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
        refreshCollapsibleToggleTexts()
        renderProcessTimeline()
        if (isClarificationGenerating) {
            renderClarificationQuestions(
                markdown = SpecCodingBundle.message("spec.workflow.clarify.generating"),
                structuredQuestions = emptyList(),
                questionDecisions = emptyMap(),
                questionDetails = emptyMap(),
            )
        } else {
            clarificationState?.let { state ->
                renderClarificationQuestions(
                    markdown = state.questionsMarkdown,
                    structuredQuestions = state.structuredQuestions,
                    questionDecisions = state.questionDecisions,
                    questionDetails = state.questionDetails,
                )
            }
        }
        refreshClarificationSectionsLayout()
        setClarificationPreviewVisible(!isClarificationGenerating)
        updateClarificationPreview()
        refreshInputAreaMode()
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
            activeChecklistDetailIndex = null
            isClarificationChecklistReadOnly = false
            clearProcessTimeline()
        }
        if (clarificationState?.phase != null && clarificationState?.phase != workflow.currentPhase) {
            clarificationState = null
            activeChecklistDetailIndex = null
            isClarificationChecklistReadOnly = false
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
        refreshInputAreaMode()
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
        activeChecklistDetailIndex = null
        isClarificationChecklistReadOnly = false
        clearProcessTimeline()
        treeRoot.removeAllChildren()
        treeModel.reload()
        setClarificationPreviewVisible(true)
        switchPreviewCard(CARD_PREVIEW)
        documentTree.isEnabled = true
        previewSourceText = ""
        previewPane.text = ""
        clarificationQuestionsPane.text = ""
        clarificationChecklistPanel.removeAll()
        clarificationChecklistHintLabel.text = SpecCodingBundle.message("spec.detail.clarify.checklist.hint")
        clarificationPreviewPane.text = ""
        validationLabel.text = SpecCodingBundle.message("spec.detail.noWorkflow")
        validationLabel.foreground = JBColor.GRAY
        inputArea.isEnabled = true
        inputArea.isEditable = true
        inputArea.toolTipText = null
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
            clarificationState?.structuredQuestions?.isNotEmpty() == true -> "spec.detail.clarify.input.placeholder.checklist"
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
        refreshInputAreaMode()
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
        refreshInputAreaMode()
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
        activeChecklistDetailIndex = null
        isClarificationChecklistReadOnly = false
        renderClarificationQuestions(
            markdown = generatingText,
            structuredQuestions = emptyList(),
            questionDecisions = emptyMap(),
            questionDetails = emptyMap(),
        )
        inputArea.text = suggestedDetails
        inputArea.caretPosition = 0
        updateInputPlaceholder(phase)
        refreshInputAreaMode()
        setClarificationPreviewVisible(false)
        switchPreviewCard(CARD_CLARIFY)
        updateClarificationPreview()
        persistClarificationDraftSnapshot()

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
        structuredQuestions: List<String> = emptyList(),
    ) {
        isGeneratingActive = false
        isClarificationGenerating = false
        stopGeneratingAnimation()
        val normalizedQuestions = structuredQuestions
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val inferredDecisions = inferQuestionDecisions(
            structuredQuestions = normalizedQuestions,
            confirmedContext = suggestedDetails,
        )
        val inferredDetails = inferQuestionDetails(
            structuredQuestions = normalizedQuestions,
            confirmedContext = suggestedDetails,
            questionDecisions = inferredDecisions,
        )
        clarificationState = ClarificationState(
            phase = phase,
            input = input,
            questionsMarkdown = questionsMarkdown,
            structuredQuestions = normalizedQuestions,
            questionDecisions = inferredDecisions,
            questionDetails = inferredDetails,
        )
        activeChecklistDetailIndex = inferredDecisions
            .entries
            .asSequence()
            .filter { it.value == ClarificationQuestionDecision.CONFIRMED }
            .map { it.key }
            .sorted()
            .firstOrNull()
        isClarificationChecklistReadOnly = false
        renderClarificationQuestions(
            markdown = questionsMarkdown,
            structuredQuestions = normalizedQuestions,
            questionDecisions = inferredDecisions,
            questionDetails = inferredDetails,
        )
        if (normalizedQuestions.isNotEmpty()) {
            syncClarificationInputFromSelection(clarificationState)
        } else {
            inputArea.text = suggestedDetails
            inputArea.caretPosition = 0
        }
        updateInputPlaceholder(phase)
        refreshInputAreaMode()
        setClarificationPreviewVisible(true)
        switchPreviewCard(CARD_CLARIFY)
        updateClarificationPreview()
        persistClarificationDraftSnapshot()
        validationLabel.text = SpecCodingBundle.message("spec.workflow.clarify.hint")
        validationLabel.foreground = TREE_TEXT
        currentWorkflow?.let { updateButtonStates(it) } ?: disableAllButtons()
    }

    fun showProcessTimeline(entries: List<ProcessTimelineEntry>) {
        processTimelineEntries.clear()
        entries.forEach { entry ->
            val normalized = entry.text.trim()
            if (normalized.isNotBlank()) {
                processTimelineEntries += entry.copy(text = normalized)
            }
        }
        while (processTimelineEntries.size > MAX_PROCESS_TIMELINE_ENTRIES) {
            processTimelineEntries.removeAt(0)
        }
        renderProcessTimeline()
    }

    fun appendProcessTimelineEntry(
        text: String,
        state: ProcessTimelineState = ProcessTimelineState.INFO,
    ) {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return
        }
        val previous = processTimelineEntries.lastOrNull()
        if (previous != null && previous.text == normalized && previous.state == state) {
            return
        }
        processTimelineEntries += ProcessTimelineEntry(
            text = normalized,
            state = state,
        )
        while (processTimelineEntries.size > MAX_PROCESS_TIMELINE_ENTRIES) {
            processTimelineEntries.removeAt(0)
        }
        renderProcessTimeline()
    }

    fun clearProcessTimeline() {
        processTimelineEntries.clear()
        renderProcessTimeline()
    }

    fun lockClarificationChecklistInteractions() {
        setClarificationChecklistReadOnly(true)
    }

    fun unlockClarificationChecklistInteractions() {
        setClarificationChecklistReadOnly(false)
    }

    private fun setClarificationChecklistReadOnly(readOnly: Boolean) {
        if (isClarificationChecklistReadOnly == readOnly) {
            return
        }
        isClarificationChecklistReadOnly = readOnly
        clarificationState
            ?.takeIf { it.structuredQuestions.isNotEmpty() }
            ?.let { state ->
                renderClarificationQuestions(
                    markdown = state.questionsMarkdown,
                    structuredQuestions = state.structuredQuestions,
                    questionDecisions = state.questionDecisions,
                    questionDetails = state.questionDetails,
                )
            }
        refreshInputAreaMode()
        currentWorkflow?.let { updateButtonStates(it) }
    }

    fun exitClarificationMode(clearInput: Boolean = false) {
        isGeneratingActive = false
        isClarificationGenerating = false
        stopGeneratingAnimation()
        clarificationState = null
        activeChecklistDetailIndex = null
        isClarificationChecklistReadOnly = false
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
        refreshInputAreaMode()
        currentWorkflow?.let { updateButtonStates(it) } ?: disableAllButtons()
    }

    private fun renderClarificationQuestions(
        markdown: String,
        structuredQuestions: List<String>,
        questionDecisions: Map<Int, ClarificationQuestionDecision>,
        questionDetails: Map<Int, String>,
    ) {
        if (structuredQuestions.isNotEmpty()) {
            renderChecklistClarificationQuestions(
                structuredQuestions = structuredQuestions,
                questionDecisions = questionDecisions,
                questionDetails = questionDetails,
            )
            clarificationQuestionsCardLayout.show(clarificationQuestionsCardPanel, CLARIFY_QUESTIONS_CARD_CHECKLIST)
            return
        }
        activeChecklistDetailIndex = null
        renderMarkdownClarificationQuestions(markdown)
        clarificationQuestionsCardLayout.show(clarificationQuestionsCardPanel, CLARIFY_QUESTIONS_CARD_MARKDOWN)
    }

    private fun renderMarkdownClarificationQuestions(markdown: String) {
        val content = SpecMarkdownSanitizer.sanitize(markdown).ifBlank {
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

    private fun renderChecklistClarificationQuestions(
        structuredQuestions: List<String>,
        questionDecisions: Map<Int, ClarificationQuestionDecision>,
        questionDetails: Map<Int, String>,
    ) {
        clarificationChecklistPanel.removeAll()
        val confirmedIndexes = structuredQuestions.indices
            .filter { index -> questionDecisions[index] == ClarificationQuestionDecision.CONFIRMED }
        val resolvedActiveIndex = when {
            confirmedIndexes.isEmpty() -> null
            activeChecklistDetailIndex?.let { it in confirmedIndexes } == true -> activeChecklistDetailIndex
            else -> confirmedIndexes.first()
        }
        activeChecklistDetailIndex = resolvedActiveIndex
        val checklistEditable = !isClarificationChecklistReadOnly
        structuredQuestions.forEachIndexed { index, question ->
            val decision = questionDecisions[index] ?: ClarificationQuestionDecision.UNDECIDED
            clarificationChecklistPanel.add(
                createChecklistQuestionItem(
                    index = index,
                    question = question,
                    decision = decision,
                    detail = questionDetails[index].orEmpty(),
                    detailExpanded = decision == ClarificationQuestionDecision.CONFIRMED && resolvedActiveIndex == index,
                    editable = checklistEditable,
                ),
            )
            clarificationChecklistPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        }
        val confirmedCount = questionDecisions.values.count { it == ClarificationQuestionDecision.CONFIRMED }
        val notApplicableCount = questionDecisions.values.count { it == ClarificationQuestionDecision.NOT_APPLICABLE }
        val summary = SpecCodingBundle.message(
            "spec.detail.clarify.checklist.progress",
            confirmedCount,
            notApplicableCount,
            structuredQuestions.size,
        )
        val hint = SpecCodingBundle.message("spec.detail.clarify.checklist.hint")
        clarificationChecklistHintLabel.text = "$summary  ·  $hint"
        clarificationChecklistPanel.revalidate()
        clarificationChecklistPanel.repaint()
    }

    private fun createChecklistQuestionItem(
        index: Int,
        question: String,
        decision: ClarificationQuestionDecision,
        detail: String,
        detailExpanded: Boolean,
        editable: Boolean,
    ): JPanel {
        val indicator = JBLabel(
            when (decision) {
                ClarificationQuestionDecision.CONFIRMED -> "✓"
                ClarificationQuestionDecision.NOT_APPLICABLE -> "∅"
                ClarificationQuestionDecision.UNDECIDED -> "•"
            },
        ).apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            foreground = when (decision) {
                ClarificationQuestionDecision.CONFIRMED -> CHECKLIST_CONFIRM_TEXT
                ClarificationQuestionDecision.NOT_APPLICABLE -> CHECKLIST_NA_TEXT
                ClarificationQuestionDecision.UNDECIDED -> TREE_FILE_TEXT
            }
            border = JBUI.Borders.empty(1, 2, 0, 2)
            cursor = if (editable) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getDefaultCursor()
            }
        }
        val questionText = JTextPane().apply {
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty(0, 2, 0, 2)
            cursor = if (editable) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getDefaultCursor()
            }
        }
        renderChecklistQuestionText(questionText, question)
        val confirmButton = createChecklistChoiceButton(
            text = SpecCodingBundle.message("spec.detail.clarify.checklist.choice.confirm"),
            selected = decision == ClarificationQuestionDecision.CONFIRMED,
            selectedBackground = CHECKLIST_CONFIRM_BG,
            selectedForeground = CHECKLIST_CONFIRM_TEXT,
            normalBackground = CHECKLIST_CHOICE_BG,
            normalForeground = TREE_FILE_TEXT,
            enabled = editable,
        ) {
            onChecklistQuestionDecisionChanged(
                index = index,
                decision = if (decision == ClarificationQuestionDecision.CONFIRMED) {
                    ClarificationQuestionDecision.UNDECIDED
                } else {
                    ClarificationQuestionDecision.CONFIRMED
                },
            )
        }
        val notApplicableButton = createChecklistChoiceButton(
            text = SpecCodingBundle.message("spec.detail.clarify.checklist.choice.na"),
            selected = decision == ClarificationQuestionDecision.NOT_APPLICABLE,
            selectedBackground = CHECKLIST_NA_BG,
            selectedForeground = CHECKLIST_NA_TEXT,
            normalBackground = CHECKLIST_CHOICE_BG,
            normalForeground = TREE_FILE_TEXT,
            enabled = editable,
        ) {
            onChecklistQuestionDecisionChanged(
                index = index,
                decision = if (decision == ClarificationQuestionDecision.NOT_APPLICABLE) {
                    ClarificationQuestionDecision.UNDECIDED
                } else {
                    ClarificationQuestionDecision.NOT_APPLICABLE
                },
            )
        }
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(confirmButton)
            add(notApplicableButton)
        }
        val detailArea = JBTextArea(detail).apply {
            isEditable = editable
            isEnabled = editable
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            rows = 2
            border = JBUI.Borders.empty(1, 2, 0, 2)
            font = JBUI.Fonts.smallFont()
            foreground = TREE_TEXT
            emptyText.setText(SpecCodingBundle.message("spec.detail.clarify.checklist.detail.placeholder"))
            document.addDocumentListener(
                object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent?) = onChecklistQuestionDetailChanged(index, text)
                    override fun removeUpdate(e: DocumentEvent?) = onChecklistQuestionDetailChanged(index, text)
                    override fun changedUpdate(e: DocumentEvent?) = onChecklistQuestionDetailChanged(index, text)
                },
            )
        }
        val detailPanel = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 14, 0, 2)
            isVisible = detailExpanded
            add(
                JBLabel(SpecCodingBundle.message("spec.detail.clarify.checklist.detail.label")).apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = TREE_FILE_TEXT
                },
                BorderLayout.NORTH,
            )
            add(
                createSectionContainer(
                    JBScrollPane(detailArea).apply {
                        border = JBUI.Borders.empty()
                        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                        preferredSize = JBUI.size(0, JBUI.scale(44))
                    },
                    backgroundColor = CHECKLIST_DETAIL_BG,
                    borderColor = CHECKLIST_DETAIL_BORDER,
                ),
                BorderLayout.CENTER,
            )
        }
        val questionHeader = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(indicator, BorderLayout.WEST)
            add(
                JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    add(questionText, BorderLayout.CENTER)
                    add(actionsPanel, BorderLayout.EAST)
                },
                BorderLayout.CENTER,
            )
        }
        val rowColors = checklistRowColors(decision)
        val row = JPanel(BorderLayout(0, 0)).apply {
            isOpaque = true
            background = rowColors.background
            border = SpecUiStyle.roundedCardBorder(
                lineColor = rowColors.border,
                arc = JBUI.scale(10),
                top = 4,
                left = 8,
                bottom = 4,
                right = 8,
            )
            add(questionHeader, BorderLayout.NORTH)
            add(detailPanel, BorderLayout.SOUTH)
        }
        val toggleListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                if (!editable) {
                    return
                }
                if (e == null || e.button != MouseEvent.BUTTON1) {
                    return
                }
                onChecklistQuestionRowClicked(index, decision)
            }
        }
        if (editable) {
            row.addMouseListener(toggleListener)
            indicator.addMouseListener(toggleListener)
            questionText.addMouseListener(toggleListener)
        }
        return row
    }

    private fun createChecklistChoiceButton(
        text: String,
        selected: Boolean,
        selectedBackground: Color,
        selectedForeground: Color,
        normalBackground: Color,
        normalForeground: Color,
        enabled: Boolean,
        onClick: () -> Unit,
    ): JButton {
        return JButton(text).apply {
            isFocusable = false
            isFocusPainted = false
            isContentAreaFilled = true
            font = JBUI.Fonts.smallFont()
            margin = JBUI.insets(0, 6, 0, 6)
            background = if (selected) selectedBackground else normalBackground
            foreground = if (selected) selectedForeground else normalForeground
            border = SpecUiStyle.roundedLineBorder(
                if (selected) selectedForeground else CHECKLIST_CHOICE_BORDER,
                JBUI.scale(8),
            )
            SpecUiStyle.applyRoundRect(this, arc = 8)
            preferredSize = JBUI.size(
                maxOf(JBUI.scale(48), getFontMetrics(font).stringWidth(text) + JBUI.scale(20)),
                JBUI.scale(22),
            )
            isEnabled = enabled
            cursor = if (enabled) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getDefaultCursor()
            }
            addActionListener {
                if (isEnabled) {
                    onClick()
                }
            }
        }
    }

    private fun onChecklistQuestionRowClicked(index: Int, fallbackDecision: ClarificationQuestionDecision) {
        if (isClarificationChecklistReadOnly) {
            return
        }
        val state = clarificationState ?: return
        if (state.structuredQuestions.isEmpty() || index !in state.structuredQuestions.indices) {
            return
        }
        val currentDecision = state.questionDecisions[index] ?: fallbackDecision
        if (currentDecision == ClarificationQuestionDecision.CONFIRMED && activeChecklistDetailIndex != index) {
            activeChecklistDetailIndex = index
            renderClarificationQuestions(
                markdown = state.questionsMarkdown,
                structuredQuestions = state.structuredQuestions,
                questionDecisions = state.questionDecisions,
                questionDetails = state.questionDetails,
            )
            return
        }
        val nextDecision = when (currentDecision) {
            ClarificationQuestionDecision.UNDECIDED -> ClarificationQuestionDecision.CONFIRMED
            ClarificationQuestionDecision.CONFIRMED -> ClarificationQuestionDecision.UNDECIDED
            ClarificationQuestionDecision.NOT_APPLICABLE -> ClarificationQuestionDecision.CONFIRMED
        }
        onChecklistQuestionDecisionChanged(index, nextDecision)
    }

    private fun renderChecklistQuestionText(target: JTextPane, question: String) {
        val doc = target.styledDocument
        try {
            doc.remove(0, doc.length)
            val baseFont = JBUI.Fonts.smallFont()
            val normalAttrs = SimpleAttributeSet().apply {
                StyleConstants.setBold(this, false)
                StyleConstants.setFontFamily(this, baseFont.family)
                StyleConstants.setFontSize(this, baseFont.size)
                StyleConstants.setForeground(this, TREE_TEXT)
            }
            val boldAttrs = SimpleAttributeSet(normalAttrs).apply {
                StyleConstants.setBold(this, true)
            }
            parseChecklistQuestionSegments(question).forEach { segment ->
                val attrs = if (segment.bold) boldAttrs else normalAttrs
                doc.insertString(doc.length, segment.text, attrs)
            }
            target.caretPosition = 0
        } catch (_: BadLocationException) {
            target.text = question
            target.caretPosition = 0
        }
    }

    private fun parseChecklistQuestionSegments(question: String): List<ChecklistQuestionSegment> {
        val normalized = question
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isBlank()) {
            return emptyList()
        }
        val segments = mutableListOf<ChecklistQuestionSegment>()
        var cursor = 0
        BOLD_MARKDOWN_REGEX.findAll(normalized).forEach { match ->
            val start = match.range.first
            val endExclusive = match.range.last + 1
            if (start > cursor) {
                segments += ChecklistQuestionSegment(
                    text = normalized.substring(cursor, start),
                    bold = false,
                )
            }
            val boldText = match.groupValues.getOrNull(1).orEmpty()
            if (boldText.isNotBlank()) {
                segments += ChecklistQuestionSegment(
                    text = boldText,
                    bold = true,
                )
            } else {
                segments += ChecklistQuestionSegment(
                    text = match.value,
                    bold = false,
                )
            }
            cursor = endExclusive
        }
        if (cursor < normalized.length) {
            segments += ChecklistQuestionSegment(
                text = normalized.substring(cursor),
                bold = false,
            )
        }
        return segments.filter { it.text.isNotEmpty() }
    }

    private fun checklistRowColors(decision: ClarificationQuestionDecision): ChecklistRowColors {
        return when (decision) {
            ClarificationQuestionDecision.CONFIRMED -> ChecklistRowColors(
                background = CHECKLIST_ROW_BG_SELECTED,
                border = CHECKLIST_ROW_BORDER_SELECTED,
            )
            ClarificationQuestionDecision.NOT_APPLICABLE -> ChecklistRowColors(
                background = CHECKLIST_ROW_BG_NA,
                border = CHECKLIST_ROW_BORDER_NA,
            )
            ClarificationQuestionDecision.UNDECIDED -> ChecklistRowColors(
                background = CHECKLIST_ROW_BG,
                border = CHECKLIST_ROW_BORDER,
            )
        }
    }

    private fun onChecklistQuestionDecisionChanged(index: Int, decision: ClarificationQuestionDecision) {
        if (isClarificationChecklistReadOnly) {
            return
        }
        val state = clarificationState ?: return
        if (state.structuredQuestions.isEmpty() || index !in state.structuredQuestions.indices) {
            return
        }
        val nextDecisions = state.questionDecisions.toMutableMap()
        val nextDetails = state.questionDetails.toMutableMap()
        if (decision == ClarificationQuestionDecision.UNDECIDED) {
            nextDecisions.remove(index)
            nextDetails.remove(index)
            if (activeChecklistDetailIndex == index) {
                activeChecklistDetailIndex = nextDecisions
                    .entries
                    .asSequence()
                    .filter { it.value == ClarificationQuestionDecision.CONFIRMED }
                    .map { it.key }
                    .sorted()
                    .firstOrNull()
            }
        } else {
            nextDecisions[index] = decision
            if (decision != ClarificationQuestionDecision.CONFIRMED) {
                nextDetails.remove(index)
                if (activeChecklistDetailIndex == index) {
                    activeChecklistDetailIndex = nextDecisions
                        .entries
                        .asSequence()
                        .filter { it.value == ClarificationQuestionDecision.CONFIRMED }
                        .map { it.key }
                        .sorted()
                        .firstOrNull()
                }
            } else {
                activeChecklistDetailIndex = index
            }
        }
        val nextState = state.copy(
            questionDecisions = nextDecisions,
            questionDetails = nextDetails,
        )
        clarificationState = nextState
        renderClarificationQuestions(
            markdown = nextState.questionsMarkdown,
            structuredQuestions = nextState.structuredQuestions,
            questionDecisions = nextState.questionDecisions,
            questionDetails = nextState.questionDetails,
        )
        syncClarificationInputFromSelection(nextState)
        updateClarificationPreview()
        persistClarificationDraftSnapshot(nextState)
        validationLabel.text = SpecCodingBundle.message("spec.workflow.clarify.hint")
        validationLabel.foreground = TREE_TEXT
        currentWorkflow?.let { updateButtonStates(it) }
    }

    private fun onChecklistQuestionDetailChanged(index: Int, detail: String) {
        if (isClarificationChecklistReadOnly) {
            return
        }
        val state = clarificationState ?: return
        if (state.structuredQuestions.isEmpty() || index !in state.structuredQuestions.indices) {
            return
        }
        if (state.questionDecisions[index] != ClarificationQuestionDecision.CONFIRMED) {
            return
        }
        activeChecklistDetailIndex = index
        val normalized = detail
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        val nextDetails = state.questionDetails.toMutableMap()
        if (normalized.isBlank()) {
            nextDetails.remove(index)
        } else {
            nextDetails[index] = normalized
        }
        val nextState = state.copy(questionDetails = nextDetails)
        clarificationState = nextState
        syncClarificationInputFromSelection(nextState)
        updateClarificationPreview()
        persistClarificationDraftSnapshot(nextState)
        validationLabel.text = SpecCodingBundle.message("spec.workflow.clarify.hint")
        validationLabel.foreground = TREE_TEXT
        currentWorkflow?.let { updateButtonStates(it) }
    }

    private fun resolveClarificationConfirmedContext(state: ClarificationState): String {
        if (state.structuredQuestions.isNotEmpty()) {
            return buildChecklistConfirmedContext(state)
        }
        return normalizeContent(inputArea.text)
    }

    private fun buildChecklistConfirmedContext(state: ClarificationState): String {
        val confirmedQuestionDetails = state.questionDecisions.entries
            .filter { it.value == ClarificationQuestionDecision.CONFIRMED }
            .sortedBy { it.key }
            .mapNotNull { entry ->
                val question = state.structuredQuestions.getOrNull(entry.key)?.trim().orEmpty()
                if (question.isBlank()) {
                    null
                } else {
                    val detail = state.questionDetails[entry.key]
                        ?.replace("\r\n", "\n")
                        ?.replace('\r', '\n')
                        ?.trim()
                        .orEmpty()
                    question to detail
                }
            }
        val notApplicableQuestions = state.questionDecisions.entries
            .filter { it.value == ClarificationQuestionDecision.NOT_APPLICABLE }
            .sortedBy { it.key }
            .mapNotNull { entry -> state.structuredQuestions.getOrNull(entry.key) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (confirmedQuestionDetails.isEmpty() && notApplicableQuestions.isEmpty()) {
            return ""
        }
        return buildString {
            if (confirmedQuestionDetails.isNotEmpty()) {
                appendLine("**${SpecCodingBundle.message("spec.detail.clarify.confirmed.title")}**")
                confirmedQuestionDetails.forEach { (question, detail) ->
                    appendLine("- $question")
                    if (detail.isNotBlank()) {
                        appendLine("  - ${SpecCodingBundle.message("spec.detail.clarify.checklist.detail.exportPrefix")}: $detail")
                    }
                }
            }
            if (notApplicableQuestions.isNotEmpty()) {
                if (confirmedQuestionDetails.isNotEmpty()) {
                    appendLine()
                }
                appendLine("**${SpecCodingBundle.message("spec.detail.clarify.notApplicable.title")}**")
                notApplicableQuestions.forEach { question ->
                    appendLine("- $question")
                }
            }
        }.trimEnd()
    }

    private fun syncClarificationInputFromSelection(state: ClarificationState?) {
        val currentState = state ?: clarificationState ?: return
        if (currentState.structuredQuestions.isEmpty()) {
            return
        }
        inputArea.text = buildChecklistConfirmedContext(currentState)
        inputArea.caretPosition = 0
    }

    private fun inferQuestionDecisions(
        structuredQuestions: List<String>,
        confirmedContext: String,
    ): Map<Int, ClarificationQuestionDecision> {
        if (structuredQuestions.isEmpty()) {
            return emptyMap()
        }
        val lines = confirmedContext
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
        val normalizedContext = normalizeComparableText(confirmedContext)
        if (normalizedContext.isBlank()) {
            return emptyMap()
        }
        val lineSections = mapContextSections(lines)
        return structuredQuestions.mapIndexedNotNull { index, question ->
            val normalizedQuestion = normalizeComparableText(question)
            if (normalizedQuestion.isBlank()) {
                return@mapIndexedNotNull null
            }
            val lineIndex = lines.indexOfFirst { line ->
                normalizeComparableText(line).contains(normalizedQuestion)
            }
            val normalizedLine = if (lineIndex >= 0) normalizeComparableText(lines[lineIndex]) else null
            val section = lineSections[lineIndex] ?: ClarificationContextSection.OTHER
            when {
                normalizedLine != null && normalizedLine.contains("[x]") ->
                    index to ClarificationQuestionDecision.CONFIRMED
                normalizedLine != null && (normalizedLine.contains("[ ]") || normalizedLine.contains("[]")) ->
                    index to ClarificationQuestionDecision.NOT_APPLICABLE
                section == ClarificationContextSection.NOT_APPLICABLE ->
                    index to ClarificationQuestionDecision.NOT_APPLICABLE
                section == ClarificationContextSection.CONFIRMED ->
                    index to ClarificationQuestionDecision.CONFIRMED
                normalizedContext.contains(normalizedQuestion) ->
                    index to ClarificationQuestionDecision.CONFIRMED
                else -> null
            }
        }.toMap()
    }

    private fun inferQuestionDetails(
        structuredQuestions: List<String>,
        confirmedContext: String,
        questionDecisions: Map<Int, ClarificationQuestionDecision>,
    ): Map<Int, String> {
        if (structuredQuestions.isEmpty() || questionDecisions.isEmpty()) {
            return emptyMap()
        }
        val lines = confirmedContext
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
        if (lines.isEmpty()) {
            return emptyMap()
        }
        return questionDecisions.entries
            .asSequence()
            .filter { it.value == ClarificationQuestionDecision.CONFIRMED }
            .mapNotNull { (index, _) ->
                val normalizedQuestion = normalizeComparableText(structuredQuestions.getOrNull(index).orEmpty())
                if (normalizedQuestion.isBlank()) {
                    return@mapNotNull null
                }
                val questionLineIndex = lines.indexOfFirst { line ->
                    normalizeComparableText(line).contains(normalizedQuestion)
                }
                if (questionLineIndex < 0) {
                    return@mapNotNull null
                }
                val detail = extractChecklistDetail(lines, questionLineIndex)
                if (detail.isBlank()) {
                    null
                } else {
                    index to detail
                }
            }
            .toMap()
    }

    private fun extractChecklistDetail(lines: List<String>, questionLineIndex: Int): String {
        for (lineIndex in (questionLineIndex + 1) until lines.size) {
            val rawLine = lines[lineIndex]
            val trimmed = rawLine.trim()
            if (trimmed.isBlank()) {
                continue
            }
            if (trimmed.startsWith("#")) {
                break
            }
            if (trimmed.startsWith("- ") && !DETAIL_LINE_REGEX.containsMatchIn(trimmed)) {
                break
            }
            val detailMatch = DETAIL_LINE_REGEX.find(trimmed)
            if (detailMatch != null) {
                return detailMatch.groupValues[2].trim()
            }
        }
        return ""
    }

    private fun mapContextSections(lines: List<String>): Map<Int, ClarificationContextSection> {
        val sectionByLine = mutableMapOf<Int, ClarificationContextSection>()
        var current = ClarificationContextSection.OTHER
        lines.forEachIndexed { index, line ->
            val normalized = normalizeComparableText(line)
            when {
                normalized.isBlank() -> Unit
                clarificationConfirmedSectionMarkers().any { marker -> normalized.contains(marker) } -> {
                    current = ClarificationContextSection.CONFIRMED
                }
                clarificationNotApplicableSectionMarkers().any { marker -> normalized.contains(marker) } -> {
                    current = ClarificationContextSection.NOT_APPLICABLE
                }
            }
            sectionByLine[index] = current
        }
        return sectionByLine
    }

    private fun clarificationConfirmedSectionMarkers(): List<String> {
        return listOf(
            SpecCodingBundle.message("spec.detail.clarify.confirmed.title"),
            "Confirmed Clarification Points",
            "已确认澄清项",
        ).map(::normalizeComparableText).filter { it.isNotBlank() }
    }

    private fun clarificationNotApplicableSectionMarkers(): List<String> {
        return listOf(
            SpecCodingBundle.message("spec.detail.clarify.notApplicable.title"),
            "Not Applicable Clarification Points",
            "不适用澄清项",
        ).map(::normalizeComparableText).filter { it.isNotBlank() }
    }

    private enum class ClarificationContextSection {
        CONFIRMED,
        NOT_APPLICABLE,
        OTHER,
    }

    private fun normalizeComparableText(value: String): String {
        return value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lowercase()
            .replace(Regex("\\s+"), "")
    }

    private fun refreshInputAreaMode() {
        val checklistMode = clarificationState?.structuredQuestions?.isNotEmpty() == true
        val showInputSection = !checklistMode
        if (::inputSectionContainer.isInitialized && inputSectionContainer.isVisible != showInputSection) {
            inputSectionContainer.isVisible = showInputSection
            inputSectionContainer.parent?.revalidate()
            inputSectionContainer.parent?.repaint()
        }
        refreshBottomSplitLayout(showInputSection)
        if (isEditing) {
            inputArea.isEnabled = false
            inputArea.isEditable = false
            inputArea.toolTipText = null
            refreshActionButtonCursors()
            return
        }
        inputArea.isEnabled = true
        inputArea.isEditable = !checklistMode
        inputArea.toolTipText = if (checklistMode) {
            SpecCodingBundle.message("spec.detail.clarify.input.locked")
        } else {
            null
        }
        refreshActionButtonCursors()
    }

    private fun refreshBottomSplitLayout(showInputSection: Boolean) {
        if (!::bottomPanelContainer.isInitialized || !::mainSplitPane.isInitialized) {
            return
        }
        val targetTopInset = if (showInputSection) JBUI.scale(8) else JBUI.scale(2)
        bottomPanelContainer.border = JBUI.Borders.emptyTop(targetTopInset)
        if (showInputSection) {
            if (!isBottomCollapsedForChecklist) {
                return
            }
            isBottomCollapsedForChecklist = false
            SwingUtilities.invokeLater {
                val total = mainSplitPane.height - mainSplitPane.dividerSize
                if (total > 0) {
                    mainSplitPane.dividerLocation = (total * 0.67).toInt()
                }
                mainSplitPane.revalidate()
                mainSplitPane.repaint()
            }
            return
        }
        if (isBottomCollapsedForChecklist) {
            return
        }
        isBottomCollapsedForChecklist = true
        SwingUtilities.invokeLater {
            val total = mainSplitPane.height - mainSplitPane.dividerSize
            if (total <= 0) {
                return@invokeLater
            }
            val desiredBottomHeight = bottomPanelContainer.preferredSize.height.coerceAtLeast(JBUI.scale(56))
            mainSplitPane.dividerLocation = (total - desiredBottomHeight).coerceAtLeast(0)
            mainSplitPane.revalidate()
            mainSplitPane.repaint()
        }
    }

    private data class ChecklistRowColors(
        val background: Color,
        val border: Color,
    )

    private data class ChecklistQuestionSegment(
        val text: String,
        val bold: Boolean,
    )

    private fun onClarificationInputEdited() {
        updateClarificationPreview()
        persistClarificationDraftSnapshot()
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

    private fun persistClarificationDraftSnapshot(state: ClarificationState? = clarificationState) {
        val snapshot = state ?: return
        onClarificationDraftAutosave(
            snapshot.input,
            resolveClarificationConfirmedContext(snapshot),
            snapshot.questionsMarkdown,
            snapshot.structuredQuestions,
        )
    }

    private fun refreshClarificationSectionsLayout() {
        if (!::clarificationSplitPane.isInitialized) {
            return
        }
        if (::clarificationQuestionsBodyContainer.isInitialized) {
            clarificationQuestionsBodyContainer.isVisible = isClarificationQuestionsExpanded
        }
        val previewBodyVisible = isClarificationPreviewContentVisible && isClarificationPreviewExpanded
        if (::clarificationPreviewBodyContainer.isInitialized) {
            clarificationPreviewBodyContainer.isVisible = previewBodyVisible
        }
        if (::clarificationQuestionsToggleButton.isInitialized) {
            updateCollapseToggleButton(
                clarificationQuestionsToggleButton,
                expanded = isClarificationQuestionsExpanded,
                enabled = true,
            )
        }
        if (::clarificationPreviewToggleButton.isInitialized) {
            updateCollapseToggleButton(
                clarificationPreviewToggleButton,
                expanded = isClarificationPreviewExpanded,
                enabled = isClarificationPreviewContentVisible,
            )
        }
        if (isClarificationPreviewContentVisible) {
            clarificationPreviewSection.isVisible = true
            if (clarificationSplitPane.bottomComponent == null) {
                clarificationSplitPane.bottomComponent = clarificationPreviewSection
            }
            clarificationSplitPane.resizeWeight = 0.58
            clarificationSplitPane.dividerSize = JBUI.scale(8)
            SwingUtilities.invokeLater {
                if (!::clarificationSplitPane.isInitialized) {
                    return@invokeLater
                }
                val total = clarificationSplitPane.height - clarificationSplitPane.dividerSize
                if (total <= 0) {
                    return@invokeLater
                }
                val collapsedSectionHeight = JBUI.scale(36)
                val minTop = collapsedSectionHeight
                val minBottom = collapsedSectionHeight
                val maxTop = (total - minBottom).coerceAtLeast(minTop)
                val target = when {
                    !isClarificationQuestionsExpanded && isClarificationPreviewExpanded -> minTop
                    isClarificationQuestionsExpanded && !isClarificationPreviewExpanded -> maxTop
                    !isClarificationQuestionsExpanded && !isClarificationPreviewExpanded -> minTop
                    else -> (total * 0.58).toInt()
                }.coerceIn(minTop, maxTop)
                clarificationSplitPane.dividerLocation = target
                clarificationSplitPane.revalidate()
                clarificationSplitPane.repaint()
            }
        } else {
            clarificationPreviewSection.isVisible = false
            clarificationSplitPane.bottomComponent = null
            clarificationSplitPane.resizeWeight = 1.0
            clarificationSplitPane.dividerSize = 0
        }
        clarificationSplitPane.revalidate()
        clarificationSplitPane.repaint()
    }

    private fun setClarificationPreviewVisible(visible: Boolean) {
        isClarificationPreviewContentVisible = visible
        refreshClarificationSectionsLayout()
    }

    private fun applyProcessTimelineCollapseState() {
        if (!::processTimelineBodyContainer.isInitialized) {
            return
        }
        processTimelineBodyContainer.isVisible = isProcessTimelineExpanded
        if (::processTimelineToggleButton.isInitialized) {
            updateCollapseToggleButton(
                processTimelineToggleButton,
                expanded = isProcessTimelineExpanded,
                enabled = true,
            )
        }
        if (::processTimelineSection.isInitialized) {
            processTimelineSection.revalidate()
            processTimelineSection.repaint()
        }
    }

    private fun setProcessTimelineVisible(visible: Boolean) {
        if (!::processTimelineSection.isInitialized) {
            return
        }
        processTimelineSection.isVisible = visible
        if (visible) {
            applyProcessTimelineCollapseState()
        }
        processTimelineSection.revalidate()
        processTimelineSection.repaint()
    }

    private fun renderProcessTimeline() {
        if (processTimelineEntries.isEmpty()) {
            processTimelinePane.text = ""
            setProcessTimelineVisible(false)
            return
        }
        setProcessTimelineVisible(true)
        val markdown = buildString {
            processTimelineEntries.forEach { entry ->
                appendLine("- ${processStatePrefix(entry.state)} ${entry.text}")
            }
        }.trimEnd()
        runCatching {
            MarkdownRenderer.render(processTimelinePane, markdown)
            processTimelinePane.caretPosition = 0
        }.onFailure {
            processTimelinePane.text = processTimelineEntries.joinToString("\n") { entry ->
                "${processStatePrefix(entry.state)} ${entry.text}"
            }
            processTimelinePane.caretPosition = 0
        }
    }

    private fun processStatePrefix(state: ProcessTimelineState): String {
        return when (state) {
            ProcessTimelineState.INFO -> "•"
            ProcessTimelineState.ACTIVE -> "→"
            ProcessTimelineState.DONE -> "✓"
            ProcessTimelineState.FAILED -> "✕"
        }
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
        setClarificationChecklistReadOnly(false)
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
        setClarificationChecklistReadOnly(false)
        renderPreviewMarkdown(buildValidationPreviewMarkdown(phase, validation))
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
        val displayContent = SpecMarkdownSanitizer.sanitize(content)
        previewSourceText = displayContent
        runCatching {
            MarkdownRenderer.render(previewPane, displayContent)
            previewPane.caretPosition = 0
        }.onFailure {
            previewPane.text = displayContent
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

    private fun buildValidationPreviewMarkdown(
        phase: SpecPhase,
        validation: ValidationResult,
    ): String {
        val documentContent = currentWorkflow
            ?.documents
            ?.get(phase)
            ?.content
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            .orEmpty()
        val issuesMarkdown = buildValidationIssuesMarkdown(phase, validation)
        if (documentContent.isBlank()) {
            return issuesMarkdown
        }
        return buildString {
            appendLine(documentContent)
            appendLine()
            appendLine("---")
            appendLine()
            append(issuesMarkdown)
        }.trim()
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
        val clarificationLocked = clarifying && isClarificationChecklistReadOnly
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
        updatePauseResumeButtonPresentation(workflow.status)
        styleActionButton(pauseResumeButton)
        openEditorButton.isEnabled = !isEditing && !clarifying && selectedPhase?.let { currentWorkflow?.documents?.containsKey(it) } == true
        historyDiffButton.isEnabled = !isEditing && !clarifying && selectedPhase?.let { currentWorkflow?.documents?.containsKey(it) } == true
        editButton.isEnabled = !isEditing && allowEditing && !clarifying
        saveButton.isEnabled = isEditing
        cancelEditButton.isEnabled = isEditing
        confirmGenerateButton.isEnabled = clarifying && inProgress && !isGeneratingActive && !clarificationLocked
        regenerateClarificationButton.isEnabled = clarifying && inProgress && !isGeneratingActive && !clarificationLocked
        skipClarificationButton.isEnabled = clarifying && inProgress && !isGeneratingActive && !clarificationLocked
        cancelClarificationButton.isEnabled = clarifying && !isGeneratingActive && !clarificationLocked
        refreshActionButtonCursors()
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
        refreshActionButtonCursors()
    }

    fun clearInput() {
        if (clarificationState?.structuredQuestions?.isNotEmpty() == true) {
            syncClarificationInputFromSelection(clarificationState)
            updateClarificationPreview()
            return
        }
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

    internal fun toggleClarificationQuestionForTest(index: Int) {
        val currentDecision = clarificationState
            ?.questionDecisions
            ?.get(index)
            ?: ClarificationQuestionDecision.UNDECIDED
        val nextDecision = if (currentDecision == ClarificationQuestionDecision.CONFIRMED) {
            ClarificationQuestionDecision.UNDECIDED
        } else {
            ClarificationQuestionDecision.CONFIRMED
        }
        onChecklistQuestionDecisionChanged(index, nextDecision)
    }

    internal fun markClarificationQuestionNotApplicableForTest(index: Int) {
        val currentDecision = clarificationState
            ?.questionDecisions
            ?.get(index)
            ?: ClarificationQuestionDecision.UNDECIDED
        val nextDecision = if (currentDecision == ClarificationQuestionDecision.NOT_APPLICABLE) {
            ClarificationQuestionDecision.UNDECIDED
        } else {
            ClarificationQuestionDecision.NOT_APPLICABLE
        }
        onChecklistQuestionDecisionChanged(index, nextDecision)
    }

    internal fun currentChecklistDecisionForTest(index: Int): String? {
        val state = clarificationState ?: return null
        return (state.questionDecisions[index] ?: ClarificationQuestionDecision.UNDECIDED).name
    }

    internal fun currentChecklistDetailForTest(index: Int): String? {
        return clarificationState?.questionDetails?.get(index)
    }

    internal fun setClarificationQuestionDetailForTest(index: Int, detail: String) {
        onChecklistQuestionDetailChanged(index, detail)
    }

    internal fun activeChecklistDetailIndexForTest(): Int? {
        return activeChecklistDetailIndex
    }

    internal fun isClarificationChecklistReadOnlyForTest(): Boolean {
        return isClarificationChecklistReadOnly
    }

    internal fun parseChecklistQuestionSegmentsForTest(question: String): List<Pair<String, Boolean>> {
        return parseChecklistQuestionSegments(question).map { it.text to it.bold }
    }

    internal fun isInputEditableForTest(): Boolean {
        return inputArea.isEditable
    }

    internal fun isInputSectionVisibleForTest(): Boolean {
        return ::inputSectionContainer.isInitialized && inputSectionContainer.isVisible
    }

    internal fun isBottomCollapsedForChecklistForTest(): Boolean {
        return isBottomCollapsedForChecklist
    }

    internal fun toggleProcessTimelineExpandedForTest() {
        if (::processTimelineToggleButton.isInitialized) {
            processTimelineToggleButton.doClick()
        }
    }

    internal fun toggleClarificationQuestionsExpandedForTest() {
        if (::clarificationQuestionsToggleButton.isInitialized) {
            clarificationQuestionsToggleButton.doClick()
        }
    }

    internal fun toggleClarificationPreviewExpandedForTest() {
        if (::clarificationPreviewToggleButton.isInitialized) {
            clarificationPreviewToggleButton.doClick()
        }
    }

    internal fun isProcessTimelineExpandedForTest(): Boolean {
        return isProcessTimelineExpanded
    }

    internal fun isClarificationQuestionsExpandedForTest(): Boolean {
        return isClarificationQuestionsExpanded
    }

    internal fun isClarificationPreviewExpandedForTest(): Boolean {
        return isClarificationPreviewExpanded
    }

    internal fun clarificationQuestionsToggleTextForTest(): String {
        return if (::clarificationQuestionsToggleButton.isInitialized) {
            clarificationQuestionsToggleButton.text
        } else {
            ""
        }
    }

    internal fun clickGenerateForTest() {
        generateButton.doClick()
    }

    internal fun clickConfirmGenerateForTest() {
        confirmGenerateButton.doClick()
    }

    internal fun isClarificationPreviewVisibleForTest(): Boolean {
        return isClarificationPreviewContentVisible
    }

    internal fun currentProcessTimelineTextForTest(): String {
        return processTimelinePane.text
    }

    internal fun isProcessTimelineVisibleForTest(): Boolean {
        return ::processTimelineSection.isInitialized && processTimelineSection.isVisible
    }

    internal fun buttonStatesForTest(): Map<String, Any> {
        return mapOf(
            "generateEnabled" to generateButton.isEnabled,
            "nextEnabled" to nextPhaseButton.isEnabled,
            "goBackEnabled" to goBackButton.isEnabled,
            "completeEnabled" to completeButton.isEnabled,
            "pauseResumeEnabled" to pauseResumeButton.isEnabled,
            "pauseResumeText" to (pauseResumeButton.toolTipText ?: pauseResumeButton.text),
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
        private val PROCESS_SECTION_BG = JBColor(Color(246, 251, 255), Color(55, 62, 73))
        private val PROCESS_SECTION_BORDER = JBColor(Color(199, 215, 237), Color(90, 101, 118))
        private val CLARIFICATION_CARD_BG = JBColor(Color(244, 249, 255), Color(56, 62, 72))
        private val CLARIFICATION_QUESTIONS_BG = JBColor(Color(249, 252, 255), Color(50, 56, 65))
        private val CLARIFICATION_QUESTIONS_BORDER = JBColor(Color(203, 216, 235), Color(84, 95, 110))
        private val CHECKLIST_ROW_BG = JBColor(Color(247, 251, 255), Color(59, 66, 76))
        private val CHECKLIST_ROW_BG_SELECTED = JBColor(Color(234, 244, 255), Color(73, 88, 109))
        private val CHECKLIST_ROW_BG_NA = JBColor(Color(250, 248, 243), Color(77, 74, 69))
        private val CHECKLIST_ROW_BORDER = JBColor(Color(210, 221, 238), Color(96, 108, 126))
        private val CHECKLIST_ROW_BORDER_SELECTED = JBColor(Color(159, 187, 224), Color(126, 152, 182))
        private val CHECKLIST_ROW_BORDER_NA = JBColor(Color(223, 210, 192), Color(126, 120, 110))
        private val CHECKLIST_CONFIRM_BG = JBColor(Color(223, 238, 255), Color(74, 95, 120))
        private val CHECKLIST_CONFIRM_TEXT = JBColor(Color(47, 91, 148), Color(192, 219, 255))
        private val CHECKLIST_NA_BG = JBColor(Color(243, 235, 223), Color(94, 88, 78))
        private val CHECKLIST_NA_TEXT = JBColor(Color(128, 101, 66), Color(232, 204, 166))
        private val CHECKLIST_CHOICE_BG = JBColor(Color(245, 249, 255), Color(68, 75, 86))
        private val CHECKLIST_CHOICE_BORDER = JBColor(Color(194, 207, 228), Color(103, 114, 130))
        private val CHECKLIST_DETAIL_BG = JBColor(Color(248, 252, 255), Color(64, 71, 81))
        private val CHECKLIST_DETAIL_BORDER = JBColor(Color(198, 211, 230), Color(98, 110, 127))
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
        private val COLLAPSE_TOGGLE_TEXT_ACTIVE = JBColor(Color(86, 115, 158), Color(187, 205, 230))
        private const val MAX_PROCESS_TIMELINE_ENTRIES = 18
        private const val CARD_PREVIEW = "preview"
        private const val CARD_EDIT = "edit"
        private const val CARD_CLARIFY = "clarify"
        private const val CLARIFY_QUESTIONS_CARD_MARKDOWN = "clarify.questions.markdown"
        private const val CLARIFY_QUESTIONS_CARD_CHECKLIST = "clarify.questions.checklist"
        private val BOLD_MARKDOWN_REGEX = Regex("\\*\\*(.+?)\\*\\*")
        private val DETAIL_LINE_REGEX = Regex("^-\\s*(detail|details|补充|说明)\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
    }
}
