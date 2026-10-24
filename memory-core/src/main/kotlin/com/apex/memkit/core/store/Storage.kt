package com.apex.memkit.core.store

import com.apex.memkit.core.KitLogger
import com.apex.memkit.core.SilentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 存储后端端口 —— 库与物理介质之间的唯一接缝。
 *
 * 库的全部持久化策略（命名文档 / 原子写 / 损坏备份）都在这层语义之上构建；
 * 宿主可换成任意实现（测试用 [InMemoryStorageBackend]，宿主默认
 * [FileStorageBackend]）。
 */
interface StorageBackend {
    /** 读取文档全文；不存在返回 null。 */
    suspend fun read(name: String): String?

    /** 原子写入文档全文（先写临时名再改名；实现须保证崩溃安全）。 */
    suspend fun write(name: String, content: String)

    /** 删除文档（不存在幂等）。 */
    suspend fun delete(name: String)
}

/**
 * 文件存储后端 —— 纪律沿用宿主仓库的「原子写」约定：
 * tmp + renameTo，rename 失败直写目标兜底；读取损坏时把原文件改名
 * `.corrupt` 留档并返回 null（记忆面不因单个坏文档击穿）。
 */
class FileStorageBackend(
    private val dir: File,
    private val logger: KitLogger = SilentLogger,
) : StorageBackend {

    init {
        if (!dir.exists() && !dir.mkdirs()) {
            logger.log(KitLogger.Level.WARN, TAG, "存储目录创建失败：$dir")
        }
    }

    private val io = Dispatchers.IO
    private val writeMutex = Mutex()

    override suspend fun read(name: String): String? = withContext(io) {
        val f = file(name)
        if (!f.isFile) return@withContext null
        runCatching { f.readText() }.getOrElse { e ->
            logger.log(KitLogger.Level.WARN, TAG, "文档读取失败（损坏备份）：$name — ${e.message}")
            runCatching {
                f.renameTo(File(dir, "$name.corrupt-${System.currentTimeMillis()}"))
            }
            null
        }
    }

    override suspend fun write(name: String, content: String) {
        writeMutex.withLock {
            withContext(io) {
                val target = file(name)
                val tmp = File(dir, "$name.tmp")
                runCatching {
                    tmp.writeText(content)
                    if (!tmp.renameTo(target)) {
                        // rename 失败兜底：直写目标（跨设备挂载点等场景）
                        target.writeText(content)
                        tmp.delete()
                    }
                }.onFailure { e ->
                    logger.log(KitLogger.Level.ERROR, TAG, "文档写入失败：$name — ${e.message}", e)
                }
            }
        }
    }

    override suspend fun delete(name: String) {
        withContext(io) { runCatching { file(name).delete() } }
    }

    private fun file(name: String): File = File(dir, name)

    companion object {
        private const val TAG = "FileStorage"
    }
}

/** 内存存储后端（测试 / 无持久化需求的宿主）。 */
class InMemoryStorageBackend : StorageBackend {
    private val docs = ConcurrentHashMap<String, String>()
    override suspend fun read(name: String): String? = docs[name]
    override suspend fun write(name: String, content: String) {
        docs[name] = content
    }

    override suspend fun delete(name: String) {
        docs.remove(name)
    }

    /** 测试辅助：当前全部文档快照。 */
    fun snapshot(): Map<String, String> = docs.toMap()
}
