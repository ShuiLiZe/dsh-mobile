package io.github.sayach.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DshBootArcTest {
    @Test fun readsTheArcDshPublishesOnItsBootSpinner() {
        assertEquals(144f, dshBootArcDegrees("\"144deg\""))
        assertEquals(72f, dshBootArcDegrees("\" 72deg\""))
    }

    @Test fun ignoresAMissingSpinner() {
        assertNull(dshBootArcDegrees("\"\""))
        assertNull(dshBootArcDegrees("null"))
        assertNull(dshBootArcDegrees(null))
    }
}

class DshLongArcLoaderTest {
    @Test fun dshBootSpinnerIsPinnedToTheSameLongArcAsTheNativeRing() {
        org.junit.Assert.assertTrue(DSH_LONG_ARC_LOADER.contains("[data-dsh-boot-spinner]{--dsh-boot-arc:${DSH_LONG_ARC_DEGREES}deg !important}"))
    }
}
