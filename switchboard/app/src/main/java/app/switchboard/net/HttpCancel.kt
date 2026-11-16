package app.switchboard.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response

/**
 * Runs a blocking OkHttp call so that cancelling the coroutine (the Stop button) aborts it at once.
 * A plain execute() ignores cancellation and can block until the read timeout, which keep-alive pings keep resetting.
 */
suspend fun <T> Call.cancellable(block: (Response) -> T): T = interruptibly({ cancel() }) { execute().use(block) }

/** Runs blocking [work] on the IO pool; if the coroutine is cancelled, [onCancel] unblocks it (closes the socket, etc.). */
suspend fun <T> interruptibly(onCancel: () -> Unit, work: () -> T): T = withContext(Dispatchers.IO) {
    val job = async { work() }
    try { job.await() } catch (e: CancellationException) { runCatching(onCancel); throw e }
}
