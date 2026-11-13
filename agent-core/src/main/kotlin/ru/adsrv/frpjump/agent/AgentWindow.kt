package ru.adsrv.frpjump.agent

data class AgentWindow(val request: AgentRequest?, val omittedTurns: Int, val omittedExchanges: Int, val estimatedTokens: Long)

/** Keep instructions/current user/latest complete exchange; evict only whole older protocol blocks. */
fun selectAgentWindow(
    instructions: String,
    tools: List<ToolSpec>,
    history: List<AgentTurn>,
    current: AgentTurn,
    limits: AgentLimits,
    counter: TokenCounter = Utf8TokenEstimate,
    ownerInstructions: String = "",
): AgentWindow {
    val budget = limits.contextTokens - limits.outputReserveTokens
    var retained = current
    var removed = 0
    fun request(turns: List<AgentTurn>) = AgentRequest(instructions, tools, turns, limits.outputReserveTokens, ownerInstructions)
    var tokens = counter.count(request(listOf(retained)))
    while (tokens > budget && retained.exchanges.size > 1) {
        retained = retained.copy(exchanges = retained.exchanges.drop(1))
        removed++
        tokens = counter.count(request(listOf(retained)))
    }
    if (tokens > budget) return AgentWindow(null, history.size, removed, tokens)
    var turns = listOf(retained)
    var kept = 0
    for (turn in history.asReversed()) {
        val candidate = listOf(turn) + turns
        val cost = counter.count(request(candidate))
        if (cost > budget) break
        turns = candidate
        tokens = cost
        kept++
    }
    return AgentWindow(request(turns), history.size - kept, removed, tokens)
}

internal fun toolResult(call: ToolCall, output: ToolOutput, limit: Int): ToolResult {
    val original = output.text
    if (original.length <= limit) return ToolResult(call.id, call.name, original, output.outcome)
    val marker = "\n[…]\n"
    val available = limit - marker.length
    var head = available / 2
    var tail = original.length - (available - head)
    // Do not split a UTF-16 surrogate pair at either cut.
    if (head > 0 && original[head - 1].isHighSurrogate() && original[head].isLowSurrogate()) head--
    if (tail > 0 && original[tail - 1].isHighSurrogate() && original[tail].isLowSurrogate()) tail++
    return ToolResult(call.id, call.name, original.take(head) + marker + original.substring(tail), output.outcome, tail - head)
}
