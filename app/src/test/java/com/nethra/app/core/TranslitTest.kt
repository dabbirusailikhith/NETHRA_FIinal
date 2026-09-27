package com.nethra.app.core

import com.nethra.app.teleprompter.ScriptTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslitTest {

    @Test fun devanagariRomanises() {
        assertEquals("aaj", Translit.toLatin("आज").removeSuffix("a"))
        assertEquals("karenge", Translit.toLatin("करेंगे"))
        assertEquals("hai", Translit.toLatin("है"))
        assertEquals("video 2", Translit.toLatin("video २"))
    }

    @Test fun teluguRomanises() {
        assertEquals("nenu", Translit.toLatin("నేను"))
        assertEquals("manam", Translit.toLatin("మనం"))
        assertEquals("baagundi", Translit.toLatin("బాగుంది"))
    }

    private fun matches(heardNative: String, typed: String): Boolean =
        ScriptTracker.same(Translit.matchKey(heardNative), Translit.matchKey(typed))

    @Test fun heardHindiMatchesTypedHinglish() {
        listOf(
            "करेंगे" to "karenge", "करेंगे" to "karege", "पानी" to "pani", "पानी" to "paani",
            "अच्छा" to "accha", "अच्छा" to "acha", "करना" to "karna", "समझ" to "samajh",
            "आज" to "aaj", "आज" to "aj", "है" to "hai", "बात" to "baat", "नहीं" to "nahi",
            "ज़रूरी" to "zaroori", "ज़रूरी" to "jaruri"
        ).forEach { (n, t) -> assertTrue("$n ~ $t (${Translit.matchKey(n)} vs ${Translit.matchKey(t)})", matches(n, t)) }
    }

    @Test fun heardTeluguMatchesTypedTinglish() {
        listOf(
            "నేను" to "nenu", "చేయాలి" to "cheyali", "చాలా" to "chala", "బాగుంది" to "bagundi",
            "మనం" to "manam", "ఈరోజు" to "eeroju", "ఈరోజు" to "iroju", "చెప్పాను" to "cheppanu"
        ).forEach { (n, t) -> assertTrue("$n ~ $t (${Translit.matchKey(n)} vs ${Translit.matchKey(t)})", matches(n, t)) }
    }

    @Test fun differentWordsStayDifferent() {
        assertFalse(matches("पानी", "khana"))
        assertFalse(matches("आज", "kal"))
        assertFalse(matches("నేను", "meeru"))
        assertFalse(matches("చాలా", "bagundi"))
    }

    @Test fun trackerFollowsNativeSpeechThroughHinglishScript() {
        val script = "Aaj hum baat karenge camera ke baare mein. Yeh bahut zaroori hai."
        val t = ScriptTracker(script, keyOf = Translit::matchKey)
        assertTrue(t.onHeard("आज हम बात करेंगे"))
        assertEquals(4, t.position)
        t.onHeard("आज हम बात करेंगे कैमरा के बारे में")
        assertEquals(1, t.currentLine)
        t.onHeard("यह बहुत ज़रूरी है")
        assertTrue(t.isFinished)
    }

    @Test fun trackerFollowsTeluguThroughTinglishScript() {
        val script = "Nenu ee roju meeku oka tip cheppali. Idi chala useful."
        val t = ScriptTracker(script, keyOf = Translit::matchKey)
        t.onHeard("నేను ఈ రోజు మీకు ఒక టిప్ చెప్పాలి")
        assertEquals(1, t.currentLine)
        t.onHeard("ఇది చాలా useful")
        assertTrue(t.isFinished)
    }
}
