package fit

interface SignalCache {
    fun load(videoPath: String): List<TrafficSignalNode>?
    fun save(videoPath: String, nodes: List<TrafficSignalNode>)
}
