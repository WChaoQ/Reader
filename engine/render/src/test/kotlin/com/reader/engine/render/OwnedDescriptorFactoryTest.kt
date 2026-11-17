package com.reader.engine.render

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OwnedDescriptorFactoryTest {

    @Test
    fun rendererConstructionFailureClosesDescriptorOnce() {
        val descriptor = FakeDescriptor()
        val expected = IllegalStateException("renderer construction failed")

        val actual = try {
            createEngineWithOwnedDescriptor(
                descriptor = descriptor,
                createRenderer = { throw expected },
                createEngine = { throw AssertionError("Engine must not be created") },
            )
            throw AssertionError("Expected renderer construction to fail")
        } catch (failure: IllegalStateException) {
            failure
        }

        assertSame(expected, actual)
        assertEquals(1, descriptor.closeCalls.get())
    }

    @Test
    fun engineConstructionFailureClosesRendererAndItsDescriptorOnce() {
        val descriptor = FakeDescriptor()
        var renderer: FakeRenderer? = null
        val expected = IllegalArgumentException("engine construction failed")

        val actual = try {
            createEngineWithOwnedDescriptor(
                descriptor = descriptor,
                createRenderer = { owned ->
                    FakeRenderer(owned).also { renderer = it }
                },
                createEngine = { throw expected },
            )
            throw AssertionError("Expected engine construction to fail")
        } catch (failure: IllegalArgumentException) {
            failure
        }

        assertSame(expected, actual)
        assertEquals(1, renderer?.closeCalls?.get())
        assertEquals(1, descriptor.closeCalls.get())
    }

    @Test
    fun successfulConstructionTransfersRendererOwnershipToEngine() {
        val descriptor = FakeDescriptor()

        val engine = createEngineWithOwnedDescriptor(
            descriptor = descriptor,
            createRenderer = { owned -> FakeRenderer(owned) },
            createEngine = { it },
        )

        assertEquals(0, descriptor.closeCalls.get())
        assertEquals(0, engine.closeCalls.get())

        engine.close()

        assertEquals(1, engine.closeCalls.get())
        assertEquals(1, descriptor.closeCalls.get())
    }

    private class FakeDescriptor : Closeable {
        val closeCalls = AtomicInteger()
        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    private class FakeRenderer(
        private val descriptor: FakeDescriptor,
    ) : Closeable {
        val closeCalls = AtomicInteger()
        override fun close() {
            closeCalls.incrementAndGet()
            descriptor.close()
        }
    }
}
