package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishingTest {
    @Test
    fun `reason codes below 0x80 accept, from 0x80 refuse`() {
        assertEquals(PublishAnswer.Accepted, ackAnswer(0x00, null))
        // "No matching subscribers" is a success.
        assertEquals(PublishAnswer.Accepted, ackAnswer(0x10, null))
        assertEquals(PublishAnswer.Refused(0x87, "acl"), ackAnswer(0x87, "acl"))
        assertEquals(PublishAnswer.Refused(0x80, null), ackAnswer(0x80, null))
    }

    @Test
    fun `a refusal reads as the code's name and the broker's reason string`() {
        assertEquals("not authorized", refusalText(PublishAnswer.Refused(0x87, null)))
        assertEquals("not authorized (no write to /devices)", refusalText(PublishAnswer.Refused(0x87, "no write to /devices")))
        // Saying the same again, or nothing, adds nothing.
        assertEquals("not authorized", refusalText(PublishAnswer.Refused(0x87, "Not authorized")))
        assertEquals("quota exceeded", refusalText(PublishAnswer.Refused(0x97, " \n")))
        assertEquals("reason code 0xA2", refusalText(PublishAnswer.Refused(0xA2, null)))
        // The broker's text is shown in one safe line.
        assertEquals("topic name invalid (a b)", refusalText(PublishAnswer.Refused(0x90, "a\n\u202Eb")))
    }

    @Test
    fun `topics starting with a dollar sign are reserved`() {
        assertTrue(isReservedTopic("\$SYS/broker/load"))
        assertFalse(isReservedTopic("/devices/\$x"))
        assertFalse(isReservedTopic(""))
    }
}
