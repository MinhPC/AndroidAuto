package com.minhphan.launcher.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An adapter that answers from a script: [answer] gets the command and how many times it has been asked so far. */
private class ScriptedLink(private val answer: (command: String, timesAsked: Int) -> String) : ElmLink {
    val asked = HashMap<String, Int>()

    override suspend fun send(command: String, timeoutMs: Long): String {
        val times = asked.merge(command, 1, Int::plus)!!
        return answer(command, times)
    }

    override fun close() = Unit
}

private const val NO_DATA = "NO DATA"

class ObdSessionTest {
    private fun read(link: ElmLink): List<ObdState> = runBlocking {
        val states = ArrayList<ObdState>()
        readUntilSilent(link) { states += it }
        states
    }

    @Test
    fun publishesTheDecodedValuesRoundAfterRound() {
        // The car answers for 3 rounds, then goes quiet (ignition off).
        val link = ScriptedLink { command, times ->
            when {
                command == "ATRV" -> "12.6V"
                times > 3 && command.startsWith("01") -> NO_DATA
                command == "010C" -> "41 0C 1A F8"
                command == "010D" -> "41 0D 3C"
                else -> NO_DATA
            }
        }
        val connected = read(link).filterIsInstance<ObdState.Connected>()
        assertEquals(12.6f, connected.first().values.voltage!!, 0.001f)
        val last = connected.last().values
        assertEquals(1726, last.rpm)
        assertEquals(60, last.speedKmh)
    }

    @Test
    fun readingsAreShownAsLastNotLiveOnceTheCarStopsAnswering() {
        val link = ScriptedLink { command, times ->
            when {
                command == "ATRV" -> "12.6V"
                command == "010D" && times <= 2 -> "41 0D 3C"
                command == "010C" && times <= 2 -> "41 0C 0F A0"
                else -> NO_DATA
            }
        }
        val states = read(link)
        val lastLive = states.indexOfLast { it is ObdState.Connected && it.values.speedKmh == 60 }
        val afterwards = states.drop(lastLive + 1)
        assertTrue("the silent rounds must be published", afterwards.isNotEmpty())
        assertTrue(afterwards.all { it is ObdState.Connecting && it.last?.speedKmh == 60 })
    }

    @Test
    fun speedAndRpmAreAskedForeverButASilentSlowValueIsGivenUpOn() {
        // Speed never answers, rpm does for 12 rounds and then everything goes quiet; the load never answers.
        val link = ScriptedLink { command, times ->
            when {
                command == "ATRV" -> "12.6V"
                command == "010C" && times <= 12 -> "41 0C 0F A0"
                command == "0105" && times <= 12 -> "41 05 6E"
                else -> NO_DATA
            }
        }
        read(link)
        val rounds = link.asked.getValue("010C")
        assertEquals(rounds, link.asked.getValue("010D")) // never dropped, so a late answer would still show
        assertEquals(3, link.asked.getValue("0104")) // the load: three misses, then no more questions
    }
}
