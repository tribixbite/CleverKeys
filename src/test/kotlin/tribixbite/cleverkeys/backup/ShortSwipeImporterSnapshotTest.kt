package tribixbite.cleverkeys.backup

import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tribixbite.cleverkeys.customization.ShortSwipeCustomizationManager

/**
 * Follow-up review 2026-11-15 of M6: the full-restore rollback snapshot must come from LOADED
 * mappings. `exportToJson` reads the manager's in-memory cache; in a process that had not loaded
 * it, the snapshot was empty and a restore that failed later put back NOTHING — erasing every
 * short swipe the user had.
 */
class ShortSwipeImporterSnapshotTest {

    @Test
    fun snapshotLoadsTheMappingsBeforeExportingThem() = runBlocking {
        val manager = mockk<ShortSwipeCustomizationManager>()
        coEvery { manager.loadMappings() } returns Unit
        every { manager.exportToJson() } returns """{"mappings":[]}"""

        assertEquals("""{"mappings":[]}""", RealShortSwipeImporter(manager).snapshot())

        coVerifyOrder {
            manager.loadMappings()
            manager.exportToJson()
        }
    }
}
