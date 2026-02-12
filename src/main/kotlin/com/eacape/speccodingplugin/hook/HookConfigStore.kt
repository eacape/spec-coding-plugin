package com.eacape.speccodingplugin.hook

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readText

@Service(Service.Level.PROJECT)
class HookConfigStore(private val project: Project) {
    private val logger = thisLogger()
    private val lock = Any()

    @Volatile
    private var loaded = false

    private val hooks = mutableListOf<HookDefinition>()

    fun listHooks(): List<HookDefinition> {
        ensureLoaded()
        return synchronized(lock) {
            hooks.toList()
        }
    }

    fun getHookById(id: String): HookDefinition? {
        ensureLoaded()
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) {
            return null
        }
        return synchronized(lock) {
            hooks.firstOrNull { it.id == normalizedId }
        }
    }

    fun saveHook(definition: HookDefinition) {
        synchronized(lock) {
            ensureLoaded()
            val normalized = normalize(definition)
            val index = hooks.indexOfFirst { it.id == normalized.id }
            if (index >= 0) {
                hooks[index] = normalized
                logger.info("Updated hook definition: ${normalized.id}")
            } else {
                hooks.add(normalized)
                logger.info("Saved hook definition: ${normalized.id}")
            }
            persistToDisk()
        }
    }

    fun deleteHook(id: String): Boolean {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) {
            return false
        }

        synchronized(lock) {
            ensureLoaded()
            val removed = hooks.removeIf { it.id == normalizedId }
            if (removed) {
                logger.info("Deleted hook definition: $normalizedId")
                persistToDisk()
            }
            return removed
        }
    }

    fun setHookEnabled(id: String, enabled: Boolean): Boolean {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) {
            return false
        }

        synchronized(lock) {
            ensureLoaded()
            val index = hooks.indexOfFirst { it.id == normalizedId }
            if (index < 0) {
                return false
            }

            hooks[index] = hooks[index].copy(enabled = enabled)
            persistToDisk()
            logger.info("Hook enabled state updated: $normalizedId -> $enabled")
            return true
        }
    }

    private fun ensureLoaded() {
        if (loaded) {
            return
        }

        synchronized(lock) {
            if (loaded) {
                return
            }
            loadFromDisk()
            loaded = true
        }
    }

    private fun loadFromDisk() {
        val path = storePath() ?: return

        if (!path.exists()) {
            logger.info("No hooks config found, starting with empty hook set")
            hooks.clear()
            return
        }

        try {
            val content = path.readText(StandardCharsets.UTF_8)
            val parsed = HookYamlCodec.deserialize(content)
                .map(::normalize)

            hooks.clear()
            hooks.addAll(parsed)
            logger.info("Loaded ${hooks.size} hooks from ${path.toAbsolutePath()}")
        } catch (e: Exception) {
            logger.warn("Failed to load hooks config, fallback to empty list", e)
            hooks.clear()
        }
    }

    private fun persistToDisk() {
        val path = storePath() ?: return
        try {
            path.parent?.let { Files.createDirectories(it) }
            val content = HookYamlCodec.serialize(hooks)
            Files.writeString(
                path,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            logger.debug("Persisted ${hooks.size} hooks to ${path.toAbsolutePath()}")
        } catch (e: Exception) {
            logger.error("Failed to persist hooks config", e)
        }
    }

    private fun storePath(): Path? {
        val basePath = project.basePath ?: return null
        return Paths.get(basePath)
            .resolve(".spec-coding")
            .resolve("hooks.yaml")
    }

    private fun normalize(definition: HookDefinition): HookDefinition {
        val normalizedId = definition.id.trim()
        val normalizedName = definition.name.trim()

        require(normalizedId.isNotBlank()) { "Hook id cannot be blank" }
        require(normalizedName.isNotBlank()) { "Hook name cannot be blank" }
        require(definition.actions.isNotEmpty()) { "Hook actions cannot be empty" }

        val normalizedConditions = HookConditions(
            filePattern = definition.conditions.filePattern?.trim()?.ifBlank { null },
            specStage = definition.conditions.specStage?.trim()?.ifBlank { null },
        )

        val normalizedActions = definition.actions.map { action ->
            val command = action.command?.trim()?.ifBlank { null }
            val message = action.message?.trim()?.ifBlank { null }
            val args = action.args.mapNotNull { it.trim().ifBlank { null } }

            when (action.type) {
                HookActionType.RUN_COMMAND -> {
                    require(!command.isNullOrBlank()) { "RUN_COMMAND action requires command" }
                }

                HookActionType.SHOW_NOTIFICATION -> {
                    require(!message.isNullOrBlank()) { "SHOW_NOTIFICATION action requires message" }
                }
            }

            action.copy(
                command = command,
                message = message,
                args = args,
                timeoutMillis = action.timeoutMillis.coerceIn(1_000L, 10 * 60_000L),
            )
        }

        return definition.copy(
            id = normalizedId,
            name = normalizedName,
            conditions = normalizedConditions,
            actions = normalizedActions,
        )
    }

    companion object {
        fun getInstance(project: Project): HookConfigStore = project.service()
    }
}
