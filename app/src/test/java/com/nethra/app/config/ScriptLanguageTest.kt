package com.nethra.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptLanguageTest {

    @Test fun wordTargetsFollowEachLanguagesPace() {
        // A minute of Telugu carries far fewer words than a minute of English.
        assertEquals(150, ScriptLanguage.ENGLISH.wordsForSeconds(60))
        assertEquals(120, ScriptLanguage.HINDI.wordsForSeconds(60))
        assertEquals(105, ScriptLanguage.TELUGU.wordsForSeconds(60))
        assertTrue(ScriptLanguage.TELUGU.wordsForSeconds(30) < ScriptLanguage.ENGLISH.wordsForSeconds(30))
        // Very short clips still ask for something writable.
        assertEquals(20, ScriptLanguage.TELUGU.wordsForSeconds(5))
    }

    @Test fun onlyIndianLanguagesNeedARomanisedCopy() {
        assertFalse(ScriptLanguage.ENGLISH.needsRomanisation)
        assertTrue(ScriptLanguage.HINDI.needsRomanisation)
        assertTrue(ScriptLanguage.TELUGU.needsRomanisation)
    }

    @Test fun syllableRatesAreOrdered() {
        assertTrue(ScriptLanguage.ENGLISH.syllablesPerWord < ScriptLanguage.HINDI.syllablesPerWord)
        assertTrue(ScriptLanguage.HINDI.syllablesPerWord < ScriptLanguage.TELUGU.syllablesPerWord)
    }

    @Test fun codesRoundTripAndUnknownFallsBackToEnglish() {
        for (l in ScriptLanguage.entries) assertEquals(l, ScriptLanguage.of(l.code))
        assertEquals(ScriptLanguage.ENGLISH, ScriptLanguage.of(null))
        assertEquals(ScriptLanguage.ENGLISH, ScriptLanguage.of("fr"))
    }

    @Test fun scriptChoicesDefaultToMixedAndNameIt() {
        assertEquals(PrompterScript.MIXED, PrompterScript.of(null))
        assertEquals(CaptionScript.MIXED, CaptionScript.of("bogus"))
        for (c in CaptionScript.entries) assertEquals(c, CaptionScript.of(c.code))
        assertEquals("Hinglish", PrompterScript.MIXED.label(ScriptLanguage.HINDI))
        assertEquals("Tinglish", CaptionScript.MIXED.label(ScriptLanguage.TELUGU))
        assertEquals("English", CaptionScript.TRANSLATED.label(ScriptLanguage.TELUGU))
        assertEquals("తెలుగు", CaptionScript.ORIGINAL.label(ScriptLanguage.TELUGU))
    }
}
