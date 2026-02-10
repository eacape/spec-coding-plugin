# 👋 欢迎加入 Spec Coding Plugin 开发！

亲爱的开发者，

欢迎来到 **Spec Coding Plugin** 项目！这是一个为 JetBrains IDE 开发的规格驱动 AI 编码工作流插件。

---

## 🚀 快速开始（5 分钟）

### 1. 了解项目
```bash
# 阅读项目介绍
cat README.md

# 查看一页纸总结
cat docs/one-page-summary.md
```

### 2. 查看已完成的工作
- ✅ **LLM 抽象层** - OpenAI 和 Anthropic 完整集成
- ✅ **提示词管理** - 三层继承机制
- ✅ **技能系统** - 5 个内置技能 + 自定义支持

### 3. 运行项目
```bash
# 在沙箱 IDE 中运行
./gradlew runIde
```

---

## 📖 推荐阅读路径

**第一天**:
1. `README.md` - 项目概览
2. `docs/one-page-summary.md` - 快速了解进度
3. `docs/quick-reference.md` - 开发参考

**第二天**:
4. `docs/spec-coding-plugin-plan.md` - 完整产品规划
5. `docs/dev-checklist.md` - 开发任务清单
6. 浏览源代码 (`src/main/kotlin/`)

---

## 🎯 当前优先级任务

### P0 - 本周必需完成
1. **Chat Tool Window UI**
   - 位置: `src/main/kotlin/com/eacape/speccodingplugin/ui/`
   - 功能: 流式消息渲染、Markdown 渲染

2. **Settings 页面**
   - 位置: `src/main/kotlin/com/eacape/speccodingplugin/ui/`
   - 功能: API Key 配置、模型选择

3. **Gradle Wrapper**
   - 添加 `gradlew` 和 `gradlew.bat`

---

## 💡 开发提示

### 获取 Service 实例
```kotlin
// Project-level Service
val manager = PromptManager.getInstance(project)
val registry = SkillRegistry.getInstance(project)

// Application-level Service
val globalManager = GlobalPromptManager.getInstance()
```

### 使用 LLM Provider
```kotlin
val provider = OpenAiProvider(apiKey = "sk-...")
provider.stream(request) { chunk ->
    println(chunk.delta)
}
```

### 执行技能
```kotlin
val executor = SkillExecutor.getInstance(project)
val result = executor.executeFromCommand("/review", context)
```

---

## 🤝 需要帮助？

- **文档**: 查看 `docs/` 目录
- **代码示例**: 查看测试文件
- **快速参考**: `docs/quick-reference.md`

---

## ✅ 开发前检查清单

- [ ] 阅读 README.md
- [ ] 阅读产品规划文档
- [ ] 熟悉项目结构
- [ ] 运行 `./gradlew runIde` 确认环境正常

---

## 🎉 项目亮点

- **高质量代码**: 2,281 行 Kotlin 代码，遵循最佳实践
- **完整文档**: 9 个文档文件，~3,800 行
- **清晰架构**: 模块化设计，易于扩展
- **类型安全**: 充分利用 Kotlin 类型系统

---

## 📊 当前状态

**Phase 1 完成度**: 60% (3/8 核心模块)
**预计完成时间**: 2 周内
**下一个里程碑**: Phase 1 MVP

---

祝你开发愉快！如果有任何问题，请查看文档或创建 Issue。

**Spec Coding Plugin Team**

---

*最后更新: 2026-02-10*
