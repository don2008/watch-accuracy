package sk.watchaccuracy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchCatalogTest {
    @Test fun catalogHasDistinctBrandsAndModels() {
        assertTrue(WatchCatalog.brands.size >= 100)
        assertEquals(WatchCatalog.brands.size, WatchCatalog.brands.distinctBy { it.lowercase() }.size)
        assertTrue(WatchCatalog.models.values.all { models ->
            models.isNotEmpty() && models.size == models.distinctBy { it.lowercase() }.size
        })
    }

    @Test fun modelsAreSelectedByBrandWithoutConfusingSimilarNames() {
        assertTrue("Corsair" in WatchCatalog.modelsFor(" biatec "))
        assertTrue("Emme" in WatchCatalog.modelsFor("dwiss"))
        assertTrue("Seamaster Diver 300M" in WatchCatalog.modelsFor("Omega"))
        assertTrue(WatchCatalog.modelsFor("Custom watchmaker").isEmpty())
    }
}
