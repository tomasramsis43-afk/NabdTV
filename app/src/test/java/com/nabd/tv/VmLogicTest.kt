package com.nabd.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VmLogicTest {
    private val eps = listOf(
        Episode(1, 1, "a", "u1"), Episode(1, 2, "b", "u2"), Episode(1, 3, "c", "u3")
    )

    @Test fun nextEpisodeWalksForwardAndStopsAtTheEnd() {
        assertEquals("u2", VmLogic.nextEpisode(eps, "u1")?.url)
        assertNull(VmLogic.nextEpisode(eps, "u3"))
    }

    @Test fun previousEpisodeWalksBackAndStopsAtTheStart() {
        assertEquals("u2", VmLogic.previousEpisode(eps, "u3")?.url)
        assertNull(VmLogic.previousEpisode(eps, "u1"))
    }

    @Test fun unknownUrlHasNoNeighbours() {
        assertNull(VmLogic.nextEpisode(eps, "zzz"))
        assertNull(VmLogic.previousEpisode(eps, "zzz"))
    }

    @Test fun friendlyErrorMapsNetworkFailures() {
        assertEquals("انتهت مهلة الاتصال بالسيرفر", VmLogic.friendlyError(java.net.SocketTimeoutException()))
        assertEquals("خطأ غير معروف", VmLogic.friendlyError(RuntimeException()))
        assertEquals("boom", VmLogic.friendlyError(RuntimeException("boom")))
    }
}
