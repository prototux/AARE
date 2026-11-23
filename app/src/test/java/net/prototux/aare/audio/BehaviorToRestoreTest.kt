package net.prototux.aare.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class BehaviorToRestoreTest {
    @Test
    fun aBehaviorOfItsOwnIsPutBack() {
        assertEquals(0, behaviorToRestore(0))
        assertEquals(3, behaviorToRestore(3))
    }

    @Test
    fun fullLeftByAStoppedAareBecomesVariable() {
        assertEquals(0, behaviorToRestore(1))
    }
}
