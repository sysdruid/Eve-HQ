package eve.hq.core

import kotlinx.coroutines.CancellationException
import java.util.logging.Level
import java.util.logging.Logger

private val log = Logger.getLogger("eve.hq.core")

/** Captures a failure as a [Result] without swallowing coroutine cancellation. */
internal suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // Callers keep only the message for the UI, so the stack trace is kept here.
    log.log(Level.WARNING, "Operation failed", e)
    Result.failure(e)
}
