package com.yinling.hotline

import android.content.Context
import java.io.File

/**
 * Filesystem home for skills the model wrote after a verified run.
 *
 * Deliberately small and text-only: a generated skill is one Markdown file with a tiny key: value
 * front matter. Screenshots and coordinates are never stored. The active directory is what
 * [SkillCatalog] exposes to the model; candidate and retired files are only visible in settings.
 */
class SkillStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("hotline", 0)

    private val root: File get() = File(context.filesDir, "skills").apply { mkdirs() }
    private val candidateDir: File get() = File(root, "candidate").apply { mkdirs() }
    private val activeDir: File get() = File(root, "active").apply { mkdirs() }
    private val retiredDir: File get() = File(root, "retired").apply { mkdirs() }

    fun activeSkills(): List<Skill> = read(activeDir, status = "active")

    fun candidateSkills(): List<Skill> = read(candidateDir, status = "candidate")

    fun retiredSkills(): List<Skill> = read(retiredDir, status = "retired")

    /** Candidate -> active. The next task sees it through [SkillCatalog]. */
    fun promote(name: String): Boolean = move(candidateFile(name), activeFile(name))

    /** Active -> retired. The next task stops seeing it, without deleting the evidence file. */
    fun retire(name: String): Boolean = move(activeFile(name), retiredFile(name))

    fun deleteCandidate(name: String): Boolean = candidateFile(name).delete()

    /**
     * Writes a generated skill as a candidate. A name collision gets a numeric suffix instead of
     * overwriting a file the family may already be reviewing.
     */
    fun saveCandidate(skill: Skill): Boolean {
        val name = uniqueName(skill.name)
        val stored = skill.copy(name = name, status = "candidate", source = "learned")
        val file = candidateFile(name)
        return runCatching {
            file.writeText(renderMarkdown(stored))
            true
        }.getOrDefault(false)
    }

    /**
     * One demo candidate so the settings screen is not empty before the writer exists. The flag keeps
     * it from reappearing after the family adopts or deletes it.
     */
    fun seedDemoCandidateIfNeeded() {
        if (prefs.getBoolean("skills_seeded", false)) return
        val file = candidateFile("meituan_waimai_order")
        if (!file.exists()) {
            runCatching { file.writeText(DEMO_CANDIDATE) }
                .onFailure { LoopLog.event("[skill] 演示候选写入失败：${it.message}") }
        }
        prefs.edit().putBoolean("skills_seeded", true).apply()
    }

    private fun read(dir: File, status: String): List<Skill> =
        dir.listFiles { file -> file.isFile && file.extension == "md" }
            .orEmpty()
            .mapNotNull { file -> parse(file, status) }
            .sortedBy { it.name }

    private fun parse(file: File, status: String): Skill? = runCatching {
        val lines = file.readText().lines()
        if (lines.firstOrNull()?.trim() != "---") return@runCatching null
        val meta = mutableMapOf<String, String>()
        var bodyStart = -1
        for (index in 1 until lines.size) {
            val line = lines[index].trim()
            if (line == "---") {
                bodyStart = index + 1
                break
            }
            val colon = line.indexOf(':')
            if (colon > 0) meta[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        if (bodyStart < 0 || bodyStart >= lines.size) return@runCatching null
        val body = lines.drop(bodyStart).joinToString("\n").trim()
        if (body.isBlank()) return@runCatching null
        val name = meta["name"]?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        Skill(
            name = name,
            description = meta["description"] ?: meta["title"] ?: name,
            apps = meta["apps"].orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet(),
            hint = meta["hint"].orEmpty(),
            body = body,
            version = meta["version"]?.toIntOrNull() ?: 1,
            status = status,
            source = meta["source"] ?: "learned",
        )
    }.getOrNull()

    private fun renderMarkdown(skill: Skill): String = buildString {
        appendLine("---")
        appendLine("name: ${oneLine(skill.name)}")
        appendLine("description: ${oneLine(skill.description)}")
        appendLine("version: ${skill.version}")
        if (skill.apps.isNotEmpty()) appendLine("apps: ${skill.apps.joinToString(",") { oneLine(it) }}")
        if (skill.hint.isNotBlank()) appendLine("hint: ${oneLine(skill.hint)}")
        appendLine("source: ${oneLine(skill.source)}")
        appendLine("---")
        appendLine()
        appendLine(skill.body.trim())
    }

    private fun oneLine(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim()

    private fun uniqueName(wanted: String): String {
        val base = safeName(wanted)
        if (!candidateFile(base).exists() && !activeFile(base).exists() && !retiredFile(base).exists()) {
            return base
        }
        var index = 2
        while (true) {
            val candidate = "${base}_$index"
            if (!candidateFile(candidate).exists() &&
                !activeFile(candidate).exists() &&
                !retiredFile(candidate).exists()
            ) {
                return candidate
            }
            index++
        }
    }

    private fun move(source: File, destination: File): Boolean {
        if (!source.exists()) return false
        destination.parentFile?.mkdirs()
        if (source.renameTo(destination)) return true
        return runCatching {
            destination.writeText(source.readText())
            source.delete()
        }.getOrDefault(false)
    }

    private fun candidateFile(name: String) = File(candidateDir, "${safeName(name)}.md")

    private fun activeFile(name: String) = File(activeDir, "${safeName(name)}.md")

    private fun retiredFile(name: String) = File(retiredDir, "${safeName(name)}.md")

    private fun safeName(name: String): String =
        name.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "skill" }

    private companion object {
        val DEMO_CANDIDATE = """
            ---
            name: meituan_waimai_order
            description: 美团点外卖流程（演示候选）
            version: 1
            apps: com.sankuai.meituan
            source: learned
            ---

            ## 适用条件

            - 当前 App：美团
            - 目标里包含“外卖 / 点餐 / 买饭”

            ## 流程

            1. 主页进入“外卖”频道
               - 动作意图：找到外卖入口并进入
               - 成功后页面：出现搜索框、定位和外卖商家列表

            2. 搜索目标商家或菜品
               - 动作意图：点搜索框，输入目标
               - 成功后页面：搜索结果列表

            3. 选择目标商家
               - 动作意图：点唯一匹配的商家
               - 成功后页面：商家详情和菜单

            4. 选择菜品与规格
               - 动作意图：按老人确认的规格选菜，加入购物车
               - 成功后页面：出现购物车或结算入口

            5. 到结算页停下
               - 必须用 ask_person
               - 只说明金额、地址、要点哪里
               - 不允许自动支付或提交订单

            ## 失败恢复

            - 找不到搜索框：先回主页，再找外卖频道入口
            - 商家页打不开：返回搜索结果重新选
            - 遇到推广弹窗：先关闭，再继续原流程
        """.trimIndent()
    }
}
