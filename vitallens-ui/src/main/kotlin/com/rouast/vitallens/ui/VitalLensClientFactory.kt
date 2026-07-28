package com.rouast.vitallens.ui

import com.rouast.vitallens.inference.InferenceStrategy
import com.rouast.vitallens.inference.network.ApiInference
import okhttp3.HttpUrl

/**
 * Resolves the custom [InferenceStrategy] needed to point at a non-production API host, or `null`
 * to fall back to `VitalLens`'s own default (production) strategy.
 *
 * Not a Swift port — `VitalLensScanView`/`VitalLensMonitorView`/`VitalLensFileView` have no
 * base-URL override, and neither does `VitalLens`'s own public constructor. `ApiInference`'s
 * `baseUrl` resolution falls back to `environment["VITALLENS_BASE_URL"]`, but `environment`
 * defaults to `System.getenv()`, which is a no-op on a real Android app process (see
 * `IntegrationTest.kt`, which already has to construct `ApiInference` directly with an explicit
 * `environment` map for exactly this reason). This does the same thing for the vitallens-ui
 * screens' optional `baseUrl` parameter.
 */
internal fun resolveCustomStrategy(
    apiKey: String?,
    proxyUrl: HttpUrl?,
    method: String,
    overrideFps: Double?,
    baseUrl: HttpUrl?,
): InferenceStrategy? = baseUrl?.let {
    ApiInference(
        apiKey = apiKey,
        proxyUrl = proxyUrl,
        requestedModel = if (method == "vitallens") null else method,
        overrideFps = overrideFps,
        environment = mapOf("VITALLENS_BASE_URL" to it.toString()),
    )
}
