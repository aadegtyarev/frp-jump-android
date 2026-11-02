package ru.adsrv.frpjump.ui.ssh

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.view.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.ssh.diagnostics.TerminalMetrics

@RunWith(AndroidJUnit4::class)
class TerminalDrawMetricsTest {
    @Test fun childDrawingRecordsCpuOnlyForAnEnabledOwner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val metrics = TerminalMetrics(1000).apply { setObserving(true) }
            val input = TerminalInput(instrumentation.targetContext, TerminalView(instrumentation.targetContext, null)).apply {
                diagnostics = metrics
            }
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            try {
                input.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
                input.layout(0, 0, 320, 240)
                input.draw(Canvas(bitmap))
                assertEquals(1L, metrics.snapshot().drawCpu.count)
                assertTrue(metrics.snapshot().drawCpu.lastMs!! >= 0)
                metrics.setObserving(false)
                input.draw(Canvas(bitmap))
                assertEquals(1L, metrics.snapshot().drawCpu.count)
                input.diagnostics = null
                input.draw(Canvas(bitmap))
                assertEquals(1L, metrics.snapshot().drawCpu.count)
                assertEquals(0L, metrics.snapshot().sentBytes)
            } finally { bitmap.recycle() }
        }
    }
}
