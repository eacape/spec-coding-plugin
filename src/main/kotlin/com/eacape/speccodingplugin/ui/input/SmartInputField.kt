package com.eacape.speccodingplugin.ui.input

import com.eacape.speccodingplugin.ui.completion.CompletionItem
import com.eacape.speccodingplugin.ui.completion.TriggerParser
import com.eacape.speccodingplugin.ui.completion.TriggerParseResult
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.JTextArea
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 增强输入框
 * 基于 JTextArea，支持触发字符检测、补全弹窗、Enter 发送 / Shift+Enter 换行
 */
class SmartInputField(
    private val placeholder: String = "",
    private val onSend: (String) -> Unit = {},
    private val onTrigger: (TriggerParseResult) -> Unit = {},
    private val onTriggerDismiss: () -> Unit = {},
    private val onCompletionSelect: (CompletionItem) -> Unit = {},
) : JTextArea() {

    private var debounceTimer: Timer? = null
    private var lastTrigger: TriggerParseResult? = null

    val completionPopup = CompletionPopup(::handleCompletionSelect)

    init {
        rows = 1
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6, 8)
        font = JBUI.Fonts.label()

        setupKeyListener()
        setupDocumentListener()
    }

    private fun setupKeyListener() {
        addKeyListener(object : KeyListener {
            override fun keyTyped(e: KeyEvent) {}
            override fun keyReleased(e: KeyEvent) {}

            override fun keyPressed(e: KeyEvent) {
                when {
                    // Escape: dismiss popup
                    e.keyCode == KeyEvent.VK_ESCAPE && completionPopup.isVisible -> {
                        completionPopup.hide()
                        onTriggerDismiss()
                        e.consume()
                    }
                    // Up arrow: navigate popup
                    e.keyCode == KeyEvent.VK_UP && completionPopup.isVisible -> {
                        completionPopup.moveUp()
                        e.consume()
                    }
                    // Down arrow: navigate popup
                    e.keyCode == KeyEvent.VK_DOWN && completionPopup.isVisible -> {
                        completionPopup.moveDown()
                        e.consume()
                    }
                    // Enter: confirm popup selection or send
                    e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown -> {
                        if (completionPopup.isVisible) {
                            if (completionPopup.confirmSelection()) {
                                e.consume()
                            }
                        } else {
                            e.consume()
                            val input = text.trim()
                            if (input.isNotBlank()) {
                                onSend(input)
                            }
                        }
                    }
                    // Tab: confirm popup selection
                    e.keyCode == KeyEvent.VK_TAB && completionPopup.isVisible -> {
                        completionPopup.confirmSelection()
                        e.consume()
                    }
                }
            }
        })
    }

    private fun setupDocumentListener() {
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = scheduleCheck()
            override fun removeUpdate(e: DocumentEvent) = scheduleCheck()
            override fun changedUpdate(e: DocumentEvent) = scheduleCheck()
        })
    }

    private fun scheduleCheck() {
        debounceTimer?.stop()
        debounceTimer = Timer(150) { checkTrigger() }
        debounceTimer?.isRepeats = false
        debounceTimer?.start()
    }

    private fun checkTrigger() {
        val currentText = text ?: ""
        val caret = caretPosition

        val result = TriggerParser.parse(currentText, caret)
        if (result != null) {
            lastTrigger = result
            onTrigger(result)
        } else if (lastTrigger != null) {
            lastTrigger = null
            completionPopup.hide()
            onTriggerDismiss()
        }
    }

    private fun handleCompletionSelect(item: CompletionItem) {
        val trigger = lastTrigger ?: return
        val before = text.substring(0, trigger.triggerOffset)
        val after = text.substring(caretPosition)

        // For @ and # triggers, don't insert the full text into the input;
        // instead clear the trigger text and notify the parent via callback
        if (item.contextItem != null) {
            text = before + after
            caretPosition = before.length
        } else {
            text = before + item.insertText + " " + after
            caretPosition = (before + item.insertText + " ").length
        }

        lastTrigger = null
        onCompletionSelect(item)
    }

    fun showCompletions(items: List<CompletionItem>) {
        if (items.isEmpty()) {
            completionPopup.hide()
            return
        }
        completionPopup.show(items, this)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        // Draw placeholder when empty
        if (text.isNullOrEmpty() && placeholder.isNotBlank()) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            )
            g2.color = JBColor.GRAY
            g2.font = font
            val fm = g2.fontMetrics
            val x = insets.left
            val y = insets.top + fm.ascent
            g2.drawString(placeholder, x, y)
        }
    }
}
