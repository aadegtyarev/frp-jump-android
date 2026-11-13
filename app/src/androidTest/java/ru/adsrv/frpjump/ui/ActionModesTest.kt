package ru.adsrv.frpjump.ui

import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionModesTest {
    private class FakeMode(private val tracker: () -> ActionModes) : ActionMode() {
        var finishes = 0
        override fun setTitle(title: CharSequence?) {}
        override fun setTitle(resId: Int) {}
        override fun setSubtitle(subtitle: CharSequence?) {}
        override fun setSubtitle(resId: Int) {}
        override fun setCustomView(view: View?) {}
        override fun invalidate() {}
        override fun finish() { finishes++; tracker().finished(this) }
        override fun getMenu(): Menu? = null
        override fun getTitle(): CharSequence? = null
        override fun getSubtitle(): CharSequence? = null
        override fun getCustomView(): View? = null
        override fun getMenuInflater(): MenuInflater? = null
    }

    private var locked = false
    private val modes = ActionModes { locked }

    @Test fun lockEndsEveryOpenSelectionToolbar() {
        val a = FakeMode { modes }
        val b = FakeMode { modes }
        modes.started(a)
        modes.started(b)
        modes.finished(b)
        modes.finishAll()
        assertEquals(1, a.finishes)
        assertEquals("a mode already ended is not ended again", 0, b.finishes)
    }

    @Test fun aToolbarStartingWhileLockedIsEndedAtOnce() {
        val a = FakeMode { modes }
        modes.started(a)
        assertEquals(0, a.finishes)
        locked = true
        val b = FakeMode { modes }
        modes.started(b)
        assertEquals(1, b.finishes)
    }
}
