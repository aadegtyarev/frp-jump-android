package ru.adsrv.frpjump.ui.connections

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DiagnosticFormatTest {
    @Test fun `unavailable invalid idle and subprecision values are distinct`() {
        for (value in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals("—", diagnosticNumber(value, Locale.US))
            assertNull(scaleDiagnosticBytes(value))
        }
        assertEquals("0", diagnosticNumber(0.0, Locale.US))
        assertEquals("<0.1", diagnosticNumber(0.01, Locale.US))
    }

    @Test fun `numbers follow interface locale without grouping`() {
        assertEquals("1.5", diagnosticNumber(1.5, Locale.US))
        assertEquals("1,5", diagnosticNumber(1.5, Locale.forLanguageTag("ru")))
        assertEquals("10000", diagnosticNumber(10000.0, Locale.US))
    }

    @Test fun `byte scaling uses bounded binary units`() {
        assertEquals(DiagnosticBytes(0.0, 0), scaleDiagnosticBytes(0.0))
        assertEquals(DiagnosticBytes(1023.0, 0), scaleDiagnosticBytes(1023.0))
        assertEquals(DiagnosticBytes(1.0, 1), scaleDiagnosticBytes(1024.0))
        assertEquals(DiagnosticBytes(1.5, 2), scaleDiagnosticBytes(1.5 * 1024 * 1024))
        assertEquals(3, scaleDiagnosticBytes(Long.MAX_VALUE.toDouble())!!.unit)
    }
}
