package net.prototux.aare.ui.car

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class AgendaRowsFitTest {
    @Test
    fun onlyWholeRowsFit() {
        // 16 dp padding top and bottom, 76 dp rows 8 dp apart: 3 rows need 32 + 228 + 16 = 276 dp.
        assertEquals(3, agendaRowsThatFit(276.dp, 76.dp, padding = 16.dp, spacing = 8.dp))
        assertEquals(2, agendaRowsThatFit(275.dp, 76.dp, padding = 16.dp, spacing = 8.dp))
    }

    @Test
    fun aTinyCardFitsNone() {
        assertEquals(0, agendaRowsThatFit(40.dp, 76.dp, padding = 16.dp, spacing = 8.dp))
        assertEquals(0, agendaRowsThatFit(0.dp, 76.dp, padding = 16.dp, spacing = 8.dp))
    }
}
