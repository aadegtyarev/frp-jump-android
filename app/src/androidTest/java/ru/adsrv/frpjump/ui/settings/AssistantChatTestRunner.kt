package ru.adsrv.frpjump.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Explicit opt-in entry for synthetic chat component checks; never starts FrpJumpApp. */
class AssistantChatTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
