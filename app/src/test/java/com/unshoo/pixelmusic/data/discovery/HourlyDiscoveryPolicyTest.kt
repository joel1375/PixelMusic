package com.unshoo.pixelmusic.data.discovery

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HourlyDiscoveryPolicyTest {
    @Test fun retainsPicksInsideSameHour() { assertFalse(HourlyDiscoveryPolicy.isDue(7_199_999, 3_600_001, false)) }
    @Test fun refreshesAtBoundaryEvenIfLoadedAMomentBefore() { assertTrue(HourlyDiscoveryPolicy.isDue(7_200_000, 7_199_999, false)) }
    @Test fun catchesUpAfterAppWasClosedAndAfterClockMovesBack() {
        assertTrue(HourlyDiscoveryPolicy.isDue(36_000_000, 3_600_001, false))
        assertTrue(HourlyDiscoveryPolicy.isDue(3_600_001, 3_600_002, false))
    }
    @Test fun retriesAnEmptyOrUninitializedCache() {
        assertTrue(HourlyDiscoveryPolicy.isDue(4_000_000, 3_600_001, true))
        assertTrue(HourlyDiscoveryPolicy.isDue(100, 0, false))
    }
}
