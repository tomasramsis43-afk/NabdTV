package com.nabd.tv

import com.nabd.tv.SplashLogic.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashLogicTest {
    @Test fun waitsForTheMinimumTimeEvenWhenReadyAtOnce() {
        assertFalse(SplashLogic.canEnter(elapsedMs = 800, readyForMs = 600))
        assertTrue(SplashLogic.canEnter(elapsedMs = 1500, readyForMs = 600))
    }

    @Test fun readyMustBeStable() {
        assertFalse(SplashLogic.canEnter(elapsedMs = 3000, readyForMs = 100))
        assertFalse(SplashLogic.canEnter(elapsedMs = 3000, readyForMs = 0))
        assertTrue(SplashLogic.canEnter(elapsedMs = 3000, readyForMs = 400))
    }

    @Test fun neverTrapsTheUser() {
        assertTrue(SplashLogic.canEnter(elapsedMs = SplashLogic.CAP_MS, readyForMs = 0))
        assertFalse(SplashLogic.canEnter(elapsedMs = SplashLogic.CAP_MS - 1, readyForMs = 0))
    }

    @Test fun offlineGivesMoreTimeToRead() {
        assertFalse(SplashLogic.canEnter(2000, 1000, SplashLogic.OFFLINE_MIN_MS))
        assertTrue(SplashLogic.canEnter(3500, 1000, SplashLogic.OFFLINE_MIN_MS))
    }

    @Test fun stagesFollowTheRealState() {
        val loading = SplashLogic.stages(true, channelsLoaded = false, vodSettled = false, moviesLoaded = false, seriesLoaded = false, homeReady = false)
        assertEquals(listOf(Stage.Channels, Stage.Movies, Stage.Series, Stage.Home), loading.map { it.first })
        assertTrue(loading.none { it.second })

        val mid = SplashLogic.stages(true, true, false, moviesLoaded = true, seriesLoaded = false, homeReady = false)
        assertEquals(listOf(true, true, false, false), mid.map { it.second })

        val done = SplashLogic.stages(true, true, true, false, false, true)   // empty catalogue, but settled
        assertTrue(done.all { it.second })
    }

    @Test fun m3uListsHaveNoVodStages() {
        val s = SplashLogic.stages(false, true, true, false, false, true)
        assertEquals(listOf(Stage.Channels, Stage.Home), s.map { it.first })
    }

    @Test fun vodSettledAloneDoesNotCompleteAStageBeforeChannelsLoad() {
        val s = SplashLogic.stages(true, channelsLoaded = false, vodSettled = true, moviesLoaded = false, seriesLoaded = false, homeReady = false)
        assertFalse(s[1].second)
    }
}
