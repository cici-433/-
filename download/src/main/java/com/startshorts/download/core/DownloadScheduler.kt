package com.startshorts.download.core

import com.startshorts.download.api.DownloadPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import kotlin.math.max

/**
 * 全局任务调度器（轻量实现）：
 * - 以 [DownloadPriority] + 入队时间做优先级排序；
 * - 通过 maxParallelTasks 控制全局并发任务数；
 * - runner 负责执行具体任务（由上层注入，便于解耦与测试）。
 *
 * 说明：
 * - 本实现不做“Host 级限流/公平性”等高级策略，作为最小可用骨架；
 * - 后续可在 submit 时引入 hostKey，结合 token-bucket 做更细粒度治理。
 */
internal class DownloadScheduler(
    private val scope: CoroutineScope,
    private val maxParallelTasks: Int,
    private val runner: suspend (String) -> Unit
) {
    private data class ScheduledTask(
        val taskId: String,
        val priority: DownloadPriority,
        val enqueueTimeMs: Long
    ) : Comparable<ScheduledTask> {
        override fun compareTo(other: ScheduledTask): Int {
            val p = other.priority.value.compareTo(priority.value)
            if (p != 0) return p
            return enqueueTimeMs.compareTo(other.enqueueTimeMs)
        }
    }

    private val queue = PriorityBlockingQueue<ScheduledTask>()
    private val scheduled = ConcurrentHashMap<String, ScheduledTask>()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val mutex = Mutex()

    /**
     * 启动调度循环：
     * - 周期性 drain，直到 scope 被取消；
     * - drain 内会尽可能填满并发槽位。
     */
    fun start() {
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                drain()
                delay(200)
            }
        }
    }

    /**
     * 提交任务进入调度队列：
     * - taskId 去重以最后一次提交为准（覆盖旧的入队记录）。
     */
    suspend fun submit(taskId: String, priority: DownloadPriority) {
        val task = ScheduledTask(taskId, priority, System.currentTimeMillis())
        scheduled[taskId] = task
        queue.put(task)
    }

    suspend fun unschedule(taskId: String) {
        scheduled.remove(taskId)?.let { queue.remove(it) }
    }

    /**
     * 取消正在执行的任务（协程 Job cancel）：
     * - runner 内需要正确响应取消（例如 IO 读写循环检查 isActive）。
     */
    suspend fun cancelRunning(taskId: String) {
        runningJobs.remove(taskId)?.cancel()
    }

    suspend fun isRunning(taskId: String): Boolean = mutex.withLock { runningJobs.containsKey(taskId) }

    private suspend fun drain() {
        mutex.withLock {
            val capacity = max(0, maxParallelTasks - runningJobs.size)
            repeat(capacity) {
                val next = queue.poll() ?: return
                val current = scheduled[next.taskId]
                if (current == null || current != next) return@repeat
                if (runningJobs.containsKey(next.taskId)) return@repeat

                val job = scope.launch(Dispatchers.IO) {
                    try {
                        runner(next.taskId)
                    } finally {
                        runningJobs.remove(next.taskId)
                    }
                }
                runningJobs[next.taskId] = job
                scheduled.remove(next.taskId)
            }
        }
    }
}
