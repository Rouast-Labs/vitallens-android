package com.rouast.vitallens.inference

/**
 * Errors specific to the VitalLens SDK and API interactions.
 *
 * [DecodingError] and [NetworkError] compare equal by their underlying
 * cause's [Throwable.message] rather than by instance identity, mirroring
 * the Swift original's `localizedDescription`-based equality (arbitrary
 * wrapped errors aren't required to be structurally comparable).
 */
sealed class VitalLensException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The provided API key is missing or invalid. */
    data object InvalidAPIKey : VitalLensException(
        "A valid VitalLens API key is required. Please check your configuration."
    )

    /** The API rejected the request due to quota limits (e.g., HTTP 429). */
    data object QuotaExceeded : VitalLensException(
        "VitalLens API quota exceeded. Please check your plan limits."
    )

    /** The API returned a server-side error (HTTP 5xx). */
    data class ServerError(
        val statusCode: Int,
        val serverMessage: String? = null,
    ) : VitalLensException(
        "VitalLens Server Error ($statusCode): ${serverMessage ?: "Unknown error"}"
    )

    /** The API returned a client-side error (HTTP 4xx) other than auth or quota issues. */
    data class ClientError(
        val statusCode: Int,
        val serverMessage: String? = null,
    ) : VitalLensException(
        "VitalLens Request Error ($statusCode): ${serverMessage ?: "Bad request"}"
    )

    /** The response from the API could not be successfully decoded. */
    class DecodingError(val underlying: Throwable) : VitalLensException(
        "Failed to parse API response: ${underlying.message}",
        underlying,
    ) {
        override fun equals(other: Any?): Boolean =
            other is DecodingError && underlying.message == other.underlying.message

        override fun hashCode(): Int = underlying.message.hashCode()
    }

    /** A general network failure, such as being offline or a connection timeout. */
    class NetworkError(val underlying: Throwable) : VitalLensException(
        "Network connection failed: ${underlying.message}",
        underlying,
    ) {
        override fun equals(other: Any?): Boolean =
            other is NetworkError && underlying.message == other.underlying.message

        override fun hashCode(): Int = underlying.message.hashCode()
    }

    /** An internal SDK error occurred during frame processing or inference setup. */
    data class ProcessingError(val detail: String) : VitalLensException(
        "Processing error: $detail"
    )
}
