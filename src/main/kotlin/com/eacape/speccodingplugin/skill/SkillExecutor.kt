package com.eacape.speccodingplugin.skill

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * 技能执行器（Project-level Service）
 * 负责技能的调度、参数解析、上下文收集和结果格式化
 */
@Service(Service.Level.PROJECT)
class SkillExecutor(private val project: Project) {
    private val logger = thisLogger()
    private val registry = SkillRegistry.getInstance(project)

    /**
     * 执行技能
     */
    suspend fun execute(request: SkillExecutionRequest): SkillExecutionResult {
        val skill = request.skill

        logger.info("Executing skill: ${skill.id} (/${skill.slashCommand})")

        return try {
            // 验证上下文要求
            validateContextRequirements(skill, request.context)

            // 渲染提示词模板
            val renderedPrompt = renderPromptTemplate(skill, request)

            // 返回成功结果（实际的 LLM 调用将在上层处理）
            SkillExecutionResult.Success(
                output = renderedPrompt,
                metadata = mapOf(
                    "skill_id" to skill.id,
                    "skill_name" to skill.name,
                )
            )
        } catch (e: Exception) {
            logger.warn("Skill execution failed: ${skill.id}", e)
            SkillExecutionResult.Failure(
                error = "Failed to execute skill '${skill.name}': ${e.message}",
                cause = e
            )
        }
    }

    /**
     * 从斜杠命令执行技能
     */
    suspend fun executeFromCommand(
        command: String,
        context: SkillContext,
        arguments: Map<String, String> = emptyMap()
    ): SkillExecutionResult {
        val normalizedCommand = command.trim().removePrefix("/")

        val skill = registry.getSkillByCommand(normalizedCommand)
            ?: return SkillExecutionResult.Failure("Unknown skill command: /$normalizedCommand")

        val request = SkillExecutionRequest(
            skill = skill,
            arguments = arguments,
            context = context
        )

        return execute(request)
    }

    /**
     * 解析斜杠命令（格式：/skill-name [args]）
     */
    fun parseSlashCommand(input: String): Pair<String, Map<String, String>>? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) {
            return null
        }

        val parts = trimmed.substring(1).split(Regex("\\s+"), limit = 2)
        val command = parts[0]
        val argsString = parts.getOrNull(1) ?: ""

        // 简单的参数解析（key=value 格式）
        val arguments = if (argsString.isNotBlank()) {
            argsString.split(Regex("\\s+"))
                .mapNotNull { arg ->
                    val keyValue = arg.split("=", limit = 2)
                    if (keyValue.size == 2) {
                        keyValue[0] to keyValue[1]
                    } else {
                        null
                    }
                }
                .toMap()
        } else {
            emptyMap()
        }

        return command to arguments
    }

    /**
     * 验证上下文要求
     */
    private fun validateContextRequirements(skill: Skill, context: SkillContext) {
        skill.contextRequirements.forEach { requirement ->
            when (requirement) {
                ContextRequirement.CURRENT_FILE -> {
                    if (context.currentFile.isNullOrBlank()) {
                        throw IllegalArgumentException("Skill '${skill.name}' requires current file context")
                    }
                }
                ContextRequirement.SELECTED_CODE -> {
                    if (context.selectedCode.isNullOrBlank()) {
                        throw IllegalArgumentException("Skill '${skill.name}' requires selected code")
                    }
                }
                ContextRequirement.PROJECT_STRUCTURE -> {
                    if (context.projectStructure.isNullOrBlank()) {
                        throw IllegalArgumentException("Skill '${skill.name}' requires project structure")
                    }
                }
                ContextRequirement.TEST_FRAMEWORK_CONFIG -> {
                    // 可选验证
                }
                ContextRequirement.GIT_STATUS -> {
                    // 可选验证
                }
            }
        }
    }

    /**
     * 渲染提示词模板
     */
    private fun renderPromptTemplate(skill: Skill, request: SkillExecutionRequest): String {
        var rendered = skill.promptTemplate

        // 替换上下文变量
        val variables = mutableMapOf<String, String>()

        request.context.currentFile?.let { variables["current_file"] = it }
        request.context.selectedCode?.let { variables["selected_code"] = it }
        request.context.projectStructure?.let { variables["project_structure"] = it }
        variables.putAll(request.context.additionalContext)
        variables.putAll(request.arguments)

        // 简单的变量替换（{{variable_name}}）
        variables.forEach { (key, value) ->
            rendered = rendered.replace("{{$key}}", value)
        }

        return rendered
    }

    companion object {
        fun getInstance(project: Project): SkillExecutor {
            return project.getService(SkillExecutor::class.java)
        }
    }
}
