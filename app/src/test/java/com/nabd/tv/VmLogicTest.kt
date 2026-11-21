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

class VmLogicListsTest {
    private fun ch(id: Int, name: String, rating: String? = null) =
        Channel(id, name, "u$id", null, "g", rating = rating)

    @Test fun topRatedSortsDescendingAcceptsCommaAndDropsUnrated() {
        val l = listOf(ch(1, "a", "6,5"), ch(2, "b", "8.1"), ch(3, "c", null), ch(4, "d", "0"), ch(5, "e", "x"))
        assertEquals(listOf("u2", "u1"), VmLogic.topRated(l).map { it.url })
    }

    @Test fun topRatedHonoursLimit() {
        val l = (1..5).map { ch(it, "n$it", "$it.0") }
        assertEquals(2, VmLogic.topRated(l, 2).size)
    }

    @Test fun dedupeKeepsLowestRankPerNormalizedNameAndPreservesOrder() {
        val l = listOf(ch(1, "beIN Sports 1 HD"), ch(2, "BEIN SPORTS 1 FHD"), ch(3, "Other"))
        val r = VmLogic.dedupeSources(l) { if (it.id == 2) 0 else 1 }
        assertEquals(listOf("u2", "u3"), r.map { it.url })
    }

    @Test fun dedupeKeepsFirstOnTieAndKeepsBlankNames() {
        val l = listOf(ch(1, "News HD"), ch(2, "News FHD"), ch(3, "HD"), ch(4, "FHD"))
        val r = VmLogic.dedupeSources(l) { 0 }
        assertEquals(listOf("u1", "u3", "u4"), r.map { it.url })
    }
}

class VmLogicTimeTest {
    private val progs = listOf(Prog(0, 100, "a"), Prog(100, 200, "b"), Prog(200, 300, "c"))

    @Test fun nowNextFindsCurrentAndFollowing() {
        val (n, nx) = VmLogic.nowNext(progs, 150)
        assertEquals("b", n?.title); assertEquals("c", nx?.title)
    }

    @Test fun nowNextAtLastProgrammeHasNoNext() {
        val (n, nx) = VmLogic.nowNext(progs, 250)
        assertEquals("c", n?.title); assertNull(nx)
    }

    @Test fun nowNextOutsideScheduleOrWithoutGuideIsEmpty() {
        assertEquals(null to null, VmLogic.nowNext(progs, 999))
        assertEquals(null to null, VmLogic.nowNext(null, 10))
    }

    @Test fun limitOnlyAppliesWithLimitPinAndLockedState() {
        assertEquals(true, VmLogic.limitReached(60, true, false, 3600))
        assertEquals(false, VmLogic.limitReached(60, true, false, 3599))
        assertEquals(false, VmLogic.limitReached(0, true, false, 99999))
        assertEquals(false, VmLogic.limitReached(60, false, false, 99999))
        assertEquals(false, VmLogic.limitReached(60, true, true, 99999))
    }
}

class VmLogicRecordTest {
    @Test fun alreadyScheduledWinsOverClash() = assertEquals("مجدول مسبقاً", VmLogic.recordMessage(false, true))
    @Test fun clashIsReportedButStillScheduled() = assertEquals("تمت الجدولة، لكنه يتعارض مع تسجيل آخر", VmLogic.recordMessage(true, true))
    @Test fun plainSuccess() = assertEquals("تمت جدولة التسجيل", VmLogic.recordMessage(true, false))
}

class VmLogicProblemTest {
    @Test fun deadAndSlowLines() {
        assertEquals("X — ميت · 3 فشل", VmLogic.problemLine("X", true, 3))
        assertEquals("Y — بطيء · 1 فشل", VmLogic.problemLine("Y", false, 1))
    }
}
