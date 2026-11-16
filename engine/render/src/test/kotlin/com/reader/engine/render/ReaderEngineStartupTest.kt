package com.reader.engine.render

import android.graphics.Canvas
import com.reader.core.model.Locator
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderEngineStartupTest {

    @Test
    fun startupFailureCompletesWaiterInsteadOfLeavingReaderLoading() = runBlocking {
        val engine = TestReaderEngine()
        val waiting = async { engine.awaitReadyOrFailure() }
        val failure = ReaderEngineFailure(
            ReaderEngineFailureKind.INVALID_DOCUMENT,
            "文档无效或内容损坏，无法继续读取",
        )

        engine.failure.value = failure

        assertEquals(failure, withTimeout(2_000) { waiting.await() })
    }

    @Test
    fun readyCompletesWaiterWithoutFailure() = runBlocking {
        val engine = TestReaderEngine()
        engine.ready.value = true

        assertNull(engine.awaitReadyOrFailure())
    }

    @Test
    fun permissionFailureMapsToRecoverableCategory() {
        val failure = ReaderEngineFailure.fromException(
            SecurityException("provider detail"),
            "文档加载失败，请重试",
        )

        assertEquals(ReaderEngineFailureKind.PERMISSION_REVOKED, failure.kind)
        assertEquals("无法访问原文件，请重新授权或重新选择文档", failure.message)
    }

    @Test
    fun missingSourceMapsToRecoverableCategory() {
        val failure = ReaderEngineFailure.fromException(
            java.io.FileNotFoundException("provider detail"),
            "文档加载失败，请重试",
        )

        assertEquals(ReaderEngineFailureKind.SOURCE_UNAVAILABLE, failure.kind)
        assertEquals("原文件已移动或删除，请重新选择文档", failure.message)
    }

    @Test
    fun outOfMemoryFailureCompletesOpeningWaiterWithRecoverableCategory() = runBlocking {
        val engine = TestReaderEngine()
        val failure = ReaderEngineFailure.fromOutOfMemoryError(
            "文档过大或设备内存不足，无法完成分页，请重试",
        )

        engine.failure.value = failure

        assertEquals(failure, withTimeout(2_000) { engine.awaitReadyOrFailure() })
        assertEquals(ReaderEngineFailureKind.OUT_OF_MEMORY_RISK, failure.kind)
    }

    @Test
    fun unsupportedCapabilityKeepsExplicitProviderMessage() {
        val failure = ReaderEngineFailure.fromException(
            UnsupportedOperationException("此文件提供程序不支持随机访问"),
            "文档加载失败，请重试",
        )

        assertEquals(ReaderEngineFailureKind.UNSUPPORTED_CAPABILITY, failure.kind)
        assertEquals("此文件提供程序不支持随机访问", failure.message)
    }

    private class TestReaderEngine : ReaderEngine {
        override val pageCount = MutableStateFlow(PageCountInfo())
        override val ready = MutableStateFlow(false)
        override val failure = MutableStateFlow<ReaderEngineFailure?>(null)
        override val capabilities = emptySet<ReaderCapability>()

        override fun locatorOf(pageIndex: Int) = Locator(0, 0)
        override fun pageIndexOf(locator: Locator) = 0
        override fun drawPage(
            pageIndex: Int,
            canvas: Canvas,
            textColor: Int,
            backgroundColor: Int,
        ) = Unit
        override fun pageText(pageIndex: Int) = ""
        override fun close() = Unit
    }
}
