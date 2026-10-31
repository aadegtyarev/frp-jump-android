package ru.adsrv.frpjump.ui.files

import org.junit.Assert.assertEquals
import org.junit.Test

class RunningTransfersTest {
    private fun going(name: String, kind: TransferKind = TransferKind.DOWNLOAD) = Transfer(name, 10, 100, kind)

    private fun failed(name: String) = Transfer(name, 0, 100, failure = "the connection dropped")

    @Test
    fun `nothing going lets the screen leave at once`() {
        assertEquals(0, runningTransfers(emptyList()))
    }

    @Test
    fun `failed transfers are not cancelled by leaving`() {
        assertEquals(0, runningTransfers(listOf(failed("a"), failed("b"))))
    }

    @Test
    fun `downloads, opens, shares and uploads going or waiting all count`() {
        val transfers = listOf(
            going("a"),
            going("b", TransferKind.OPEN),
            going("c", TransferKind.SHARE),
            // An upload waiting its turn: nothing done yet.
            Transfer("d", 0, 5),
            failed("e"),
        )
        assertEquals(4, runningTransfers(transfers))
    }
}
