package com.nethra.app.speech

import com.nethra.app.core.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {

    @Test fun recordingCommands() {
        assertEquals(Utterance.Command(VoiceCommand.START_RECORDING), WakeWord.parse("Nethra, start recording"))
        assertEquals(Utterance.Command(VoiceCommand.PAUSE), WakeWord.parse("Nethra pause"))
        assertEquals(Utterance.Command(VoiceCommand.RESUME), WakeWord.parse("nethra resume"))
        assertEquals(Utterance.Command(VoiceCommand.STOP), WakeWord.parse("Nethra stop."))
    }

    @Test fun fuzzySpellingsOfTheWakeWord() {
        assertEquals(Utterance.Command(VoiceCommand.PAUSE), WakeWord.parse("Netra pause"))
        assertEquals(Utterance.Command(VoiceCommand.RESUME), WakeWord.parse("nitra resume"))
        assertEquals(Utterance.Command(VoiceCommand.STOP), WakeWord.parse("net ra stop"))
        assertTrue(WakeWord.isWakeToken("Nethraa"))
        assertTrue(WakeWord.isWakeToken("nethra,"))
        assertFalse(WakeWord.isWakeToken("network"))
        assertFalse(WakeWord.isWakeToken("neural"))
        assertFalse(WakeWord.isWakeToken("never"))
    }

    @Test fun scriptRequestKeepsTheOriginalBrief() {
        val u = WakeWord.parse("Nethra, write a script about solar panels for beginners")
        assertEquals(Utterance.ScriptRequest("write a script about solar panels for beginners"), u)
    }

    @Test fun wakeAloneAndPlainSpeech() {
        assertEquals(Utterance.Wake(""), WakeWord.parse("Nethra"))
        assertEquals(Utterance.Wake("a video about coffee for students"), WakeWord.parse("Nethra, a video about coffee for students"))
        assertEquals(Utterance.Plain("what a nice day"), WakeWord.parse("what a nice day "))
    }

    @Test fun commandOnlyCountsAtTheStart() {
        assertNull(WakeWord.commandIn(Text.tokens("the video should stop here")))
        assertNull(WakeWord.commandIn(emptyList()))
        assertEquals(VoiceCommand.START_RECORDING, WakeWord.commandIn(Text.tokens("start")))
        assertEquals(VoiceCommand.STOP, WakeWord.commandIn(Text.tokens("end recording")))
        assertEquals(VoiceCommand.PAUSE, WakeWord.commandIn(Text.tokens("pose")))
        assertEquals(VoiceCommand.RESUME, WakeWord.commandIn(Text.tokens("carry on")))
        // "start" followed by an unrelated word is not a command.
        assertNull(WakeWord.commandIn(Text.tokens("start thinking about it")))
    }
}

class WakeWordVoiceCommandTest {

    @Test fun theFourRecordingCommandsInManyForms() {
        val cases = mapOf(
            "Nethra start recording" to VoiceCommand.START_RECORDING,
            "Nethra, start the recording" to VoiceCommand.START_RECORDING,
            "hey Nethra start recording" to VoiceCommand.START_RECORDING,
            "Nethra's start recording" to VoiceCommand.START_RECORDING,
            "netra star recording" to VoiceCommand.START_RECORDING,
            "Nethra stop" to VoiceCommand.STOP,
            "Nethra stop recording" to VoiceCommand.STOP,
            "Nethra stopped" to VoiceCommand.STOP,
            "Nethra pause" to VoiceCommand.PAUSE,
            "Nethra pause recording" to VoiceCommand.PAUSE,
            "nitra paused" to VoiceCommand.PAUSE,
            "Nethra resume" to VoiceCommand.RESUME,
            "Nethra resume recording" to VoiceCommand.RESUME,
            "Nethra presume" to VoiceCommand.RESUME,
            "Nethra continue" to VoiceCommand.RESUME,
        )
        for ((text, cmd) in cases) assertEquals(text, Utterance.Command(cmd), WakeWord.parse(text))
    }

    @Test fun fastCommandsFromPartialResults() {
        assertEquals(VoiceCommand.STOP, WakeWord.fastCommand("Nethra stop"))
        assertEquals(VoiceCommand.PAUSE, WakeWord.fastCommand("nethra pause"))
        assertEquals(VoiceCommand.RESUME, WakeWord.fastCommand("Nethra resume"))
        assertEquals(VoiceCommand.START_RECORDING, WakeWord.fastCommand("Nethra start recording"))
        // "start" alone may still become "start writing a script…": wait for the final result.
        assertNull(WakeWord.fastCommand("Nethra start"))
        assertNull(WakeWord.fastCommand("Nethra"))
        assertNull(WakeWord.fastCommand("please stop"))
        assertNull(WakeWord.fastCommand("Nethra write a script about stopping"))
    }
}

class WakeWordSpellingsTest {
    @Test fun commonMishearingsStillWake() {
        for (w in listOf("Netro", "Nether", "Nehra", "Nethra.", "NETHRA", "nethra's", "net ra"))
            assertEquals(w, Utterance.Wake(""), WakeWord.parse(w))
        assertEquals(Utterance.Plain("never mind"), WakeWord.parse("never mind"))
        assertEquals(Utterance.Plain("network issue"), WakeWord.parse("network issue"))
    }
}

class BareCommandTest {
    @Test fun unambiguousPhrasesWorkWithoutTheWakeWord() {
        assertEquals(VoiceCommand.STOP, WakeWord.bareCommand("stop recording"))
        assertEquals(VoiceCommand.STOP, WakeWord.bareCommand("Okay stop the recording"))
        assertEquals(VoiceCommand.PAUSE, WakeWord.bareCommand("pause recording"))
        assertEquals(VoiceCommand.RESUME, WakeWord.bareCommand("resume recording"))
        assertEquals(VoiceCommand.START_RECORDING, WakeWord.bareCommand("start recording"))
        assertEquals(VoiceCommand.START_RECORDING, WakeWord.bareCommand("start the video"))
    }

    @Test fun ordinarySpeechIsNotACommand() {
        assertNull(WakeWord.bareCommand("stop"))
        assertNull(WakeWord.bareCommand("we should stop recording videos at night because"))
        assertNull(WakeWord.bareCommand("I love recording"))
        assertNull(WakeWord.bareCommand("recording studio tips"))
        assertNull(WakeWord.bareCommand("please don't stop"))
    }

    @Test fun nearMissesOfCommandWords() {
        assertEquals(Utterance.Command(VoiceCommand.RESUME), WakeWord.parse("Nethra resumed"))
        assertEquals(Utterance.Command(VoiceCommand.PAUSE), WakeWord.parse("Nethra please pause"))
        assertEquals(VoiceCommand.STOP, WakeWord.bareCommand("stop recordin"))
        assertEquals("pause", WakeWord.normaliseCommandWord("pausе".replace('е', 'e')))
    }
}
