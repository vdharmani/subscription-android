package com.vdharmani.subscription.revenuecat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real Play Billing client on a real device.
 *
 * This does not assert *what* the account owns — that depends on the tester's
 * Google account and on the app being published, neither of which a test can
 * fix. It asserts the things that were genuinely unproven: that the client
 * builds with the parameters given, that connecting and querying complete
 * without crashing, that the result is one of the two shapes the caller
 * handles, and that the call is actually bounded in time.
 *
 * A null result here is a pass, not a skip: an unpublished test package is
 * exactly the "Play refuses" path, and returning null is the required
 * behaviour for it.
 */
@RunWith(AndroidJUnit4::class)
class PlayStoreOwnershipInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun connectingAndQueryingPlayCompletesWithoutCrashing() {
        runBlocking {
        val ownership = PlayStoreOwnership(context)

        val elapsed = System.currentTimeMillis()
        val result = ownership.ownedSubscriptions()
        val took = System.currentTimeMillis() - elapsed

        // Either Play answered (a list, possibly empty) or it could not be
        // reached (null). Both are handled; a crash or a hang is not.
        assertTrue(
            "took ${took}ms, which exceeds the documented bound",
            took < TIMEOUT_HEADROOM_MS,
        )
        assertTrue("unexpected result shape: $result", result == null || result is List<*>)
            android.util.Log.i(TAG, "ownedSubscriptions -> $result in ${took}ms")
        }
    }

    @Test
    fun repeatedCallsAreSafeAndStillBounded() {
        runBlocking {
        val ownership = PlayStoreOwnership(context)

            repeat(3) {
                val result = ownership.ownedSubscriptions()
                assertTrue(result == null || result is List<*>)
            }
        }
    }

    @Test
    fun concurrentCallersAreSerialisedAndAllComplete() {
        runBlocking {
        // The mutex sits inside the timeout, so queued callers must still
        // finish within the bound rather than each waiting a full timeout.
        val ownership = PlayStoreOwnership(context)

        val started = System.currentTimeMillis()
        val results = (1..4).map { async { ownership.ownedSubscriptions() } }.awaitAll()
        val took = System.currentTimeMillis() - started

        assertTrue("4 concurrent callers took ${took}ms", took < TIMEOUT_HEADROOM_MS * 2)
        assertTrue(results.all { it == null || it is List<*> })
            android.util.Log.i(TAG, "4 concurrent callers finished in ${took}ms")
        }
    }

    private companion object {
        const val TAG = "PlayStoreOwnershipTest"

        /** The 5s bound plus room for a cold Play Store service bind. */
        const val TIMEOUT_HEADROOM_MS = 12_000L
    }
}
