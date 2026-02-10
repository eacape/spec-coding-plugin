# Rollback System

AI 操作的修改回滚系统，支持单步撤销和选择性回滚。

## 核心组件

### 1. RollbackModels.kt

定义数据模型：
- `FileChange` - 单个文件的变更记录
- `Changeset` - 一次 AI 操作的所有文件变更
- `RollbackResult` - 回滚操作的结果
- `RollbackOptions` - 回滚选项配置

### 2. ChangesetRecorder.kt

自动捕获 AI 文件操作的 before/after 快照：
- `startRecording()` - 开始记录新的变更集
- `recordFileCreation()` - 记录文件创建
- `recordFileModification()` - 记录文件修改
- `recordFileDeletion()` - 记录文件删除
- `finishRecording()` - 完成记录并返回变更集

### 3. ChangesetStore.kt

变更集的持久化存储（Project-level Service）：
- `save()` - 保存变更集
- `getAll()` - 获取所有变更集
- `getById()` - 根据 ID 获取变更集
- `getRecent()` - 获取最近的变更集
- `delete()` - 删除变更集

存储位置：`.spec-coding/changesets.json`

### 4. RollbackManager.kt

执行回滚操作（Project-level Service）：
- `rollback()` - 回滚指定的变更集
- `rollbackLast()` - 回滚最近的变更集
- `checkConflicts()` - 检查文件是否被手动修改

## 使用示例

### 记录 AI 操作

```kotlin
val recorder = ChangesetRecorder(project)

// 开始记录
val changesetId = recorder.startRecording(
    description = "AI refactored UserService.kt",
    metadata = mapOf("skill" to "refactor")
)

// 记录文件修改
val file = LocalFileSystem.getInstance().findFileByPath("src/UserService.kt")!!
val beforeContent = ChangesetRecorder.readFileContent(file)

// ... AI 修改文件 ...

val afterContent = ChangesetRecorder.readFileContent(file)
recorder.recordFileModification(file, beforeContent, afterContent)

// 完成记录
val changeset = recorder.finishRecording()

// 保存到存储
if (changeset != null) {
    ChangesetStore.getInstance(project).save(changeset)
}
```

### 回滚操作

```kotlin
val rollbackManager = RollbackManager.getInstance(project)

// 回滚最近的变更
val result = rollbackManager.rollbackLast()

when (result) {
    is RollbackResult.Success -> {
        println("Rolled back ${result.rolledBackFiles.size} files")
    }
    is RollbackResult.PartialSuccess -> {
        println("Rolled back ${result.rolledBackFiles.size} files")
        println("Failed: ${result.failedFiles.size} files")
    }
    is RollbackResult.Failure -> {
        println("Rollback failed: ${result.error}")
    }
}
```

### 选择性回滚

```kotlin
// 只回滚特定文件
val options = RollbackOptions(
    selectedFiles = setOf("src/UserService.kt", "src/UserRepository.kt"),
    createBackup = true
)

val result = rollbackManager.rollback(changesetId, options)
```

### 检查冲突

```kotlin
// 检查文件是否被手动修改
val conflicts = rollbackManager.checkConflicts(changesetId)

if (conflicts.isNotEmpty()) {
    println("Warning: The following files have been manually modified:")
    conflicts.forEach { println("  - $it") }
}
```

## 变更集统计

```kotlin
val changeset = ChangesetStore.getInstance(project).getById(changesetId)!!

val stats = changeset.getStatistics()
println("Total files: ${stats.totalFiles}")
println("Created: ${stats.createdFiles}")
println("Modified: ${stats.modifiedFiles}")
println("Deleted: ${stats.deletedFiles}")

println("Summary: ${changeset.getSummary()}")
// 输出: "2 created, 3 modified, 1 deleted"
```

## 备份机制

回滚前会自动创建备份（如果 `createBackup = true`）：
- 备份位置：`.spec-coding/backups/{changeset-id}/`
- 包含所有受影响文件的当前内容
- 用于在回滚失败时恢复

## 集成到 IDE Undo/Redo

回滚操作通过 `WriteAction` 包装，自动集成到 IDE 的 Undo/Redo 栈：

```kotlin
WriteAction.runAndWait<Throwable> {
    document.setText(content)
}
```

用户可以使用 Ctrl+Z 撤销回滚操作。

## 注意事项

1. **线程安全**：所有文件操作都在 Write Action 中执行
2. **冲突检测**：回滚前检查文件是否被手动修改
3. **备份保护**：默认创建备份，防止数据丢失
4. **部分成功**：即使部分文件回滚失败，也会返回成功的文件列表
5. **VFS 刷新**：回滚后自动刷新虚拟文件系统

## 未来增强

- [ ] UI 面板展示变更时间线
- [ ] Diff 预览（回滚前查看变更）
- [ ] 批量回滚多个变更集
- [ ] 变更集合并和拆分
- [ ] 导出变更集为 patch 文件
