package com.ziar.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersionsTest {
    @Test fun newerVersionsAreComparedNumerically() {
        assertEquals(1, AppVersions.compare("0.10.0", "0.9.9"))
        assertEquals(0, AppVersions.compare("1.2.3", "1.2.3"))
        assertEquals(-1, AppVersions.compare("1.2.2", "1.2.3"))
        assertEquals(null, AppVersions.compare("1.2", "1.2.3"))
    }
}
