package ru.adsrv.frpjump.ui.files

import androidx.lifecycle.ViewModel
import ru.adsrv.frpjump.FrpJumpApp

/**
 * A file screen's hold on its [FilesSession]: attached as the screen
 * opens, released as it goes ([ru.adsrv.frpjump.files.FilesKeeper] keeps
 * or closes it then).
 */
class FilesViewModel(private val app: FrpJumpApp, private val targetId: Int) : ViewModel() {
    val session: FilesSession = app.files.attach(targetId)

    override fun onCleared() = app.files.release(targetId)
}
