package com.launchpoint.stereosine.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrimaryNavigationTest {
    private val tabs = listOf("home", "songs", "library", "settings/statistics")

    private fun controller(start: String = "home"): NavHostController {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        return NavHostController(RuntimeEnvironment.getApplication()).apply {
            setLifecycleOwner(owner)
            setViewModelStore(ViewModelStore())
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = start) {
                (tabs + listOf("child", "now_playing", "settings", "onboarding")).forEach { route ->
                    composable(route) {}
                }
            }
        }
    }

    private fun go(nav: NavHostController, route: String) = nav.navigatePrimary(route)

    private fun routes(nav: NavHostController) = nav.currentBackStack.value
        .mapNotNull { it.destination.route }

    @Test fun `each requested main destination is reached with one call`() {
        for (start in tabs) for (target in tabs) {
            val nav = controller(start)
            go(nav, target)
            assertEquals("$start -> $target", target, nav.currentDestination?.route)
        }
    }

    @Test fun `same destination is a strict no-op preserving entry state and notifications`() {
        for (route in tabs) {
            val nav = controller(route)
            val entry = nav.currentBackStackEntry!!
            entry.savedStateHandle["scroll"] = 37
            var events = 0
            nav.addOnDestinationChangedListener { _, _, _ -> events++ }
            val before = events
            repeat(10) { go(nav, route) }
            assertSame(route, entry, nav.currentBackStackEntry)
            assertEquals(37, nav.currentBackStackEntry!!.savedStateHandle.get<Int>("scroll"))
            assertEquals("No navigation notification for reselection of $route", before, events)
        }
    }

    @Test fun `switching tabs never restores a saved child as the visible destination`() {
        val nav = controller()
        go(nav, "songs")
        nav.navigate("child")
        go(nav, "library")
        go(nav, "songs")
        assertEquals("songs", nav.currentDestination?.route)
        assertFalse(routes(nav).contains("child"))
    }

    @Test fun `non Home startup alternating tabs have no duplicate destinations`() {
        for (start in tabs + listOf("settings", "now_playing")) {
            val nav = controller(start)
            repeat(8) {
                for (target in listOf("library", "settings/statistics", "songs", "home")) {
                    go(nav, target)
                    assertEquals(target, nav.currentDestination?.route)
                    assertEquals("Duplicate stack from $start: ${routes(nav)}", routes(nav).distinct(), routes(nav))
                    assertTrue("Only startup anchor and requested main remain", routes(nav).size <= 2)
                }
            }
        }
    }

    @Test fun `primary tabs override deeper screens without changing ordinary secondary Back`() {
        val nav = controller()
        nav.navigate("child")
        assertTrue(nav.popBackStack())
        assertEquals("home", nav.currentDestination?.route)
        nav.navigate("child")
        go(nav, "library")
        assertEquals(listOf("home", "library"), routes(nav))
        assertTrue(nav.popBackStack())
        assertEquals("home", nav.currentDestination?.route)
    }

    @Test fun `Back after switching returns to actual startup anchor`() {
        for (start in tabs) {
            val nav = controller(start)
            val target = tabs.first { it != start }
            go(nav, target)
            assertTrue(nav.popBackStack())
            assertEquals(start, nav.currentDestination?.route)
            assertFalse(nav.popBackStack())
        }
    }

    @Test fun `removed onboarding start falls back to root cleanup`() {
        val nav = controller("onboarding")
        nav.navigate("songs") { popUpTo("onboarding") { inclusive = true } }
        for (target in listOf("library", "settings/statistics", "home", "songs")) {
            go(nav, target)
            assertEquals(listOf(target), routes(nav))
        }
    }
}
