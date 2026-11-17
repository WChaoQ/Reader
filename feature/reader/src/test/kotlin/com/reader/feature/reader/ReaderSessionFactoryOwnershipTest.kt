package com.reader.feature.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSessionFactoryOwnershipTest {

    @Test
    fun cancellationDuringConstructionStillRecordsEngineOwnership() = runBlocking {
        val constructionStarted = CountDownLatch(1)
        val allowConstructionToFinish = CountDownLatch(1)
        val transferred = AtomicReference<String?>()

        val opening = launch(Dispatchers.Default) {
            createAndTransferOwnership(
                dispatcher = Dispatchers.Default,
                create = {
                    constructionStarted.countDown()
                    check(allowConstructionToFinish.await(2, TimeUnit.SECONDS))
                    "engine"
                },
                transfer = transferred::set,
            )
        }

        assertTrue(
            withContext(Dispatchers.IO) {
                constructionStarted.await(2, TimeUnit.SECONDS)
            },
        )
        opening.cancel()
        allowConstructionToFinish.countDown()

        withTimeout(2_000) { opening.join() }

        assertEquals("engine", transferred.get())
        assertTrue(opening.isCancelled)
    }

    @Test
    fun failedConstructionDoesNotTransferOwnership() = runBlocking {
        val transferred = AtomicReference<String?>()
        val expected = IllegalStateException("engine construction failed")

        val actual = try {
            createAndTransferOwnership<String>(
                dispatcher = Dispatchers.Default,
                create = { throw expected },
                transfer = transferred::set,
            )
            throw AssertionError("Expected construction to fail")
        } catch (failure: IllegalStateException) {
            failure
        }

        assertSame(expected, actual)
        assertNull(transferred.get())
    }
}
