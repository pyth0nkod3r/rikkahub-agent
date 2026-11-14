package me.rerere.rikkahub.data.cartography

import java.io.File

data class CartographyReport(
    val projectType: String,
    val modules: List<String>,
    val techStack: List<String>,
    val fileCountsByExt: Map<String, Int>,
    val topLevelDirs: List<String>,
    val gitBranch: String?,
    val generatedMarkdown: String,
)

/**
 * Fast local structural cartographer for workspaces.
 *
 * Scans workspace structure, Gradle/package declarations, architecture patterns,
 * and renders a definitive project reference (.rikkahub/project.md).
 */
class ProjectCartographer {

    companion object {
        private val SKIP_DIRS = setOf(
            ".git", ".gradle", "build", "node_modules", ".agents",
            "dist", "target", "out", ".idea", ".vscode", ".husky"
        )
        const val PROJECT_DOC_RELATIVE_PATH = ".rikkahub/project.md"
    }

    fun survey(rootDir: File, hint: String? = null): CartographyReport {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            val emptyMd = renderDoc("Empty Workspace", emptyList(), emptyList(), emptyMap(), emptyList(), null, hint)
            return CartographyReport("Unknown", emptyList(), emptyList(), emptyMap(), emptyList(), null, emptyMd)
        }

        val topLevelEntries = rootDir.listFiles().orEmpty()
        val topLevelDirs = topLevelEntries.filter { it.isDirectory && it.name !in SKIP_DIRS }.map { it.name }.sorted()

        // 1. Detect modules
        val modules = detectModules(rootDir)

        // 2. Count extensions and sample tree
        val extCounts = mutableMapOf<String, Int>()
        walkDirectory(rootDir, extCounts, maxFiles = 2000)

        // 3. Detect Tech Stack and Project Type
        val techStack = detectTechStack(rootDir, extCounts)
        val projectType = detectProjectType(rootDir, techStack)

        // 4. Git branch
        val gitBranch = detectGitBranch(rootDir)

        // 5. Render markdown
        val markdown = renderDoc(
            projectType = projectType,
            modules = modules,
            techStack = techStack,
            fileCounts = extCounts,
            topLevelDirs = topLevelDirs,
            gitBranch = gitBranch,
            hint = hint
        )

