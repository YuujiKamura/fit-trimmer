package fit

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class JavaHttpRequester : HttpRequester {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override suspend fun get(url: String, headers: Map<String, String>): String {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .GET()

        headers.forEach { (k, v) ->
            builder.header(k, v)
        }

        val request = builder.build()
        val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())

        return future.await().body()
    }
}

// Suspend extension for CompletableFuture
private suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            this.cancel(true)
        }
        this.whenComplete { result, exception ->
            if (exception != null) {
                cont.resumeWithException(exception)
            } else {
                cont.resume(result)
            }
        }
    }
