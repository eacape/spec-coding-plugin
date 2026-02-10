# Prompt 模块

当前已实现 Phase 1 的最小可用能力：

- `PromptTemplate` / `PromptScope` / `PromptCatalog` 数据模型
- `PromptManager`（Project Service）
  - Prompt 列表读取
  - 激活 Prompt 切换
  - 基础 CRUD（upsert / delete）
  - 活跃 Prompt 渲染（`{{variable}}` 插值）
- YAML 持久化（项目级）：`.spec-coding/prompts/catalog.yaml`

后续迭代方向：

- 全局级/会话级三层继承
- Prompt 编辑器 UI（多行编辑 + 语法高亮）
- 更完整的变量上下文（语言、文件、分支、模式等）
