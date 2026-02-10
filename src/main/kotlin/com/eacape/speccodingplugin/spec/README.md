# Spec 工作流引擎

> 规格驱动的三阶段开发工作流

---

## 📋 概述

Spec 工作流引擎是 Spec Coding Plugin 的核心模块，实现了从自然语言需求到可执行代码的三阶段转换流程。

### 三个阶段

```
自然语言需求
    ↓
┌─────────────────┐
│  1. Specify     │  需求规格化
│  requirements.md│
└─────────────────┘
    ↓
┌─────────────────┐
│  2. Design      │  技术方案设计
│  design.md      │
└─────────────────┘
    ↓
┌─────────────────┐
│  3. Implement   │  任务拆解与实现
│  tasks.md       │
└─────────────────┘
    ↓
可执行代码
```

---

## 🏗️ 架构设计

### 核心组件

#### 1. SpecEngine
工作流引擎，负责状态机管理和阶段流转。

**核心功能**:
- 创建和管理工作流
- 阶段流转控制
- 门控验证
- 工作流状态管理

#### 2. SpecGenerator
文档生成器，使用 LLM 生成各阶段文档。

**核心功能**:
- 构建阶段特定的 Prompt
- 调用 LLM 生成文档
- 应用文档模板

#### 3. SpecValidator
文档验证器，验证各阶段文档的完整性。

**核心功能**:
- 检查必需章节
- 验证内容质量
- 提供改进建议

#### 4. SpecStorage
文档存储管理器，负责文档的持久化。

**核心功能**:
- 保存/加载文档
- 工作流元数据管理
- 文件系统操作

---

## 📝 数据模型

### SpecPhase
```kotlin
enum class SpecPhase {
    SPECIFY,    // 需求规格化
    DESIGN,     // 技术方案设计
    IMPLEMENT   // 任务拆解与实现
}
```

### SpecDocument
```kotlin
data class SpecDocument(
    val id: String,
    val phase: SpecPhase,
    val content: String,
    val metadata: SpecMetadata,
    val validationResult: ValidationResult?
)
```

### SpecWorkflow
```kotlin
data class SpecWorkflow(
    val id: String,
    val currentPhase: SpecPhase,
    val documents: Map<SpecPhase, SpecDocument>,
    val status: WorkflowStatus
)
```

---

## 🚀 使用示例

### 创建工作流

```kotlin
val engine = SpecEngine.getInstance(project)

// 创建新工作流
val workflow = engine.createWorkflow(
    title = "用户登录功能",
    description = "实现用户登录和认证功能"
).getOrThrow()
```

### 生成 Specify 阶段文档

```kotlin
// 生成需求文档
engine.generateCurrentPhase(
    workflowId = workflow.id,
    input = """
        我需要实现一个用户登录功能：
        1. 用户可以使用邮箱和密码登录
        2. 支持记住登录状态
        3. 登录失败显示错误提示
    """.trimIndent()
).collect { progress ->
    when (progress) {
        is SpecGenerationProgress.Started ->
            println("开始生成 ${progress.phase.displayName} 文档")

        is SpecGenerationProgress.Generating ->
            println("生成中... ${(progress.progress * 100).toInt()}%")

        is SpecGenerationProgress.Completed ->
            println("生成完成: ${progress.document.content}")

        is SpecGenerationProgress.ValidationFailed ->
            println("验证失败: ${progress.validation.getSummary()}")

        is SpecGenerationProgress.Failed ->
            println("生成失败: ${progress.error}")
    }
}
```

### 进入下一阶段

```kotlin
// 进入 Design 阶段
val updatedWorkflow = engine.proceedToNextPhase(workflow.id).getOrThrow()

// 生成设计文档
engine.generateCurrentPhase(
    workflowId = workflow.id,
    input = "基于需求文档设计技术方案"
).collect { progress ->
    // 处理进度
}
```

### 完成工作流

```kotlin
// 进入 Implement 阶段
engine.proceedToNextPhase(workflow.id)

// 生成任务文档
engine.generateCurrentPhase(
    workflowId = workflow.id,
    input = "拆解实现任务"
).collect { progress ->
    // 处理进度
}

// 完成工作流
val completedWorkflow = engine.completeWorkflow(workflow.id).getOrThrow()
```

---

## 📂 文件存储

### 目录结构

```
.spec-coding/
└── specs/
    └── spec-{timestamp}-{id}/
        ├── workflow.yaml        # 工作流元数据
        ├── requirements.md      # Specify 阶段文档
        ├── design.md            # Design 阶段文档
        └── tasks.md             # Implement 阶段文档
```

