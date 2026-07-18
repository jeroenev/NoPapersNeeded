package dev.npn.core.journal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory [SegmentStore] for tests. Thread-safe via a mutex. */
class InMemorySegmentStore : SegmentStore {
    // deviceId -> (fileName -> content)
    private val data = LinkedHashMap<String, LinkedHashMap<String, String>>()
    private val mutex = Mutex()

    override suspend fun listSegments(): List<SegmentRef> = mutex.withLock {
        data.entries.flatMap { (device, files) ->
            files.keys.map { SegmentRef(device, it) }
        }
    }

    override suspend fun readSegment(ref: SegmentRef): String = mutex.withLock {
        data[ref.deviceId]?.get(ref.fileName)
            ?: throw IllegalStateException("No such segment: ${ref.deviceId}/${ref.fileName}")
    }

    override suspend fun writeSegment(deviceId: String, fileName: String, content: String) = mutex.withLock {
        val files = data.getOrPut(deviceId) { LinkedHashMap() }
        require(!files.containsKey(fileName)) { "Segment already exists: $deviceId/$fileName" }
        files[fileName] = content
    }
}
