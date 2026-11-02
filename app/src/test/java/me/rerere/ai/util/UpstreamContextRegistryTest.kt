package me.rerere.ai.util

import me.rerere.ai.provider.Model
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpstreamContextRegistryTest {
    @After
    fun tearDown() {
        UpstreamContextRegistry.clearForTest()
    }

    @Test
    fun `extracts context length from provider error message`() {
        val errorBody = """{"object":"error","message":"The input (571415 tokens) is longer than the model's context length (524288 tokens).","type":"BadRequestError","code":400}"""
        val model = Model(modelId = "agnes-3.0-flash", contextLength = null)

        val extracted = UpstreamContextRegistry.recordFromError(
            modelUuid = model.id.toString(),
            modelSlug = model.modelId,
            errorText = errorBody,
        )

        assertEquals(524_288, extracted)
        assertEquals(524_288, UpstreamContextRegistry.resolveContextLength(model))
    }

    @Test
    fun `explicit model contextLength takes precedence over discovered value`() {
        val model = Model(modelId = "agnes-3.0-flash", contextLength = 128_000)
        UpstreamContextRegistry.record(model.id.toString(), model.modelId, 524_288)

        assertEquals(128_000, UpstreamContextRegistry.resolveContextLength(model))
    }

    @Test
    fun `returns null when neither static nor discovered context length exists`() {
        val model = Model(modelId = "unknown-model", contextLength = null)
        assertNull(UpstreamContextRegistry.resolveContextLength(model))
    }
}
