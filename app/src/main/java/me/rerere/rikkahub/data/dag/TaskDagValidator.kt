package me.rerere.rikkahub.data.dag

data class DagValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val cycles: List<List<String>> = emptyList(),
    val topologicalOrder: List<String> = emptyList(),
    val executionLevels: List<List<String>> = emptyList(),
)

/**
 * Validates DAG structure: checks for cycles, missing dependencies, and computes execution levels.
 */
object TaskDagValidator {

    fun validate(graph: TaskGraph): DagValidationResult {
        val errors = mutableListOf<String>()

        if (graph.nodes.isEmpty()) {
            return DagValidationResult(isValid = true, errors = emptyList())
        }

        // 1. Check for missing dependencies
        for ((nodeId, node) in graph.nodes) {
            for (prereq in node.needs) {
                if (prereq !in graph.nodes) {
                    errors.add("Node '$nodeId' depends on non-existent prerequisite '$prereq'")
                }
            }
        }

        if (errors.isNotEmpty()) {
            return DagValidationResult(isValid = false, errors = errors)
        }

        // 2. Kahn's Algorithm for cycle detection and topological sorting
        val inDegree = mutableMapOf<String, Int>()
        val dependents = mutableMapOf<String, MutableList<String>>()

        for (id in graph.nodes.keys) {
            inDegree[id] = 0
            dependents[id] = mutableListOf()
        }

        for (edge in graph.edges) {
            inDegree[edge.to] = (inDegree[edge.to] ?: 0) + 1
            dependents[edge.from]?.add(edge.to)
        }

        val queue = ArrayDeque<String>()
        val levels = mutableMapOf<String, Int>()

        for ((id, degree) in inDegree) {
            if (degree == 0) {
                queue.add(id)
                levels[id] = 0
            }
        }

        val sortedOrder = mutableListOf<String>()

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            sortedOrder.add(current)
            val currentLevel = levels[current] ?: 0

            for (dep in dependents[current].orEmpty()) {
                val newDegree = (inDegree[dep] ?: 1) - 1
                inDegree[dep] = newDegree
                val depLevel = levels[dep] ?: 0
                levels[dep] = maxOf(depLevel, currentLevel + 1)
                if (newDegree == 0) {
                    queue.add(dep)
                }
            }
        }

        // 3. Cycle Detection
        if (sortedOrder.size != graph.nodes.size) {
            val cycleNodes = graph.nodes.keys.filter { it !in sortedOrder }
            errors.add("Cycle detected in graph among nodes: $cycleNodes")
            return DagValidationResult(
                isValid = false,
                errors = errors,
                cycles = listOf(cycleNodes),
            )
        }

        // Group into execution levels
        val levelGroups = levels.entries
            .groupBy({ it.value }, { it.key })
            .toSortedMap()
            .values
            .map { it.sorted() }

        return DagValidationResult(
            isValid = true,
            errors = emptyList(),
            topologicalOrder = sortedOrder,
            executionLevels = levelGroups,
        )
    }
}
