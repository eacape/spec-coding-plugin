package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 将结构化 trace 事件编码到消息 metadata_json，便于重启后恢复执行过程。
 */
internal object TraceEventMetadataCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun encode(events: List<ChatStreamEvent>): String? {
        val normalized = events
            .filter { it.detail.isNotBlank() }
            .takeLast(MAX_TRACE_EVENTS)
        if (normalized.isEmpty()) return null

        val root = buildJsonObject {
            put(FORMAT_KEY, FORMAT_VALUE)
            put(
                TRACE_EVENTS_KEY,
                buildJsonArray {
                    normalized.forEach { event ->
                        add(event.toJson())
                    }
                }
            )
        }
        return root.toString()
    }

    fun decode(metadataJson: String?): List<ChatStreamEvent> {
        if (metadataJson.isNullOrBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(metadataJson).asObject() }
            .getOrNull()
            ?: return emptyList()

        val array = when (val node = root[TRACE_EVENTS_KEY]) {
            is JsonArray -> node
            else -> null
        } ?: return emptyList()

        return array
            .asSequence()
            .mapNotNull { it.asObject()?.toTraceEvent() }
            .take(MAX_TRACE_EVENTS)
            .toList()
    }

    private fun ChatStreamEvent.toJson(): JsonObject {
        return buildJsonObject {
            put("kind", kind.name)
            put("detail", detail)
            put("status", status.name)
            id?.let { put("id", it) }
            sequence?.let { put("sequence", it) }
            if (metadata.isNotEmpty()) {
                put(
                    "metadata",
                    buildJsonObject {
                        metadata.forEach { (k, v) ->
                            put(k, v)
                        }
                    }
                )
            }
        }
    }

    private fun JsonObject.toTraceEvent(): ChatStreamEvent? {
        val detail = string("detail")?.trim().orEmpty()
        if (detail.isBlank()) return null

        val kind = enumOrNull<ChatTraceKind>(string("kind")) ?: ChatTraceKind.TASK
        val status = enumOrNull<ChatTraceStatus>(string("status")) ?: ChatTraceStatus.INFO
        val metadataMap = obj("metadata")
            ?.entries
            ?.associate { (k, v) -> k to (v.asPrimitive()?.contentOrNull ?: v.toString()) }
            .orEmpty()

        return ChatStreamEvent(
            kind = kind,
            detail = detail,
            status = status,
            id = string("id"),
            sequence = long("sequence"),
            metadata = metadataMap,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? {
        val value = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return enumValues<T>().firstOrNull { it.name == value }
    }

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement.asPrimitive(): JsonPrimitive? = this as? JsonPrimitive

    private fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun buildJsonObject(builder: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
        val map = linkedMapOf<String, JsonElement>()
        map.builder()
        return JsonObject(map)
    }

    private fun buildJsonArray(builder: MutableList<JsonElement>.() -> Unit): JsonArray {
        val list = mutableListOf<JsonElement>()
        list.builder()
        return JsonArray(list)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: String) {
        this[key] = JsonPrimitive(value)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: Long) {
        this[key] = JsonPrimitive(value)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: JsonElement) {
        this[key] = value
    }

    private fun MutableList<JsonElement>.add(value: JsonObject) {
        this += value
    }

    private const val FORMAT_KEY = "format"
    private const val FORMAT_VALUE = "chat_trace_v1"
    private const val TRACE_EVENTS_KEY = "trace_events"
    private const val MAX_TRACE_EVENTS = 600
}
