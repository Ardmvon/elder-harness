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

}
