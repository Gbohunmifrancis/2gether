package app.heartpact.map

import androidx.lifecycle.Lifecycle.Event
import app.heartpact.ui.map.MapHost
import app.heartpact.ui.map.MapLifecycleDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLifecycleDriverTest {
    private class Recorder : MapHost {
        val calls = mutableListOf<String>()
        override fun onCreate() { calls += "create" }
        override fun onStart() { calls += "start" }
        override fun onResume() { calls += "resume" }
        override fun onPause() { calls += "pause" }
        override fun onStop() { calls += "stop" }
        override fun onDestroy() { calls += "destroy" }
    }

    private fun run(vararg events: Event): List<String> {
        val host = Recorder()
        val driver = MapLifecycleDriver(host)
        events.forEach(driver::onEvent)
        return host.calls
    }

    @Test fun `a normal life goes in order`() {
        assertEquals(
            listOf("create", "start", "resume", "pause", "stop", "destroy"),
            run(Event.ON_CREATE, Event.ON_START, Event.ON_RESUME, Event.ON_PAUSE, Event.ON_STOP, Event.ON_DESTROY),
        )
    }

    @Test fun `leaving for the background and coming back pauses and resumes`() {
        assertEquals(
            listOf("create", "start", "resume", "pause", "stop", "start", "resume"),
            run(Event.ON_CREATE, Event.ON_START, Event.ON_RESUME, Event.ON_PAUSE, Event.ON_STOP, Event.ON_START, Event.ON_RESUME),
        )
    }

    @Test fun `an event that arrives early brings the earlier steps with it`() {
        assertEquals(listOf("create", "start", "resume"), run(Event.ON_RESUME))
    }

    @Test fun `no step runs twice`() {
        assertEquals(
            listOf("create", "start", "resume"),
            run(Event.ON_CREATE, Event.ON_CREATE, Event.ON_START, Event.ON_START, Event.ON_RESUME, Event.ON_RESUME),
        )
    }

    @Test fun `disposing a running view brings it down in order, once`() {
        val host = Recorder()
        val driver = MapLifecycleDriver(host)
        driver.onEvent(Event.ON_RESUME)
        driver.dispose()
        driver.dispose()
        driver.onEvent(Event.ON_DESTROY)
        assertEquals(listOf("create", "start", "resume", "pause", "stop", "destroy"), host.calls)
        assertTrue(driver.destroyed)
    }

    @Test fun `disposing a view that never started does nothing to the map and nothing comes back after`() {
        val host = Recorder()
        val driver = MapLifecycleDriver(host)
        driver.dispose()
        driver.onEvent(Event.ON_RESUME)
        assertEquals(emptyList<String>(), host.calls)
        assertTrue(driver.destroyed)
    }

    @Test fun `a stopped view is destroyed without pausing again`() {
        val host = Recorder()
        val driver = MapLifecycleDriver(host)
        listOf(Event.ON_RESUME, Event.ON_PAUSE, Event.ON_STOP).forEach(driver::onEvent)
        driver.dispose()
        assertEquals(listOf("create", "start", "resume", "pause", "stop", "destroy"), host.calls)
    }
}
