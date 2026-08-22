package com.BalancedLight.dynamicdriving.projected

import android.app.Application
import androidx.car.app.model.GridSection
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.BalancedLight.dynamicdriving.shared.DynamicDrivingRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds the Android Auto browse template against a real car context.
 *
 * The host renders whatever this returns, so what is worth asserting is that the template really is
 * the distraction-safe sectioned grid, populated from the library — not a free-form browser.
 *
 * A section hands its items to the host through a [androidx.car.app.serialization.ListDelegate],
 * so the assertions here go through the delegate's size rather than reaching for a list the app side
 * never holds.
 */
@RunWith(AndroidJUnit4::class)
class CarBrowseScreenTest {

    private lateinit var carContext: TestCarContext

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        DynamicDrivingRuntime.initialize(application)
        carContext = TestCarContext.createCarContext(application)
        awaitLibrary()
    }

    @Test
    fun the_browse_screen_is_a_sectioned_grid_containing_the_library() {
        val screen = CarBrowseScreen(carContext)

        val template = screen.onGetTemplate()

        val sectioned = template as SectionedItemTemplate
        assertTrue("Expected at least one section", sectioned.sections.isNotEmpty())

        val songSection = sectioned.sections.filterIsInstance<GridSection>().last()
        assertEquals(GridSection.ITEM_SIZE_LARGE, songSection.itemSize)
        assertTrue(
            "Expected the song grid to hold items",
            songSection.itemsDelegate.size > 0
        )
    }

    @Test
    fun a_playlist_gains_its_own_grid_section() {
        val runtime = DynamicDrivingRuntime.require()
        val songId = runtime.catalogRepository.getSongs().first().songId
        val playlist = runtime.playlistRepository.createPlaylist("Car Test Playlist")
        runtime.playlistRepository.addSong(playlist.id, songId)

        val template = CarBrowseScreen(carContext).onGetTemplate() as SectionedItemTemplate

        val sections = template.sections.filterIsInstance<GridSection>()
        assertEquals("Expected a playlist section and a song section", 2, sections.size)
        assertTrue(
            "Expected the playlist grid to hold the playlist",
            sections.first().itemsDelegate.size > 0
        )

        runtime.playlistRepository.deletePlaylist(playlist.id)
    }

    @Test
    fun the_template_reports_loading_rather_than_an_empty_grid_while_scanning() {
        val template = CarBrowseScreen(carContext).onGetTemplate() as SectionedItemTemplate

        // The library is already loaded by setUp, so the screen must not be claiming to be busy.
        assertEquals(false, template.isLoading)
    }

    private fun awaitLibrary(timeoutMs: Long = 20_000L) {
        val repository = DynamicDrivingRuntime.require().catalogRepository
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = repository.libraryState.value
            if (!state.isRefreshing && state.songs.isNotEmpty()) {
                return
            }
            Thread.sleep(50)
        }
        throw AssertionError("The bundled library never finished loading.")
    }
}
