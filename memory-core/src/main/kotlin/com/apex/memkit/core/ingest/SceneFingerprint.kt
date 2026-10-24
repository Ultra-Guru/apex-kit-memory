package com.apex.memkit.core.ingest

import java.security.MessageDigest

/**
 * 内容指纹 —— 记忆库的身份体系。
 *
 * 公式与旧系统（UiTreePruner/NodeFingerprint）保持一致以兼容迁移数据：
 * `SHA-256("cls:X|res:Y|text:Z|role:R|parent:P")` 全 hex。父指纹参与哈希
 * 使同属性元素在不同容器中天然可区分。
 *
 * 与旧实现的差异：**每次调用独立 [MessageDigest] 实例**（`getInstance`
 * 廉价）—— 旧实现单例 + synchronized 在高帧率采集下是全局串行点。
 */
object SceneFingerprint {

    private val HEX = "0123456789abcdef"

    /** 节点指纹（含父上下文）。 */
    fun node(
        className: String,
        resourceId: String,
        text: String,
        role: String,
        parentFingerprint: String?,
    ): String {
        val payload = buildString {
            append("cls:").append(className)
            append("|res:").append(resourceId)
            append("|text:").append(text)
            append("|role:").append(role)
            append("|parent:").append(parentFingerprint ?: "-")
        }
        return sha256(payload)
    }

    /** 场景指纹：节点指纹排序去重后整体哈希（同一屏面稳定复现）。 */
    fun scene(fingerprints: Collection<String>): String =
        sha256(fingerprints.toSortedSet().joinToString("|"))

    /** 空间边稳定 ID：`e_` + 内容哈希前 16 位（同一条边任意两帧同 ID）。 */
    fun edge(sourceFp: String, targetFp: String): String =
        "e_" + sha256("$sourceFp|$targetFp").take(16)

    private fun sha256(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val i = b.toInt() and 0xff
            sb.append(HEX[i ushr 4]).append(HEX[i and 0x0f])
        }
        return sb.toString()
    }
}