        return CartographyReport(
            projectType = projectType,
            modules = modules,
            techStack = techStack,
            fileCountsByExt = extCounts,
            topLevelDirs = topLevelDirs,
            gitBranch = gitBranch,
            generatedMarkdown = markdown
        )
    }

    private fun detectModules(rootDir: File): List<String> {
        val settingsKts = File(rootDir, "settings.gradle.kts")
        val settingsGroovy = File(rootDir, "settings.gradle")
        val settingsFile = if (settingsKts.exists()) settingsKts else if (settingsGroovy.exists()) settingsGroovy else null

        if (settingsFile != null && settingsFile.exists()) {
            val content = runCatching { settingsFile.readText() }.getOrDefault("")
            val regex = Regex("""include\s*\(\s*["']([^"']+)["']\s*\)|include\s*["']([^"']+)["']""")
            val matches = regex.findAll(content)
                .mapNotNull { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                .map { it.trimStart(':') }
                .toList()
            if (matches.isNotEmpty()) return matches
        }

        // Fallback: check directories that contain build.gradle(.kts)
        return rootDir.listFiles()?.filter { sub ->
            sub.isDirectory && (File(sub, "build.gradle.kts").exists() || File(sub, "build.gradle").exists())
        }?.map { it.name }?.sorted() ?: emptyList()
    }

    private fun detectTechStack(rootDir: File, extCounts: Map<String, Int>): List<String> {
        val stack = mutableListOf<String>()

        val hasGradle = File(rootDir, "build.gradle.kts").exists() || File(rootDir, "build.gradle").exists()
        val hasPackageJson = File(rootDir, "package.json").exists()
        val hasPyproject = File(rootDir, "pyproject.toml").exists() || File(rootDir, "requirements.txt").exists()

        if (hasGradle) stack.add("Gradle")
        if (hasPackageJson) stack.add("Node.js")
        if (hasPyproject) stack.add("Python")

        // Inspect gradle files for Android / Compose / Room / Koin
        val allGradleFiles = rootDir.walkTopDown()
            .maxDepth(3)
            .filter { it.name == "build.gradle.kts" || it.name == "build.gradle" }
            .take(10)
            .toList()

        val gradleText = allGradleFiles.joinToString("\n") { f ->
            runCatching { f.readText() }.getOrDefault("")
        }

        if (gradleText.contains("com.android") || gradleText.contains("android {")) {
            stack.add("Android SDK")
        }
        if (gradleText.contains("compose") || gradleText.contains("androidx.compose")) {
            stack.add("Jetpack Compose")
        }
        if (gradleText.contains("androidx.room")) {
            stack.add("Room Database")
        }
        if (gradleText.contains("koin") || gradleText.contains("org.koin")) {
            stack.add("Koin DI")
        }
        if (gradleText.contains("kotlinx.coroutines")) {
            stack.add("Kotlin Coroutines")
        }
        if (gradleText.contains("okhttp") || gradleText.contains("retrofit") || gradleText.contains("ktor")) {
            stack.add("Networking (OkHttp/Retrofit/Ktor)")
        }

        if (extCounts.containsKey("kt")) stack.add("Kotlin")
        if (extCounts.containsKey("java")) stack.add("Java")
        if (extCounts.containsKey("ts") || extCounts.containsKey("tsx")) stack.add("TypeScript")
        if (extCounts.containsKey("py")) stack.add("Python")
        if (extCounts.containsKey("rs")) stack.add("Rust")

        return stack.distinct()
    }

    private fun detectProjectType(rootDir: File, techStack: List<String>): String {
        return when {
            techStack.contains("Android SDK") -> "Android Application"
            techStack.contains("Gradle") && techStack.contains("Kotlin") -> "Kotlin / JVM Project"
            techStack.contains("Node.js") -> "Node.js / Web Application"
            techStack.contains("Python") -> "Python Application"
            techStack.contains("Rust") -> "Rust Application"
            File(rootDir, "Cargo.toml").exists() -> "Rust Project"
            File(rootDir, "go.mod").exists() -> "Go Project"
            else -> "General Software Workspace"
        }
    }

    private fun detectGitBranch(rootDir: File): String? {
        val headFile = File(rootDir, ".git/HEAD")
        if (!headFile.exists()) return null
        val line = runCatching { headFile.readText().trim() }.getOrNull() ?: return null
        return if (line.startsWith("ref: refs/heads/")) {
            line.removePrefix("ref: refs/heads/")
        } else {
            line.take(8)
        }
    }

    private fun walkDirectory(dir: File, extCounts: MutableMap<String, Int>, maxFiles: Int) {
        var counted = 0
        dir.walkTopDown()
            .onEnter { it.name !in SKIP_DIRS }
            .forEach { file ->
                if (file.isFile) {
                    val ext = file.extension.lowercase()
                    if (ext.isNotEmpty()) {
                        extCounts[ext] = (extCounts[ext] ?: 0) + 1
                    }
                    counted++
                    if (counted >= maxFiles) return
                }
            }
    }

    private fun renderDoc(
        projectType: String,
        modules: List<String>,
        techStack: List<String>,
        fileCounts: Map<String, Int>,
        topLevelDirs: List<String>,
        gitBranch: String?,
        hint: String?
    ): String = buildString {
        appendLine("# Project Cartography & Architecture Reference")
        appendLine()
        appendLine("> Auto-generated by RikkaHub AgentX ProjectCartographer. Update via `update_project_doc` tool.")
        appendLine()
        appendLine("## 1. Overview")
        appendLine("- **Project Type**: $projectType")
        if (gitBranch != null) {
            appendLine("- **Git Branch**: `$gitBranch`")
        }
        if (!hint.isNullOrBlank()) {
            appendLine("- **Focus / Purpose**: $hint")
        }
        appendLine("- **Tech Stack**: ${if (techStack.isEmpty()) "Standard" else techStack.joinToString(", ")}")
        appendLine()

        if (modules.isNotEmpty()) {
            appendLine("## 2. Modules & Components")
            modules.forEach { mod ->
                appendLine("- `:$mod`")
            }
            appendLine()
        }

        if (topLevelDirs.isNotEmpty()) {
            appendLine("## 3. Directory Structure")
            topLevelDirs.forEach { d ->
                appendLine("- `/$d/`")
            }
            appendLine()
        }

        if (fileCounts.isNotEmpty()) {
            appendLine("## 4. File Inventory")
            fileCounts.entries
                .sortedByDescending { it.value }
                .take(10)
                .forEach { (ext, count) ->
                    appendLine("- `.$ext`: $count files")
                }
            appendLine()
        }

        appendLine("## 5. Architectural Guidance & Constraints")
        appendLine("- Enforce clean separation between core data/business logic and presentation surfaces.")
        appendLine("- Keep logic flat: guard clauses and early returns; avoid nesting > 3 levels.")
        appendLine("- Fault isolation: I/O or background failures must degrade gracefully rather than crashing turns.")
    }
}
