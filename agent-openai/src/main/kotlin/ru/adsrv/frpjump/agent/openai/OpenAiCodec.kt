package ru.adsrv.frpjump.agent.openai

import kotlinx.serialization.json.*
import ru.adsrv.frpjump.agent.*

internal object OpenAiCodec {
    private fun invalid(): Nothing = throw OpenAiProviderException(ProviderFailure.INVALID_RESPONSE)
    private fun JsonElement?.obj() = this as? JsonObject ?: invalid()
    private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()

    private class Continuation(val owner: Any?, val reasoning: String) : AgentContinuation {
        override val estimatedTokens = reasoning.toByteArray(Charsets.UTF_8).size.toLong()
        override val sizeChars = reasoning.length
        override fun toString() = "<provider-continuation>"
    }

    fun encode(request: AgentRequest, config: OpenAiConfig, owner: Any? = null): String = buildJsonObject {
        put("model", config.model)
        put("stream", false)
        put(config.outputLimitField.wire, request.maxOutputTokens)
        config.reasoningEffort?.let { put("reasoning_effort", it) }
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", request.instructions) })
            request.turns.forEach { turn ->
                add(buildJsonObject { put("role", "user"); put("content", turn.user) })
                turn.exchanges.forEach { exchange ->
                    add(buildJsonObject {
                        put("role", "assistant")
                        exchange.reply.continuation?.let { opaque ->
                            val state = opaque as? Continuation ?: throw OpenAiProviderException(ProviderFailure.INCOMPATIBLE_CONTEXT)
                            if (owner == null || state.owner !== owner) throw OpenAiProviderException(ProviderFailure.INCOMPATIBLE_CONTEXT)
                            put("reasoning_content", state.reasoning)
                        }
                        put("content", exchange.reply.text.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
                        if (exchange.reply.calls.isNotEmpty()) putJsonArray("tool_calls") {
                            exchange.reply.calls.forEach { call -> add(buildJsonObject {
                                put("id", call.id); put("type", "function")
                                putJsonObject("function") { put("name", call.name); put("arguments", call.arguments.toString()) }
                            }) }
                        }
                    })
                    exchange.results.forEach { result -> add(buildJsonObject {
                        put("role", "tool"); put("tool_call_id", result.callId)
                        put("content", buildJsonObject {
                            put("outcome", result.outcome.name.lowercase()); put("text", result.text); put("omitted_chars", result.omittedChars)
                        }.toString())
                    }) }
                }
            }
        }
        if (request.tools.isNotEmpty()) putJsonArray("tools") {
            request.tools.forEach { tool -> add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") { put("name", tool.name); put("description", tool.description); put("parameters", tool.parameters) }
            }) }
        }
    }.toString()

    /** Preflight depth before the recursive JSON parser, including nested tool-argument JSON. */
    private fun parse(text: String): JsonElement {
        var depth = 0; var string = false; var escaped = false
        text.forEach { char ->
            if (string) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') string = false
            } else when (char) {
                '"' -> string = true
                '{', '[' -> { depth++; if (depth > 64) invalid() }
                '}', ']' -> { depth--; if (depth < 0) invalid() }
            }
        }
        return try { Json.parseToJsonElement(text) } catch (_: Exception) { invalid() }
    }

    fun decode(text: String, owner: Any? = null): AgentReply {
        val root = parse(text).obj()
        val choice = (root["choices"] as? JsonArray)?.firstOrNull().obj()
        val finish = choice["finish_reason"].text()
        if (finish in setOf("length", "content_filter")) throw OpenAiProviderException(ProviderFailure.INCOMPLETE_RESPONSE)
        if (finish !in setOf("stop", "tool_calls")) invalid()
        val message = choice["message"].obj()
        if (message["role"].text() != "assistant") invalid()
        val content = when (val value = message["content"]) {
            null, JsonNull -> ""
            else -> value.text()
        }
        val raw = when (val value = message["tool_calls"]) {
            null, JsonNull -> emptyList()
            is JsonArray -> value.toList()
            else -> invalid()
        }
        if (raw.size > 16) invalid()
        val calls = raw.map { element ->
            val call = element.obj()
            if (call["type"].text() != "function") invalid()
            val id = call["id"].text()
            val function = call["function"].obj()
            val name = function["name"].text()
            if (id.isBlank() || id.length > 256 || name.isBlank() || name.length > 64) invalid()
            ToolCall(id, name, parse(function["arguments"].text()).obj())
        }
        if (calls.map { it.id }.distinct().size != calls.size || (content.isBlank() && calls.isEmpty()) || ((finish == "tool_calls") != calls.isNotEmpty())) invalid()
        val continuation = when (val value = message["reasoning_content"]) {
            null, JsonNull -> null
            else -> Continuation(owner, value.text())
        }
        return AgentReply(content, calls, continuation)
    }

    fun models(text: String): List<ProviderModel> {
        val data = parse(text).obj()["data"] as? JsonArray ?: invalid()
        if (data.size > 4096) invalid()
        return data.map { element ->
            val id = element.obj()["id"].text()
            if (id.isBlank() || id.length > 256 || id.any { it.isISOControl() }) invalid()
            ProviderModel(id)
        }.distinctBy { it.id }
    }
}
