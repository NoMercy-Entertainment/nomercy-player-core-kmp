package tv.nomercy.player.core.ports

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNull

// The app builds its music player from a flow collector on a background
// dispatcher. ExoPlayer rejects calls from any thread but its own, so the
// backend must be constructible there.
class ExoBackendOffMainThreadTest {

    @Test
    fun backendCanBeConstructedOffTheMainThread() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val failure = AtomicReference<Throwable?>(null)

        val worker = Thread {
            try {
                ExoPlayerAudioBackend(context)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        worker.start()
        worker.join()

        assertNull(failure.get(), "construction off the main thread threw: ${failure.get()}")
    }
}
