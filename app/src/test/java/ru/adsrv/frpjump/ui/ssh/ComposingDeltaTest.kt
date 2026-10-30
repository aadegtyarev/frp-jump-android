package ru.adsrv.frpjump.ui.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposingDeltaTest {
    @Test
    fun growingWordSendsOnlyTheNewLetter() {
        assertEquals(ComposingDelta(0, "n"), composingDelta("m", "mn"))
    }

    @Test
    fun commitOfTheSameWordSendsNothing() {
        assertEquals(ComposingDelta(0, ""), composingDelta("mnt", "mnt"))
    }

    @Test
    fun correctionErasesTheDifferingTail() {
        assertEquals(ComposingDelta(2, "int"), composingDelta("mnt", "mint"))
    }

    @Test
    fun clearedComposingErasesEverything() {
        assertEquals(ComposingDelta(3, ""), composingDelta("мнт", ""))
    }

    @Test
    fun surrogatePairIsErasedAsOneCharacter() {
        // Two emoji sharing a high surrogate: the whole old one goes, the whole new one comes.
        assertEquals(ComposingDelta(1, "😁"), composingDelta("a😀", "a😁"))
    }
}
