package fit

import fit.TrafficSignalNode

interface SignalCache {
    fun load(videoPath: String): List<TrafficSignalNode>?
    fun save(videoPath: String, nodes: List<TrafficSignalNode>)
}
