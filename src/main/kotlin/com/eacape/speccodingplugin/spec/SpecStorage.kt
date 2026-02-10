package com.eacape.speccodingplugin.spec

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Spec 文档存储管理器
 * 负责 Spec 文档的文件系统存储和读取
 */
class SpecStorage(private val project: Project) {
    private val logger = thisLogger()

    /**
     * 保存 Spec 文档
     */
    fun saveDocument(workflowId: String, document: SpecDocument): Result<Path> {
        return runCatching {
            val workflowDir = getWorkflowDirectory(workflowId)
            Files.createDirectories(workflowDir)

            val filePath = workflowDir.resolve(document.phase.outputFileName)
            val content = formatDocument(document)

            Files.writeString(filePath, content, StandardCharsets.UTF_8)
            logger.info("Saved ${document.phase} document to $filePath")

            filePath
        }
    }

    /**
     * 加载 Spec 文档
     */
    fun loadDocument(workflowId: String, phase: SpecPhase): Result<SpecDocument> {
        return runCatching {
            val workflowDir = getWorkflowDirectory(workflowId)
            val filePath = workflowDir.resolve(phase.outputFileName)

            if (!Files.exists(filePath)) {
                throw IllegalStateException("Document not found: $filePath")
            }

            val content = Files.readString(filePath, StandardCharsets.UTF_8)
            parseDocument(workflowId, phase, content)
        }
    }

    /**
     * 保存工作流状态
     */
    fun saveWorkflow(workflow: SpecWorkflow): Result<Path> {
        return runCatching {
            val workflowDir = getWorkflowDirectory(workflow.id)
            Files.createDirectories(workflowDir)

            val metadataPath = workflowDir.resolve("workflow.yaml")
            val metadata = formatWorkflowMetadata(workflow)

            Files.writeString(metadataPath, metadata, StandardCharsets.UTF_8)
            logger.info("Saved workflow metadata to $metadataPath")

            metadataPath
        }
    }

    /**
     * 加载工作流状态
     */
    fun loadWorkflow(workflowId: String): Result<SpecWorkflow> {
        return runCatching {
            val workflowDir = getWorkflowDirectory(workflowId)
            val metadataPath = workflowDir.resolve("workflow.yaml")

            if (!Files.exists(metadataPath)) {
                throw IllegalStateException("Workflow not found: $workflowId")
            }

            val metadata = Files.readString(metadataPath, StandardCharsets.UTF_8)
            parseWorkflowMetadata(workflowId, metadata)
        }
    }

    /**
     * 列出所有工作流
     */
    fun listWorkflows(): List<String> {
        val specsDir = getSpecsDirectory()
        if (!Files.exists(specsDir)) {
            return emptyList()
        }

        return Files.list(specsDir).use { stream ->
            val workflows = mutableListOf<String>()
            stream.forEach { path ->
                if (Files.isDirectory(path)) {
                    workflows.add(path.fileName.toString())
                }
            }
            workflows
        }
    }

    /**
     * 删除工作流
     */
    fun deleteWorkflow(workflowId: String): Result<Unit> {
        return runCatching {
            val workflowDir = getWorkflowDirectory(workflowId)
            if (Files.exists(workflowDir)) {
                Files.walk(workflowDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.delete(it) }
                logger.info("Deleted workflow: $workflowId")
            }
        }
    }

    /**
     * 获取工作流目录
     */
    private fun getWorkflowDirectory(workflowId: String): Path {
        return getSpecsDirectory().resolve(workflowId)
    }

    /**
     * 获取 Specs 根目录
     */
    private fun getSpecsDirectory(): Path {
        val basePath = project.basePath ?: throw IllegalStateException("Project base path is null")
        return Paths.get(basePath)
            .resolve(".spec-coding")
            .resolve("specs")
    }

