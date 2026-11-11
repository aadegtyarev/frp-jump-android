package ru.adsrv.frpjump.assistant

/** Removed in PR 4b: still used by the profile-information and SSH tools, whose own start paths are not yet structured. */
internal typealias WebDispatch = (String, () -> Unit) -> Unit
