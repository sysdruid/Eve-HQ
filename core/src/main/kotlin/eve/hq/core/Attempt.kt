package eve.hq.core

import kotlinx.coroutines.CancellationException

/** Captures a failure as a [Result] without swallowing coroutine cancellation. */
internal suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
