package eve.hq.app.assets

import eve.hq.app.characters.AppSystem
import eve.hq.core.asset.AssetItem
import eve.hq.core.asset.AssetLocation
import eve.hq.core.asset.AssetSource
import eve.hq.core.asset.AssetType
import eve.hq.core.asset.ListAssets
import eve.hq.core.character.ListCharacters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AssetsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val system = AppSystem(scope, dispatcher)

    @Test
    fun `loading is visible until the inventory arrives`() {
        system.store(1, "Alice")
        val pending = CompletableDeferred<List<AssetItem>>()
        val viewModel = AssetsViewModel(
            ListCharacters(system.repository),
            ListAssets { pending.await() },
            scope,
            dispatcher,
        )

        assertTrue(viewModel.state.value.loading)
        pending.complete(
            listOf(AssetItem(101, AssetType(34, "Tritanium"), null, 42, AssetLocation(600, "Jita"))),
        )

        assertFalse(viewModel.state.value.loading)
        assertEquals(listOf("Tritanium"), viewModel.state.value.items.map { it.typeName })
    }

    @Test
    fun `refresh loads assets for the current character list`() {
        system.store(1, "Alice")
        val source = AssetSource { character ->
            listOf(AssetItem(character.id + 100, AssetType(34, "Tritanium"), null, 1, AssetLocation(600, "Jita")))
        }
        val viewModel = AssetsViewModel(ListCharacters(system.repository), ListAssets(source), scope, dispatcher)
        assertEquals(listOf("Alice"), viewModel.state.value.items.map { it.characterName })

        system.store(2, "Bob")
        viewModel.refresh()

        assertEquals(listOf("Alice", "Bob"), viewModel.state.value.items.map { it.characterName })
    }

    @Test
    fun `search filters the loaded inventory without requesting it again`() {
        system.store(1, "Alice")
        var loads = 0
        val source = AssetSource {
            loads++
            listOf(
                AssetItem(101, AssetType(34, "Tritanium"), null, 42, AssetLocation(600, "Jita")),
                AssetItem(102, AssetType(35, "Pyerite"), null, 3, AssetLocation(600, "Jita")),
            )
        }
        val viewModel = AssetsViewModel(ListCharacters(system.repository), ListAssets(source), scope, dispatcher)

        viewModel.setQuery("tRiTa")
        assertEquals(listOf("Tritanium"), viewModel.state.value.items.map { it.typeName })
        viewModel.setQuery("jItA")

        assertEquals(listOf("Pyerite", "Tritanium"), viewModel.state.value.items.map { it.typeName })
        assertEquals(1, loads)
    }

    @Test
    fun `a failed character is reported alongside successful asset rows`() {
        system.store(1, "Alice")
        system.store(2, "Bob")
        val source = AssetSource { character ->
            if (character.name == "Alice") error("Please log in again")
            listOf(AssetItem(102, AssetType(34, "Tritanium"), null, 3, AssetLocation(600, "Jita")))
        }

        val viewModel = AssetsViewModel(ListCharacters(system.repository), ListAssets(source), scope, dispatcher)

        assertEquals(listOf("Bob"), viewModel.state.value.items.map { it.characterName })
        assertEquals("Alice", viewModel.state.value.failures.single().characterName)
        assertEquals("Please log in again", viewModel.state.value.failures.single().message)
    }
}