package com.eacape.speccodingplugin.ui.chat

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JTextPane
import javax.swing.SwingUtilities

class MarkdownRendererTest {

    @Test
    fun `render should support level six heading syntax`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(pane, "###### 2.1 数据采集模块")
        }

        assertTrue(pane.text.contains("2.1 数据采集模块"))
        assertFalse(pane.text.contains("######"))
    }

    @Test
    fun `render should support indented heading syntax`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(pane, "  ####  details")
        }

        assertTrue(pane.text.contains("details"))
        assertFalse(pane.text.contains("####"))
    }

    @Test
    fun `render should remove code fences and keep code body`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                代码示例
                ```
                const id = 1;
                if (id) {
                    console.log(id);
                }
                ```
                结束
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(text.contains("代码示例"))
        assertTrue(text.contains("const id = 1;"))
        assertTrue(text.contains("console.log(id);"))
        assertTrue(text.contains("结束"))
        assertFalse(text.contains("```"))
    }

    @Test
    fun `render should convert markdown table with markdown engine`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                | 层 | 选型 |
                | --- | --- |
                | 前端 | React |
                | 后端 | Kotlin |
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(text.contains("| 层 | 选型 |"))
        assertFalse(text.contains("| ---"))
    }

    @Test
    fun `render should keep plain pipe text when separator row is missing`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                A | B
                plain
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(text.contains("A | B"))
        assertFalse(text.contains("| ---"))
    }

    @Test
    fun `render should convert wide multi-column table with markdown engine`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                | 任务 | 说明 | 状态 |
                | --- | --- | --- |
                | 渲染优化 | 解决长文本表格在窄区域内换行错乱导致阅读困难的问题 | 进行中 |
                | 交互优化 | 保持布局轻量并提升可读性与信息密度的一致性 | 待验证 |
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(text.contains("| 任务 | 说明 | 状态 |"))
        assertFalse(text.contains("| ---"))
    }

    @Test
    fun `render should support escaped pipe table rows with markdown engine`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                \| 维度 | 方案 |
                \| --- | --- |
                \| 架构 | 模块化 |
                \| 流程 | 规范化 |
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(text.contains("\\| 维度 | 方案 |"))
        assertFalse(text.contains("| ---"))
    }

    @Test
    fun `render should support fullwidth pipe table rows with markdown engine`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                ｜ 维度 ｜ 方案 ｜
                ｜ --- ｜ --- ｜
                ｜ 架构 ｜ 模块化 ｜
                ｜ 流程 ｜ 规范化 ｜
                """.trimIndent(),
            )
        }

        val text = pane.text
        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(text.contains("｜ 维度 ｜ 方案 ｜"))
        assertFalse(text.contains("｜ --- ｜ --- ｜"))
    }

    @Test
    fun `render should switch back to plain mode after html table rendering`() {
        val pane = JTextPane()

        runOnEdt {
            MarkdownRenderer.render(
                pane,
                """
                | A | B |
                | --- | --- |
                | 1 | 2 |
                """.trimIndent(),
            )
            MarkdownRenderer.render(pane, "plain text")
        }

        assertTrue(pane.contentType.contains("plain"))
        assertTrue(pane.text.contains("plain text"))
    }

    @Test
    fun `render should parse user reported comparison table content as html table`() {
        val pane = JTextPane()
        val markdown = """
            按你说的两个工具，我这里默认是对比 **GitHub 的 Spec Kit（常被叫 speckit）** 和 **Fission-AI 的 OpenSpec**。
            | 对比项 | speckit（`github/spec-kit`） | OpenSpec（`Fission-AI/OpenSpec`） |
            |---|---|---|
            | 核心定位 | Spec-Driven Development 工具包，强调从规格到实现的完整流水线 | 轻量 spec 框架，强调“先对齐需求再写代码”，并把 spec 长期留在仓库里 |
            | 安装方式 | `uv tool install specify-cli --from git+https://github.com/github/spec-kit.git` | `npm install -g @fission-ai/openspec@latest` |
            | 运行前置 | `uv` + Python 3.11+ + Git + 支持的 AI agent | Node.js 20.19.0+ |
            | 主要命令流 | `/speckit.constitution` → `/speckit.specify` → `/speckit.plan` → `/speckit.tasks` → `/speckit.implement` | `/opsx:propose` → `/opsx:apply` → `/opsx:archive`（也有扩展命令） |
            | 工件目录风格 | 主要在 `.specify/` 下组织（memory/specs/templates/scripts） | 主要在 `openspec/` 下组织，强调 `openspec/specs`（真相源）+ `openspec/changes`（变更提案） |
            | 适配场景 | 官方同时覆盖 0→1（greenfield）和迭代增强 | 明确强调 brownfield（存量项目改造）优势，支持跨会话/跨人协作 |
            | Agent/工具支持 | 有明确“Supported AI Agents”列表（Codex/Cursor/Copilot 等） | 官网强调通用、原生支持多工具（20+） |
            | API Key / MCP 立场 | 框架本身不主打该点，更多依赖你使用的 AI 工具能力 | 官网明确写了 “No API Keys / No MCP” |
            | 遥测 | README 未突出遥测策略 | README 明确有匿名 telemetry，可通过环境变量关闭 |
            | 开源与社区热度（截至 2026-03-03） | MIT；约 73.5k stars | MIT；约 27k stars |
        """.trimIndent()

        runOnEdt {
            MarkdownRenderer.render(pane, markdown)
        }

        assertTrue(pane.contentType.contains("html"))
        assertTrue(pane.document.length > 0)
        assertFalse(pane.text.contains("| 对比项 |"))
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }
        SwingUtilities.invokeAndWait(block)
    }
}
