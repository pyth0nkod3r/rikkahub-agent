package me.rerere.rikkahub.data.dag

data class TaskEdge(
    val from: String,
    val to: String,
)

/**
 * Immutable Directed Acyclic Graph (DAG) representation.
 * Edges are derived strictly from nodes' `needs` declarations to avoid state drift.
 */
data class TaskGraph(
    val nodes: Map<String, TaskNode> = emptyMap(),
) {
    /**
     * Derived edges: if node B has node A in needs, an edge exists from A to B.
     */
    val edges: Set<TaskEdge> by lazy {
        nodes.values.flatMap { node ->
            node.needs.map { prereq -> TaskEdge(from = prereq, to = node.id) }
        }.toSet()
    }

    fun getNode(id: String): TaskNode? = nodes[id]

    fun withNode(node: TaskNode): TaskGraph =
        copy(nodes = nodes + (node.id to node))

    fun withoutNode(id: String): TaskGraph {
        val filtered = nodes.filterKeys { it != id }.mapValues { (_, node) ->
            if (id in node.needs) node.copy(needs = node.needs - id) else node
        }
        return copy(nodes = filtered)
    }

    fun withEdge(from: String, to: String): TaskGraph {
        val target = nodes[to] ?: return this
        val updated = target.copy(needs = target.needs + from)
        return copy(nodes = nodes + (to to updated))
    }

    fun withoutEdge(from: String, to: String): TaskGraph {
        val target = nodes[to] ?: return this
        val updated = target.copy(needs = target.needs - from)
        return copy(nodes = nodes + (to to updated))
    }

    fun findRoots(): List<TaskNode> =
        nodes.values.filter { it.needs.isEmpty() }

    fun findLeaves(): List<TaskNode> {
        val parentIds = edges.map { it.from }.toSet()
        return nodes.values.filter { it.id !in parentIds }
    }

    fun isAllTerminal(): Boolean =
        nodes.values.all { it.status.isTerminal }

    fun isSuccessful(): Boolean =
        nodes.isNotEmpty() && nodes.values.all { it.status == TaskNodeStatus.COMPLETED || it.status == TaskNodeStatus.SKIPPED }
}
