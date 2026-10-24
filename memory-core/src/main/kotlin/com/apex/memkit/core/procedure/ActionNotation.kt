package com.apex.memkit.core.procedure

import com.apex.memkit.core.model.MacroAction

/**
 * 引擎动作记法 —— 宿主引擎的 `ToolName(args)` 动作描述字符串到
 * 类型化 [MacroAction] 的**唯一**解析点。
 *
 * 旧系统的同一字符串在 TraceDistiller.extractActionParams、
 * BypassExecutionEngine.extractInputText、CsMemSessionObserver.canonicalActionType
 * 三处各自正则解析（所谓「双保险」实为口径分裂的面具）；本库收口到一处，
 * 解析失败返回 null（非 UI 动作不进宏，蒸馏期即过滤）。
 */
object ActionNotation {

    /**
     * 解析动作描述。
     *
     * 支持的宿主工具名（大小写不敏感）：
     * - `UiTap(540,1200)` / `tap` / `click` → [MacroAction.Tap]
     * - `UiSwipe(x1,y1,x2,y2[,durationMs])` / `swipe` → [MacroAction.Swipe]
     * - `InputText("文本")`（单/双引号或裸文本）→ [MacroAction.Input]
     * - `back` / `home` → [MacroAction.Back] / [MacroAction.Home]
     */
    fun parse(description: String?): MacroAction? {
        if (description.isNullOrBlank()) return null
        val raw = description.trim()
        val open = raw.indexOf('(')
        val name = (if (open > 0) raw.substring(0, open) else raw).trim().lowercase()
        val argsRaw = if (open > 0 && raw.endsWith(")")) raw.substring(open + 1, raw.length - 1) else ""

        return when (name) {
            "uitap", "tap", "click", "uiclick", "tapnode" -> parseTap(argsRaw)
            "uiswipe", "swipe", "swipenode" -> parseSwipe(argsRaw)
            "inputtext", "input", "type", "typesearchtext" -> parseInput(argsRaw)
            "back", "go_back", "press_back" -> MacroAction.Back
            "home", "go_home", "press_home" -> MacroAction.Home
            else -> null
        }
    }

    private fun parseTap(args: String): MacroAction? {
        val nums = splitNumbers(args) ?: return null
        if (nums.size < 2) return null
        return MacroAction.Tap(nums[0], nums[1])
    }

    private fun parseSwipe(args: String): MacroAction? {
        val nums = splitNumbers(args) ?: return null
        if (nums.size < 4) return null
        val duration = if (nums.size >= 5) nums[4].toLong() else 300L
        return MacroAction.Swipe(nums[0], nums[1], nums[2], nums[3], duration)
    }

    private fun parseInput(args: String): MacroAction? {
        var text = args.trim()
        if (text.isEmpty()) return null
        // 引号包裹剥离（宿主工具参数的三种历史形态）
        if ((text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) ||
            (text.startsWith("'") && text.endsWith("'") && text.length >= 2)
        ) {
            text = text.substring(1, text.length - 1)
        }
        if (text.isEmpty()) return null
        return MacroAction.Input(text)
    }

    /** 逗号切分数值（容忍空格与尾逗号；含非数字段返回 null）。 */
    private fun splitNumbers(args: String): List<Int>? {
        val parts = args.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return try {
            parts.map { it.toInt() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
