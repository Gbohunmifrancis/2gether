package app.heartpact.data

import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException

/** What a call came back with. Failures keep the API's code so screens can react to specific ones. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Failure(val code: String, val message: String, val status: Int) : ApiResult<Nothing>
    data object Offline : ApiResult<Nothing>
}

const val OFFLINE_MESSAGE = "You're offline. Check your connection and try again."

/** Runs a Retrofit call and turns every outcome, including no network, into an [ApiResult]. */
suspend fun <T> apiCall(json: Json, block: suspend () -> Response<T>): ApiResult<T> {
    val response = try {
        block()
    } catch (_: IOException) {
        return ApiResult.Offline
    }
    if (response.isSuccessful) {
        @Suppress("UNCHECKED_CAST")
        return ApiResult.Ok(response.body() ?: Unit as T)
    }
    val error = runCatching { json.decodeFromString<ApiErrorBody>(response.errorBody()?.string().orEmpty()) }.getOrNull()
    val message = error?.message?.takeIf { it.isNotBlank() } ?: when (response.code()) {
        401 -> "Your session has ended. Sign in again."
        in 500..599 -> "Something went wrong on our side. Try again in a moment."
        else -> "That didn't work. Try again."
    }
    return ApiResult.Failure(error?.code ?: "http.${response.code()}", message, response.code())
}
