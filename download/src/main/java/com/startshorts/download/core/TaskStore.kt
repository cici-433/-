package com.startshorts.download.core

import android.util.AtomicFile
import com.startshorts.download.api.TaskStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 任务持久化存储（最小实现）：
 * - 使用 [AtomicFile] 保证写入原子性（避免半写入导致的 JSON 损坏）；
 * - 以 tasks.json 单文件保存任务快照，适用于任务数量不大、读写频率可控的场景；
 * - 所有读写通过 Mutex 串行化，避免并发写入导致的竞态与覆盖。
 *
 * 说明：
 * - 当前实现为了简单，每次 update/get/list 都会读取全量文件并回写全量；
 * - 若需要支撑更高吞吐（大量任务/高频进度），建议替换为 Room/SQLite + 增量更新。
 */
internal class TaskStore(private val baseDir: File) {
    private val mutex = Mutex()
    private val file = AtomicFile(File(baseDir, "tasks.json"))

    /**
     * 插入或更新任务（以 taskId 为主键）。
     */
    suspend fun upsert(task: TaskRecord) {
        mutex.withLock {
            val map = readAllLocked().toMutableMap()
            map[task.taskId] = task
            writeAllLocked(map)
        }
    }

    suspend fun get(taskId: String): TaskRecord? {
        return mutex.withLock { readAllLocked()[taskId] }
    }

    /**
     * 删除任务记录（不负责删除文件）。
     */
    suspend fun delete(taskId: String) {
        mutex.withLock {
            val map = readAllLocked().toMutableMap()
            map.remove(taskId)
            writeAllLocked(map)
        }
    }

    suspend fun update(taskId: String, block: (TaskRecord) -> TaskRecord): TaskRecord? {
        return mutex.withLock {
            val map = readAllLocked().toMutableMap()
            val old = map[taskId] ?: return@withLock null
            val updated = block(old)
            map[taskId] = updated
            writeAllLocked(map)
            updated
        }
    }

    suspend fun updateStatus(taskId: String, status: TaskStatus, reason: String? = null): TaskRecord? {
        return update(taskId) {
            it.copy(
                status = status,
                errorReason = reason,
                updateTimeMs = System.currentTimeMillis()
            )
        }
    }

    /**
     * 列出所有任务快照。
     */
    suspend fun list(): List<TaskRecord> {
        return mutex.withLock { readAllLocked().values.toList() }
    }

    private fun readAllLocked(): Map<String, TaskRecord> {
        return try {
            if (!file.baseFile.exists()) return emptyMap()
            val bytes = file.readFully()
            if (bytes.isEmpty()) return emptyMap()
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            val tasks = root.optJSONArray("tasks") ?: JSONArray()
            buildMap {
                for (i in 0 until tasks.length()) {
                    val task = TaskRecord.fromJson(tasks.getJSONObject(i))
                    put(task.taskId, task)
                }
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun writeAllLocked(map: Map<String, TaskRecord>) {
        val root = JSONObject()
        val tasks = JSONArray()
        map.values.forEach { tasks.put(it.toJson()) }
        root.put("tasks", tasks)

        baseDir.mkdirs()
        val out = file.startWrite()
        try {
            out.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (t: Throwable) {
            file.failWrite(out)
            throw t
        }
    }
}