### 文档格式

每个文档包含：
- 标题和元数据
- 阶段信息
- 文档内容
- 验证结果（如果有）

示例：
```markdown
# 用户登录功能 - 需求文档

**阶段**: Specify
**作者**: developer
**创建时间**: 2026-02-10 10:00:00
**版本**: 1.0.0

---

## 功能需求

### FR-1: 用户登录
用户可以使用邮箱和密码登录系统...

---

## 验证结果

✓ 验证通过
```

---

## ✅ 验证规则

### Specify 阶段

**必需章节**:
- 功能需求（Functional Requirements）
- 非功能需求（Non-Functional Requirements）
- 用户故事（User Stories）

**检查项**:
- 内容长度 >= 200 字符
- 包含用户故事格式
- 包含验收标准
- 无模糊表述

### Design 阶段

**必需章节**:
- 架构设计（Architecture Design）
- 技术选型（Technology Stack）
- 数据模型（Data Model）

**检查项**:
- 内容长度 >= 300 字符
- 包含架构图或流程图
- 包含 API 设计
- 考虑非功能需求

### Implement 阶段

**必需章节**:
- 任务列表（Task List）
- 实现步骤（Implementation Steps）

**检查项**:
- 包含任务列表（Markdown Checkbox）
- 任务数量 >= 3
- 包含测试计划
- 包含时间估算
- 包含优先级标记

---

## 🔄 状态机

### 工作流状态

```
IN_PROGRESS → PAUSED → IN_PROGRESS
     ↓
COMPLETED
     ↓
  (终态)
```

### 阶段流转

```
SPECIFY → DESIGN → IMPLEMENT
   ↑         ↑         ↑
   └─────────┴─────────┘
      (可以返回上一阶段)
```

### 门控验证

进入下一阶段的条件：
1. 当前阶段文档已生成
2. 当前阶段文档验证通过
3. 存在下一阶段

---

## 🎯 最佳实践

### 1. 需求描述要清晰

**好的示例**:
```
用户可以使用邮箱和密码登录系统。
登录成功后跳转到首页。
登录失败显示错误提示。
```

**不好的示例**:
```
做一个登录功能。
```

### 2. 逐阶段验证

每个阶段完成后，检查验证结果：
```kotlin
val document = workflow.getCurrentDocument()
val validation = document?.validationResult

if (validation?.valid == false) {
    println("验证失败:")
    validation.errors.forEach { println("  - $it") }
}
```

### 3. 保存中间结果

即使验证失败，文档也会被保存，可以手动修改后继续。

### 4. 使用合适的模型

不同阶段可以使用不同的模型：
```kotlin
val options = GenerationOptions(
    model = when (phase) {
        SpecPhase.SPECIFY -> "gpt-4o"      // 需要理解能力
        SpecPhase.DESIGN -> "claude-opus-4" // 需要架构能力
        SpecPhase.IMPLEMENT -> "gpt-4o"     // 需要任务拆解
    }
)
```

---

## 🧪 测试

### 单元测试

```kotlin
class SpecEngineTest : BasePlatformTestCase() {
    fun `test create workflow`() {
        val engine = SpecEngine.getInstance(project)
        val workflow = engine.createWorkflow("Test", "Test workflow")

        assertTrue(workflow.isSuccess)
        assertEquals(SpecPhase.SPECIFY, workflow.getOrThrow().currentPhase)
    }
}
```

### 集成测试

测试完整的三阶段流程：
1. 创建工作流
2. 生成 Specify 文档
3. 进入 Design 阶段
4. 生成 Design 文档
5. 进入 Implement 阶段
6. 生成 Implement 文档
7. 完成工作流

---

## 📚 相关文档

- [Phase 2 开发计划](../../docs/phase2-plan.md)
- [产品规划 - Spec 工作流](../../docs/spec-coding-plugin-plan.md)
- [开发清单](../../docs/dev-checklist.md)

---

## 🔮 未来计划

### Phase 2.1
- [ ] Spec Tab UI 集成
- [ ] 文档编辑器
- [ ] 阶段切换按钮
- [ ] 进度可视化

### Phase 2.2
- [ ] 模板管理
- [ ] 自定义验证规则
- [ ] 多语言支持
- [ ] Git 集成

### Phase 2.3
- [ ] 协作功能
- [ ] 版本控制
- [ ] 差异对比
- [ ] 导入导出

---

**模块状态**: 核心功能完成 ✅

**下一步**: UI 集成和测试
