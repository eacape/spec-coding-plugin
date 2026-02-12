# Hook 模块

当前已完成 MVP 主链路：

- `HookModels.kt`：事件/条件/动作/执行日志模型
- `HookYamlCodec.kt`：`.spec-coding/hooks.yaml` 编解码
- `HookConfigStore.kt`：Hook 配置读写与启用/禁用
- `HookManager.kt`：事件匹配、异步调度、执行日志缓存
- `HookExecutor.kt`：动作执行（命令/通知）、超时与错误处理、操作模式门控
- `HookFileSaveListener.kt`：文件保存触发 `FILE_SAVED` 事件

后续增强方向：

- 更丰富事件源（Git 提交、Spec 阶段变更）
- 更强条件表达式（分支名/文件类型/上下文元数据）
- 更细粒度 UI 管理与日志过滤
