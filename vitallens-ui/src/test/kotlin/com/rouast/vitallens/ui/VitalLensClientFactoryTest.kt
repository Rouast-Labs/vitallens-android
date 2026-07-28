package com.rouast.vitallens.ui

import com.rouast.vitallens.inference.network.ApiInference
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalLensClientFactoryTest {

    @Test
    fun `resolveCustomStrategy returns null when no base URL override is given`() {
        val strategy = resolveCustomStrategy(
            apiKey = "key", proxyUrl = null, method = "vitallens", overrideFps = null, baseUrl = null,
        )
        assertNull(strategy)
    }

    @Test
    fun `resolveCustomStrategy returns an ApiInference strategy when a base URL override is given`() {
        val strategy = resolveCustomStrategy(
            apiKey = "key",
            proxyUrl = null,
            method = "vitallens",
            overrideFps = null,
            baseUrl = "https://api-dev.rouast.com/vitallens-v3".toHttpUrl(),
        )
        assertTrue(strategy is ApiInference)
    }
}
