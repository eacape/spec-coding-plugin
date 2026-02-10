# Spec Coding Plugin

> 规格驱动的 AI 编码工作流插件 for JetBrains IDEs

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blue.svg)](https://kotlinlang.org/)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.2+-orange.svg)](https://plugins.jetbrains.com/)
[![Phase 1](https://img.shields.io/badge/Phase%201-Complete-brightgreen.svg)](PHASE1-COMPLETE.md)
[![License](https://img.shields.io/badge/License-TBD-green.svg)](LICENSE)

---

## 🎉 Phase 1 完成！

**Phase 1 MVP 已完成**（2026-02-10）- 所有核心功能已实现，包括：
- ✅ 8/8 核心模块完成
- ✅ ~4,630 行高质量代码
- ✅ 50+ 单元测试用例（~50% 覆盖率）
- ✅ 完整的文档体系

详见 [Phase 1 完成报告](PHASE1-COMPLETE.md)

---

## 📖 项目简介

**Spec Coding Plugin** 是一个为 JetBrains IDE 开发的规格驱动 AI 编码工作流插件。从 "Vibe Coding"（凭感觉编码）进化到 "Spec Coding"（规格驱动编码），通过结构化的规格说明驱动 AI 生成可预测、可审计、可迭代的代码。

### 核心特性

- 🤖 **多模型支持**: OpenAI (GPT-4o/O1) 和 Anthropic (Claude Opus 4/Sonnet 4)
- 📝 **三层提示词管理**: 全局 -> 项目 -> 会话级继承（含继承解析器和导入导出）
- ⚡ **技能系统**: 技能注册表 + 执行器 + 斜杠命令解析
- 🔄 **流式响应**: 实时显示 AI 生成过程
- 🎯 **操作模式系统**: 4 种模式（DEFAULT/PLAN/AGENT/AUTO）+ 权限矩阵 + 安全熔断
- 🔀 **模型切换器**: 状态栏 Widget + 8 个预注册模型
- 🔐 **安全存储**: API Key 使用 IDE PasswordSafe 加密存储

---

## 🚀 快速开始

### 环境要求

- **JDK**: 17 或更高版本
- **IDE**: IntelliJ IDEA 2024.2+ / WebStorm / PyCharm 等
- **Gradle**: 8.x（项目包含 Wrapper）

### 构建与运行

```bash
# 构建插件
./gradlew buildPlugin

# 在沙箱 IDE 中运行（开发调试）
./gradlew runIde

# 运行测试
./gradlew test

# 生成代码覆盖率报告
./gradlew koverHtmlReport
```

### 安装插件

1. 构建插件：`./gradlew buildPlugin`
2. 在 IDE 中打开 `Settings -> Plugins -> Install Plugin from Disk`
3. 选择 `build/distributions/spec-coding-plugin-*.zip`
4. 重启 IDE

---

## 📚 功能概览

### 1. 多模型提供者抽象层

统一的 LLM 调用接口，支持多个 AI 模型提供者：

```kotlin
val provider = OpenAiProvider(apiKey = "sk-...")
val request = LlmRequest(
    messages = listOf(
        LlmMessage(LlmRole.USER, "Explain this code")
    ),
    model = "gpt-4o"
)

// 流式调用
provider.stream(request) { chunk ->
    println(chunk.delta)
}
```

**支持的提供者：**
- ✅ OpenAI (GPT-4o, o1-preview, o1-mini)
- ✅ Anthropic (Claude Opus 4, Sonnet 4.5, Haiku 4.5)
- 🔄 Google Gemini (计划中)
- 🔄 本地模型 Ollama (计划中)

### 2. 三层提示词管理

灵活的提示词继承机制：

```
全局级 (~/.spec-coding/prompts/)
    ↓ 继承
项目级 (.spec-coding/prompts/)
    ↓ 继承
会话级 (临时覆盖)
```

**使用示例：**

```kotlin
val manager = PromptManager.getInstance(project)

// 列出所有提示词
val templates = manager.listPromptTemplates()

// 设置活跃提示词
manager.setActivePrompt("code-review")

// 渲染提示词（应用变量替换）
val rendered = manager.renderActivePrompt(
    mapOf("language" to "Kotlin")
)
```

### 3. 技能系统

可扩展的 AI 能力单元，支持斜杠命令：

| 命令 | 功能 | 上下文要求 |
|------|------|-----------|
| `/review` | 代码审查 | 选中的代码 |
| `/explain` | 代码解释 | 选中的代码 |
| `/refactor` | 代码重构 | 选中的代码 |
| `/test` | 生成测试 | 选中的代码 |
| `/fix` | 修复 Bug | 选中的代码 |

**自定义技能：**

在 `.spec-coding/skills/` 目录创建 YAML 文件：

```yaml
id: tdd-workflow
name: TDD Workflow
description: Test-driven development workflow
slash_command: tdd
prompt_template: |
  Execute TDD workflow:
  1. Write failing tests
  2. Implement minimal code
  3. Refactor

  Code: {{selected_code}}
context_requirements:
  - SELECTED_CODE
tags:
  - testing
  - tdd
enabled: true
```

---

## 📂 项目结构

```
spec-coding-plugin/
├── src/main/kotlin/com/eacape/speccodingplugin/
│   ├── llm/              # LLM 抽象层
│   │   ├── LlmProvider.kt
│   │   ├── OpenAiProvider.kt
│   │   ├── AnthropicProvider.kt
│   │   └── LlmRouter.kt
│   ├── prompt/           # 提示词管理
│   │   ├── PromptManager.kt
│   │   ├── GlobalPromptManager.kt
│   │   └── PromptInterpolator.kt
│   ├── skill/            # 技能系统
│   │   ├── SkillRegistry.kt
│   │   ├── SkillExecutor.kt
│   │   └── SkillModels.kt
│   ├── core/             # 核心服务
│   ├── ui/               # UI 组件
│   ├── mcp/              # MCP 集成（计划中）
│   ├── worktree/         # Worktree 管理（计划中）
│   └── hook/             # Hook 系统（计划中）
├── docs/                 # 项目文档
│   ├── spec-coding-plugin-plan.md
│   ├── dev-checklist.md
│   ├── progress-report.md
│   └── project-summary.md
├── build.gradle.kts
└── README.md
```

---

## 🎯 开发进度

### Phase 1 - 基础框架与核心对话（MVP）

**完成度：100%** ✅

- ✅ 多模型提供者抽象层（OpenAI + Anthropic）
- ✅ 三层提示词管理系统（含继承解析器和导入导出）
- ✅ 技能系统（注册表 + 执行器）
- ✅ Chat Tool Window UI
- ✅ Settings 页面
- ✅ 模型切换器（状态栏 Widget）
- ✅ 操作模式系统（4 种模式 + 权限矩阵 + 安全熔断）
- ✅ Gradle Wrapper
- ✅ 单元测试（50+ 用例，~50% 覆盖率）

### Phase 2 - Spec 工作流与 MCP 集成（进行中）

**完成度：30%**

- ✅ Spec 工作流引擎（三阶段模型）
- ⏳ MCP 集成
- ⏳ 智能上下文引擎
- ⏳ 输入框增强
- ⏳ Spec Tab UI

详细进度请查看：
- [Phase 2 开发计划](docs/phase2-plan.md)
- [Phase 2 开发进度](docs/phase2-progress.md)
- [Phase 2 会话总结](docs/phase2-session-1-summary.md)
- [开发清单](docs/dev-checklist.md)

---

## 🛠️ 技术栈

| 层次 | 技术选择 |
|------|----------|
| 开发语言 | Kotlin 2.0+ |
| 构建工具 | Gradle 8.x + IntelliJ Platform Plugin 2.x |
| IDE 平台 | IntelliJ Platform SDK 2024.2+ |
| HTTP 客户端 | Ktor Client 2.3 |
| JSON 序列化 | kotlinx.serialization 1.7 |
| YAML 解析 | SnakeYAML 2.2 |
| 协程 | kotlinx.coroutines 1.8 |
| 测试框架 | JUnit 5 + MockK |

---

## 📖 文档

### 核心文档
- [Phase 1 完成报告](PHASE1-COMPLETE.md) - Phase 1 开发完成总结
- [产品规划文档](docs/spec-coding-plugin-plan.md) - 完整的产品设计和技术架构
- [开发清单](docs/dev-checklist.md) - 分阶段的开发任务清单
- [项目状态](docs/project-status.md) - 当前项目状态
- [最终总结](docs/final-summary.md) - 项目最终总结报告

### 构建文档
- [构建验证报告](docs/build-verification-report.md) - 构建就绪状态分析
- [快速构建指南](docs/build-quick-guide.md) - 构建和测试命令速查表
- [交付清单](docs/delivery-checklist.md) - Phase 1 交付物清单

### 开发文档
- [开发进度记录](docs/dev-process.md) - 详细的开发过程记录
- [会话总结](docs/session-4-summary.md) - 最新会话总结
- [快速参考](docs/quick-reference.md) - 快速参考指南

---

## 🤝 贡献指南

欢迎贡献代码、报告问题或提出建议！

### 开发流程

1. Fork 项目
2. 创建功能分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

### 代码规范

- 遵循 Kotlin 官方编码风格
- 使用 `val` 优先于 `var`
- 避免使用 `!!` 强制非空断言
- 函数长度 < 50 行
- 单元测试覆盖率 >= 80%

详细规范请查看 [.claude/rules/](/.claude/rules/) 目录。

---

## 🔒 安全性

- API Key 使用 IntelliJ Platform 的 `PasswordSafe` 加密存储
- 不明文存储任何敏感信息
- 所有 HTTP 请求强制使用 HTTPS
- 文件路径验证防止路径遍历攻击
- 命令参数化防止命令注入

详细安全规范请查看 [.claude/rules/security.md](/.claude/rules/security.md)。

---

## 📝 许可证

待定

---

## 📧 联系方式

- **项目地址**: D:\eacape\spec-coding-plugin
- **问题反馈**: 通过 Issue 提交
- **文档**: [docs/](docs/) 目录

---

## 🙏 致谢

感谢以下开源项目和工具：

- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/)
- [Kotlin](https://kotlinlang.org/)
- [Ktor](https://ktor.io/)
- [OpenAI API](https://platform.openai.com/)
- [Anthropic API](https://www.anthropic.com/)

---

**最后更新**: 2026-02-10 | **版本**: 0.1.0-dev | **状态**: Phase 1 完成 ✅

**下一步**: 构建验证 → 集成测试 → Phase 2 开发