    /**
     * 格式化文档内容
     */
    private fun formatDocument(document: SpecDocument): String {
        return buildString {
            appendLine("# ${document.metadata.title}")
            appendLine()
            appendLine("**阶段**: ${document.phase.displayName}")
            appendLine("**作者**: ${document.metadata.author}")
            appendLine("**创建时间**: ${formatTimestamp(document.metadata.createdAt)}")
            appendLine("**更新时间**: ${formatTimestamp(document.metadata.updatedAt)}")
            appendLine("**版本**: ${document.metadata.version}")

            if (document.metadata.tags.isNotEmpty()) {
                appendLine("**标签**: ${document.metadata.tags.joinToString(", ")}")
            }

            appendLine()
            appendLine("---")
            appendLine()
            appendLine(document.content)

            // 添加验证结果（如果有）
            document.validationResult?.let { validation ->
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("## 验证结果")
                appendLine()
                appendLine("```")
                appendLine(validation.getSummary())
                appendLine("```")
            }
        }
    }

    /**
     * 解析文档内容
     */
    private fun parseDocument(workflowId: String, phase: SpecPhase, content: String): SpecDocument {
        // 简化版本：直接提取内容部分
        // 实际实现可以解析 YAML front matter
        val lines = content.lines()
        val contentStart = lines.indexOfFirst { it.trim() == "---" }
        val actualContent = if (contentStart >= 0 && contentStart < lines.size - 1) {
            lines.subList(contentStart + 1, lines.size).joinToString("\n")
        } else {
            content
        }

        return SpecDocument(
            id = "$workflowId-${phase.name.lowercase()}",
            phase = phase,
            content = actualContent.trim(),
            metadata = SpecMetadata(
                title = "${phase.displayName} Document",
                description = "Generated ${phase.displayName} document"
            )
        )
    }

    /**
     * 格式化工作流元数据
     */
    private fun formatWorkflowMetadata(workflow: SpecWorkflow): String {
        return buildString {
            appendLine("id: ${workflow.id}")
            appendLine("currentPhase: ${workflow.currentPhase.name}")
            appendLine("status: ${workflow.status.name}")
            appendLine("createdAt: ${workflow.createdAt}")
            appendLine("updatedAt: ${workflow.updatedAt}")
            appendLine("documents:")
            workflow.documents.forEach { (phase, doc) ->
                appendLine("  - phase: ${phase.name}")
                appendLine("    id: ${doc.id}")
                appendLine("    title: ${doc.metadata.title}")
            }
        }
    }

    /**
     * 解析工作流元数据
     */
    private fun parseWorkflowMetadata(workflowId: String, metadata: String): SpecWorkflow {
        val lines = metadata.lines()
        var currentPhase = SpecPhase.SPECIFY
        var status = WorkflowStatus.IN_PROGRESS
        var createdAt = System.currentTimeMillis()
        var updatedAt = System.currentTimeMillis()

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("currentPhase:") -> {
                    val value = trimmed.substringAfter(":").trim()
                    currentPhase = runCatching { SpecPhase.valueOf(value) }.getOrDefault(SpecPhase.SPECIFY)
                }
                trimmed.startsWith("status:") -> {
                    val value = trimmed.substringAfter(":").trim()
                    status = runCatching { WorkflowStatus.valueOf(value) }.getOrDefault(WorkflowStatus.IN_PROGRESS)
                }
                trimmed.startsWith("createdAt:") -> {
                    createdAt = trimmed.substringAfter(":").trim().toLongOrNull() ?: createdAt
                }
                trimmed.startsWith("updatedAt:") -> {
                    updatedAt = trimmed.substringAfter(":").trim().toLongOrNull() ?: updatedAt
                }
            }
        }

        // 加载已有文档
        val documents = mutableMapOf<SpecPhase, SpecDocument>()
        for (phase in SpecPhase.entries) {
            loadDocument(workflowId, phase).getOrNull()?.let {
                documents[phase] = it
            }
        }

        return SpecWorkflow(
            id = workflowId,
            currentPhase = currentPhase,
            documents = documents,
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    /**
     * 格式化时间戳
     */
    private fun formatTimestamp(timestamp: Long): String {
        val instant = Instant.ofEpochMilli(timestamp)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        return formatter.format(instant)
    }

    companion object {
        fun getInstance(project: Project): SpecStorage {
            return SpecStorage(project)
        }
    }
}
