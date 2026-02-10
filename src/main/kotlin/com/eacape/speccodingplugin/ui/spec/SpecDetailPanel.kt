package com.eacape.speccodingplugin.ui.spec

import com.eacape.speccodingplugin.spec.SpecDocument
import com.eacape.speccodingplugin.spec.SpecPhase
import com.eacape.speccodingplugin.spec.SpecWorkflow
import com.eacape.speccodingplugin.spec.WorkflowStatus
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class SpecDetailPanel(
    private val onGenerate: (String) -> Unit,
    private val onNextPhase: () -> Unit,
    private val onGoBack: () -> Unit,
    private val onComplete: () -> Unit,
    private val onPauseResume: () -> Unit,
    private val onOpenInEditor: (SpecPhase) -> Unit
) : JPanel(BorderLayout()) {

    private val treeRoot = DefaultMutableTreeNode("Documents")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val documentTree = JTree(treeModel)

    private val previewArea = JBTextArea()
    private val validationLabel = JBLabel("")
    private val inputArea = JBTextArea(3, 40)

    private val generateButton = JButton("Generate")
    private val nextPhaseButton = JButton("Next Phase")
    private val goBackButton = JButton("Go Back")
    private val completeButton = JButton("Complete")
    private val pauseResumeButton = JButton("Pause")
    private val openEditorButton = JButton("Open in Editor")

    private var currentWorkflow: SpecWorkflow? = null
    private var selectedPhase: SpecPhase? = null

    init {
        setupUI()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(4)

        // 上半部分：文档树 + 预览
        val topSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        topSplit.dividerLocation = 160

        // 文档树
        documentTree.isRootVisible = false
        documentTree.addTreeSelectionListener {
            val node = documentTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val phase = node?.userObject as? PhaseNode
            if (phase != null) {
                selectedPhase = phase.phase
                showDocumentPreview(phase.phase)
            }
        }
        topSplit.leftComponent = JBScrollPane(documentTree)

        // 预览区
        val previewPanel = JPanel(BorderLayout())
        previewArea.isEditable = false
        previewArea.lineWrap = true
        previewArea.wrapStyleWord = true
        previewPanel.add(JBScrollPane(previewArea), BorderLayout.CENTER)
        validationLabel.border = JBUI.Borders.empty(4)
        previewPanel.add(validationLabel, BorderLayout.SOUTH)
        topSplit.rightComponent = previewPanel

        // 下半部分：输入 + 按钮
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.border = JBUI.Borders.emptyTop(8)

        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.emptyText.setText("Describe your requirements...")
        val inputScroll = JBScrollPane(inputArea)
        inputScroll.preferredSize = java.awt.Dimension(0, JBUI.scale(80))
        bottomPanel.add(inputScroll, BorderLayout.CENTER)

        // 按钮栏
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        setupButtons(buttonPanel)
        bottomPanel.add(buttonPanel, BorderLayout.SOUTH)

        // 组合
        val mainSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        mainSplit.topComponent = topSplit
        mainSplit.bottomComponent = bottomPanel
        mainSplit.resizeWeight = 0.65
        add(mainSplit, BorderLayout.CENTER)
    }

    private fun setupButtons(panel: JPanel) {
        generateButton.addActionListener {
            val text = inputArea.text.trim()
            if (text.isNotBlank()) onGenerate(text)
        }
        nextPhaseButton.addActionListener { onNextPhase() }
        goBackButton.addActionListener { onGoBack() }
        completeButton.addActionListener { onComplete() }
        pauseResumeButton.addActionListener { onPauseResume() }
        openEditorButton.addActionListener {
            selectedPhase?.let { onOpenInEditor(it) }
        }

        panel.add(generateButton)
        panel.add(nextPhaseButton)
        panel.add(goBackButton)
        panel.add(completeButton)
        panel.add(pauseResumeButton)
        panel.add(openEditorButton)

        disableAllButtons()
    }

    fun updateWorkflow(workflow: SpecWorkflow?) {
        currentWorkflow = workflow
        if (workflow == null) {
            showEmpty()
            return
        }
        rebuildTree(workflow)
        updateButtonStates(workflow)
        // 自动选中当前阶段
        selectedPhase = workflow.currentPhase
        showDocumentPreview(workflow.currentPhase)
    }

    fun showEmpty() {
        treeRoot.removeAllChildren()
        treeModel.reload()
        previewArea.text = ""
        validationLabel.text = "Select a workflow to view details"
        disableAllButtons()
    }

    fun showGenerating(progress: Double) {
        val pct = (progress * 100).toInt()
        validationLabel.text = "Generating... $pct%"
        generateButton.isEnabled = false
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

    private fun showDocumentPreview(phase: SpecPhase) {
        val doc = currentWorkflow?.documents?.get(phase)
        if (doc != null) {
            previewArea.text = doc.content
            previewArea.caretPosition = 0
            val vr = doc.validationResult
            if (vr != null) {
                validationLabel.text = if (vr.valid) "Validation: PASSED" else "Validation: FAILED"
                validationLabel.foreground = if (vr.valid)
                    JBColor(java.awt.Color(76, 175, 80), java.awt.Color(76, 175, 80))
                else
                    JBColor(java.awt.Color(244, 67, 54), java.awt.Color(239, 83, 80))
            } else {
                validationLabel.text = "No validation result"
                validationLabel.foreground = JBColor.GRAY
            }
        } else {
            previewArea.text = "(No document generated yet for ${phase.displayName})"
            validationLabel.text = ""
        }
    }

    private fun updateButtonStates(workflow: SpecWorkflow) {
        val inProgress = workflow.status == WorkflowStatus.IN_PROGRESS
        generateButton.isEnabled = inProgress
        nextPhaseButton.isEnabled = workflow.canProceedToNext() && inProgress
        goBackButton.isEnabled = workflow.canGoBack() && inProgress
        completeButton.isEnabled = workflow.currentPhase == SpecPhase.IMPLEMENT
                && workflow.getDocument(SpecPhase.IMPLEMENT)?.validationResult?.valid == true
                && inProgress
        pauseResumeButton.isEnabled = true
        pauseResumeButton.text = if (workflow.status == WorkflowStatus.PAUSED) "Resume" else "Pause"
        openEditorButton.isEnabled = selectedPhase?.let { currentWorkflow?.documents?.containsKey(it) } == true
    }

    private fun disableAllButtons() {
        generateButton.isEnabled = false
        nextPhaseButton.isEnabled = false
        goBackButton.isEnabled = false
        completeButton.isEnabled = false
        pauseResumeButton.isEnabled = false
        openEditorButton.isEnabled = false
    }

    private data class PhaseNode(val phase: SpecPhase, val document: SpecDocument?) {
        override fun toString(): String {
            val status = when {
                document?.validationResult?.valid == true -> "[done]"
                document != null -> "[draft]"
                else -> ""
            }
            return "${phase.displayName}: ${phase.outputFileName} $status"
        }
    }
}
