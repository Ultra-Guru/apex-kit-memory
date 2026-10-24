package com.apex.memkit.core.store

import com.apex.memkit.core.SilentLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 文件存储后端：原子写 / 损坏备份 / 幂等删。 */
class FileStorageBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `写入读取往返`() = runTest {
        val backend = FileStorageBackend(tmp.newFolder(), SilentLogger)
        backend.write("a.json", "{\"x\":1}")
        assertEquals("{\"x\":1}", backend.read("a.json"))
    }

    @Test
    fun `不存在的文档读 null`() = runTest {
        val backend = FileStorageBackend(tmp.newFolder(), SilentLogger)
        assertNull(backend.read("missing.json"))
    }

    @Test
    fun `损坏文档备份留档并返回 null`() = runTest {
        val dir = tmp.newFolder()
        val backend = FileStorageBackend(dir, SilentLogger)
        backend.write("bad.json", "原内容")
        // 手动制造读取级损坏：收回读权限（readText 抛 IOException 的形态）
        assertTrue(dir.resolve("bad.json").setReadable(false))
        assertNull(backend.read("bad.json"))
        // .corrupt 备份存在（目录形态整目录改名留档）
        val backups = dir.listFiles { f -> f.name.startsWith("bad.json.corrupt") }
        assertTrue(backups != null && backups.isNotEmpty())
    }

    @Test
    fun `删除幂等`() = runTest {
        val backend = FileStorageBackend(tmp.newFolder(), SilentLogger)
        backend.write("a.json", "1")
        backend.delete("a.json")
        backend.delete("a.json")  // 不抛
        assertNull(backend.read("a.json"))
    }

    @Test
    fun `目录不存在时自动创建`() = runTest {
        val dir = tmp.newFolder().resolve("nested/child")
        val backend = FileStorageBackend(dir, SilentLogger)
        backend.write("a.json", "x")
        assertEquals("x", backend.read("a.json"))
    }
}
