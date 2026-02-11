# Skill 技能系统

技能系统提供可扩展的 AI 能力单元，支持内置技能和自定义技能。

## 核心组件

### 1. SkillRegistry（技能注册表）

管理所有技能的注册、发现和查询。

**功能：**
- 注册内置技能和自定义技能
- 根据 ID 或斜杠命令查找技能
- 搜索技能（按名称、描述、标签）

**使用示例：**
```kotlin
val registry = SkillRegistry.getInstance(project)

// 列出所有技能
val skills = registry.listSkills()

// 根据命令查找技能
val reviewSkill = registry.getSkillByCommand("review")

// 搜索技能
val searchResults = registry.searchSkills("code quality")
```

### 2. SkillExecutor（技能执行器）

负责技能的调度、参数解析和执行。

**功能：**
- 执行技能并渲染提示词模板
- 解析斜杠命令（如 `/review`）
- 验证上下文要求
- 变量替换

**使用示例：**
```kotlin
val executor = SkillExecutor.getInstance(project)

// 从斜杠命令执行
val context = SkillContext(
    selectedCode = "fun example() { ... }"
)
val result = executor.executeFromCommand("/review", context)

// 解析斜杠命令
val (command, args) = executor.parseSlashCommand("/test framework=junit")
```

### 3. Skill（技能模型）

定义技能的元数据和行为。

**属性：**
- `id`: 唯一标识符
- `name`: 显示名称
- `description`: 功能描述
- `slashCommand`: 斜杠命令（如 "review"）
- `promptTemplate`: 提示词模板
- `contextRequirements`: 上下文要求列表
- `tags`: 标签列表
- `enabled`: 是否启用

## 内置技能

### /review - 代码审查
分析代码质量、安全性和可维护性。

**上下文要求：** 选中的代码

**示例：**
```
/review
```

### /security-scan - 安全扫描
按 OWASP Top 10 和插件风险做漏洞扫描，并给出修复建议。

**上下文要求：** 选中的代码

**示例：**
```
/security-scan
```

### /tdd - TDD 工作流
按 Red-Green-Refactor 流程输出测试与实现建议。

**上下文要求：** 选中的代码、测试框架配置

**示例：**
```
/tdd
```

### /explain - 代码解释
解释代码逻辑和设计意图。

**上下文要求：** 选中的代码

**示例：**
```
/explain
```

### /refactor - 代码重构
建议重构改进。

**上下文要求：** 选中的代码

**示例：**
```
/refactor
```

### /test - 生成测试
为代码生成单元测试。

**上下文要求：** 选中的代码、测试框架配置

**示例：**
```
/test
```

### /fix - 修复 Bug
分析并修复代码中的 Bug。

**上下文要求：** 选中的代码

**示例：**
```
/fix
```

## 自定义技能

### 创建自定义技能

在项目根目录创建 `.spec-coding/skills/` 目录，添加 YAML 文件：

**示例：`tdd-workflow.yaml`**
```yaml
id: tdd-workflow
name: TDD Workflow
description: Test-driven development workflow
slash_command: tdd
prompt_template: |
  You are executing a TDD workflow. Please follow these steps:

  1. Analyze requirements and write failing test cases
  2. Run tests to confirm failure (red)
  3. Write minimal implementation to pass tests (green)
  4. Refactor code (improve)
  5. Verify coverage >= 80%

  Current code:
  {{selected_code}}

  Generate complete test code and implementation.
context_requirements:
  - SELECTED_CODE
  - TEST_FRAMEWORK_CONFIG
tags:
  - testing
  - tdd
enabled: true
```

### 自定义技能字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | String | 是 | 唯一标识符 |
| `name` | String | 是 | 显示名称 |
| `description` | String | 是 | 功能描述 |
| `slash_command` | String | 是 | 斜杠命令（不含 `/`） |
| `prompt_template` | String | 是 | 提示词模板，支持 `{{variable}}` 变量 |
| `context_requirements` | List | 否 | 上下文要求列表 |
| `tags` | List | 否 | 标签列表 |
| `enabled` | Boolean | 否 | 是否启用（默认 true） |

### 上下文要求类型

- `CURRENT_FILE`: 当前文件内容
- `SELECTED_CODE`: 选中的代码
- `PROJECT_STRUCTURE`: 项目结构
- `TEST_FRAMEWORK_CONFIG`: 测试框架配置
- `GIT_STATUS`: Git 状态

### 变量替换

提示词模板支持以下变量：

- `{{current_file}}`: 当前文件内容
- `{{selected_code}}`: 选中的代码
- `{{project_structure}}`: 项目结构
- 自定义变量（通过 `additionalContext` 传递）

## 技能执行流程

```
用户输入 /review
    ↓
SkillExecutor.parseSlashCommand()
    ↓
SkillRegistry.getSkillByCommand("review")
    ↓
验证上下文要求
    ↓
渲染提示词模板（变量替换）
    ↓
返回 SkillExecutionResult
    ↓
上层调用 LLM 生成响应
```

## 最佳实践

### 1. 技能命名
- 使用简短、描述性的命令名（如 `review`、`test`）
- 避免与内置技能冲突

### 2. 提示词模板
- 使用清晰的指令和结构
- 明确输出格式要求
- 包含必要的上下文变量

### 3. 上下文要求
- 只声明必需的上下文
- 过多要求会降低技能可用性

### 4. 标签管理
- 使用有意义的标签便于搜索
- 内置技能自动添加 `built-in` 标签
- 自定义技能自动添加 `custom` 标签

## 未来扩展

- **Skill Pipeline**: 多技能串联执行
- **条件触发**: 根据文件类型、项目配置自动推荐技能
- **技能市场**: 共享和下载社区技能
- **参数验证**: 更强大的参数解析和验证
- **执行历史**: 记录技能执行历史和统计
