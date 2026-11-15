package ru.adsrv.frpjump.ui.settings

import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.AssistantToolId

/** The only mapping from an assistant tool to its texts; exhaustive, so a new tool cannot be forgotten. */
internal fun AssistantToolId.title(): Int = when (this) {
    AssistantToolId.WEB_SEARCH -> R.string.tool_search
    AssistantToolId.READ_PAGE -> R.string.tool_read
    AssistantToolId.INSPECT_IMAGE -> R.string.tool_image
    AssistantToolId.PROFILE_INFORMATION -> R.string.tool_profile_information
    AssistantToolId.TERMINAL_TABS, AssistantToolId.TERMINAL_READ -> R.string.tool_terminal_tabs
    AssistantToolId.TERMINAL_INPUT -> R.string.tool_terminal_input
    AssistantToolId.SSH_COMMAND -> R.string.tool_ssh_command
}

/** The Tools-screen hint; for SSH it is the FULL SSH ACCESS warning. */
internal fun AssistantToolId.hint(): Int = when (this) {
    AssistantToolId.WEB_SEARCH -> R.string.tool_search_hint
    AssistantToolId.READ_PAGE -> R.string.tool_read_hint
    AssistantToolId.INSPECT_IMAGE -> R.string.tool_image_hint
    AssistantToolId.PROFILE_INFORMATION -> R.string.tool_profile_information_hint
    AssistantToolId.TERMINAL_TABS, AssistantToolId.TERMINAL_READ -> R.string.tool_terminal_tabs_hint
    AssistantToolId.TERMINAL_INPUT -> R.string.tool_terminal_input_hint
    AssistantToolId.SSH_COMMAND -> R.string.tool_ssh_command_hint
}

internal fun AssistantToolId.progress(): Int = when (this) {
    AssistantToolId.WEB_SEARCH -> R.string.tool_search_progress
    AssistantToolId.READ_PAGE -> R.string.tool_read_progress
    AssistantToolId.INSPECT_IMAGE -> R.string.tool_image_progress
    AssistantToolId.PROFILE_INFORMATION -> R.string.tool_profile_information_progress
    AssistantToolId.TERMINAL_TABS -> R.string.tool_terminal_tabs_progress
    AssistantToolId.TERMINAL_READ -> R.string.tool_terminal_read_progress
    AssistantToolId.TERMINAL_INPUT -> R.string.tool_terminal_input_progress
    AssistantToolId.SSH_COMMAND -> R.string.tool_ssh_command_progress
}

/**
 * The title of a call's tool in the chat (receipt rows, failures, declines). It is the switch's [title] except for the terminal
 * tools: the first two share one switch but are different calls, and typing has a title of its own for the call.
 */
internal fun AssistantToolId.receiptTitle(): Int = when (this) {
    AssistantToolId.TERMINAL_TABS -> R.string.tool_terminal_tabs_receipt
    AssistantToolId.TERMINAL_READ -> R.string.tool_terminal_read_receipt
    AssistantToolId.TERMINAL_INPUT -> R.string.tool_terminal_input_receipt
    else -> title()
}
