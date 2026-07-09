package fit

interface HttpRequester {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String
}
