package com.eacape.speccodingplugin.context

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * 编辑器上下文提供者
 * 从当前编辑器状态收集上下文信息
 */
object EditorContextProvider {

    /**
     * 获取当前编辑器文件的上下文
     */
    fun getCurrentFileContext(project: Project): ContextItem? {
        val editor = getActiveEditor(project) ?: return null
        val virtualFile = editor.virtualFile ?: return null

        val content = ReadAction.compute<String, Throwable> {
            editor.document.text
        }

        if (content.isBlank()) return null

        return ContextItem(
            type = ContextType.CURRENT_FILE,
            label = virtualFile.name,
            content = content,
            filePath = virtualFile.path,
            priority = 70,
        )
    }

    /**
     * 获取选中代码的上下文
     */
    fun getSelectedCodeContext(project: Project): ContextItem? {
        val editor = getActiveEditor(project) ?: return null
        val virtualFile = editor.virtualFile

        val selectedText = ReadAction.compute<String?, Throwable> {
            val selectionModel = editor.selectionModel
            if (selectionModel.hasSelection()) {
                selectionModel.selectedText
            } else {
                null
            }
        }

        if (selectedText.isNullOrBlank()) return null

        return ContextItem(
            type = ContextType.SELECTED_CODE,
            label = "Selection in ${virtualFile?.name ?: "editor"}",
            content = selectedText,
            filePath = virtualFile?.path,
            priority = 90,
        )
    }

    /**
     * 获取光标所在作用域（函数/类）的上下文
     * 使用通用 PsiNamedElement，不依赖语言特定 API
     */
    fun getContainingScopeContext(project: Project): ContextItem? {
        val editor = getActiveEditor(project) ?: return null
        val virtualFile = editor.virtualFile ?: return null
        val offset = editor.caretModel.offset

        return ReadAction.compute<ContextItem?, Throwable> {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@compute null
            val elementAtCaret = psiFile.findElementAt(offset)
                ?: return@compute null

            val scope = findContainingScope(elementAtCaret)
                ?: return@compute null

            val scopeText = scope.text
            if (scopeText.isNullOrBlank()) return@compute null

            val name = (scope as? PsiNamedElement)?.name ?: "anonymous"

            ContextItem(
                type = ContextType.CONTAINING_SCOPE,
                label = name,
                content = scopeText,
                filePath = virtualFile.path,
                priority = 80,
            )
        }
    }

    private fun findContainingScope(element: PsiElement): PsiElement? {
        // Walk up the PSI tree to find the nearest named scope
        var current = PsiTreeUtil.getParentOfType(
            element,
            PsiNamedElement::class.java,
            /* strict = */ true,
        )

        // Skip trivially small elements (parameters, variables)
        while (current != null && current.textLength < 20) {
            current = PsiTreeUtil.getParentOfType(
                current,
                PsiNamedElement::class.java,
                /* strict = */ true,
            )
        }

        return current
    }

    private fun getActiveEditor(project: Project): Editor? {
        return FileEditorManager.getInstance(project).selectedTextEditor
    }
}
