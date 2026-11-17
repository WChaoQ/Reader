package com.reader.engine.render

import java.io.Closeable

/**
 * Builds an engine around a renderer that takes ownership of a descriptor.
 *
 * Ownership transfers to [createRenderer] only when it returns successfully. The
 * returned renderer then owns the descriptor. Once [createEngine] succeeds, the
 * engine owns the renderer; if engine construction fails, this function closes it.
 */
internal fun <D : Closeable, R : Closeable, E> createEngineWithOwnedDescriptor(
    descriptor: D,
    createRenderer: (D) -> R,
    createEngine: (R) -> E,
): E {
    val renderer = try {
        createRenderer(descriptor)
    } catch (failure: Throwable) {
        runCatching { descriptor.close() }
        throw failure
    }

    return try {
        createEngine(renderer)
    } catch (failure: Throwable) {
        runCatching { renderer.close() }
        throw failure
    }
}
