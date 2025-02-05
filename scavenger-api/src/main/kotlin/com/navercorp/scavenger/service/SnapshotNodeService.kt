package com.navercorp.scavenger.service

import com.navercorp.scavenger.entity.MethodInvocation
import com.navercorp.scavenger.entity.Snapshot
import com.navercorp.scavenger.entity.SnapshotNode
import com.navercorp.scavenger.repository.SnapshotNodeDao
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.AntPathMatcher

@Service
class SnapshotNodeService(
    private val snapshotNodeDao: SnapshotNodeDao
) {
    fun readSnapshotNode(customerId: Long, snapshotId: Long, parent: String): List<SnapshotNode> {
        return snapshotNodeDao.findAllByCustomerIdAndSnapshotIdAndParent(customerId, snapshotId, parent)
    }

    @Transactional
    fun createAndSaveSnapshotNodes(snapshot: Snapshot, methodInvocations: List<MethodInvocation>) {
        val filteredMethodInvocations = filterByPackagesAntMatch(methodInvocations, snapshot.packages.trim())

        val root = Node("", Node.Type.ROOT)
        filteredMethodInvocations.forEach {
            updateCountGraph(
                current = root,
                elements = splitSignature(it),
                isUsed = it.invokedAtMillis > 0 && it.invokedAtMillis >= snapshot.filterInvokedAtMillis,
                invokedAtMillis = it.invokedAtMillis
            )
        }

        serializeGraphAddToNodes(
            currentNode = root,
            parentNode = root,
            snapshotId = checkNotNull(snapshot.id) { "node 저장은 Snaptshot 저장 이후에 일어나므로 id는 null일 수 없음" },
            customerId = checkNotNull(snapshot.customerId) { "customer ID는 null일 수 없음" }
        ).chunked(BATCH_CHUNK_SIZE).forEach {
            snapshotNodeDao.saveAllSnapshotNodes(it)
        }
    }

    fun deleteSnapshotNode(customerId: Long, snapshotId: Long) {
        snapshotNodeDao.deleteAllByCustomerIdAndSnapshotId(customerId, snapshotId)
    }

    fun getSnapshotNodesBySignatureContaining(
        customerId: Long,
        snapshotId: Long,
        signature: String,
        snapshotNodeId: Long? = null
    ): List<SnapshotNode> {
        return snapshotNodeDao.selectAllBySignatureContaining(customerId, snapshotId, signature, snapshotNodeId)
    }

    private fun filterByPackagesAntMatch(
        methodInvocations: List<MethodInvocation>,
        packages: String
    ): List<MethodInvocation> {
        if (packages.isEmpty()) {
            return methodInvocations
        }
        val antPathMatcher = AntPathMatcher(".")
        val patterns = packages.replace(" ", "").split(",")
        return methodInvocations
            .filter { methodInvocation: MethodInvocation ->
                patterns.any { pattern: String ->
                    antPathMatcher.match(
                        pattern,
                        methodInvocation.signature.replace("$", ".")
                    )
                }
            }
    }

    private fun updateCountGraph(
        current: Node,
        elements: List<String>,
        isUsed: Boolean,
        invokedAtMillis: Long
    ) {
        var node = current
        elements.forEach {
            updateCount(node, isUsed, invokedAtMillis)
            if (it !in node.signatureChildMap) {
                val delimiter = if (node.type == Node.Type.CLASS && !it.contains("(")) "$" else "."
                val nextElementName = if (node.signature.isBlank()) it else "${node.signature}$delimiter$it"
                node.signatureChildMap[it] = Node(
                    signature = nextElementName,
                    type = getType(node, nextElementName)
                )
            }
            node = node.signatureChildMap.getValue(it)
        }
        updateCount(node, isUsed, invokedAtMillis)
    }

    private fun updateCount(
        node: Node,
        isUsed: Boolean,
        invokedAtMillis: Long
    ) {
        if (isUsed) {
            node.usedCount += 1
            node.lastInvokedAtMillis = maxOf(node.lastInvokedAtMillis ?: Long.MIN_VALUE, invokedAtMillis)
        } else {
            node.unusedCount += 1
        }
    }

    /*
    * "a.b.c.d(e, f)" to ["a", "b", "c", "d(e, f)"]
    * "a.b.c.D(e, f)" to ["a", "b", "c", "D", "D(e, f)"]
    */
    private fun splitSignature(methodInvocation: MethodInvocation): List<String> {
        val (nameOnlySignature, arguments) = methodInvocation.signature.split("(", limit = 2)

        val elements = nameOnlySignature.split(*DELIMITERS)
            .filterNot { it == "" }
            .toMutableList()

        if (isConstructor(methodInvocation)) {
            elements.add(elements.last())
        }

        elements[elements.size - 1] = "${elements.last()}($arguments" + if (arguments.last() != ')') ")" else ""
        return elements
    }

    private fun isConstructor(methodInvocation: MethodInvocation): Boolean {
        return methodInvocation.methodName == "<init>"
    }

    private fun getType(parent: Node, signature: String): Node.Type {
        val isParentTypeClass = parent.type == Node.Type.CLASS
        return if (isParentTypeClass && signature.contains("(")) {
            Node.Type.METHOD
        } else {
            val split = signature.split(*DELIMITERS)
            val firstOfLast = split.last()[0]
            if (firstOfLast.uppercaseChar() == firstOfLast || isParentTypeClass /*Inner Class*/) {
                Node.Type.CLASS
            } else {
                Node.Type.PACKAGE
            }
        }
    }

    private fun serializeGraphAddToNodes(
        currentNode: Node,
        parentNode: Node,
        snapshotId: Long,
        customerId: Long
    ): List<SnapshotNode> {
        if (currentNode.type != Node.Type.ROOT && currentNode.signatureChildMap.size == 1) {
            val child = currentNode.signatureChildMap.values.first()
            if (child.type == Node.Type.PACKAGE) {
                return serializeGraphAddToNodes(child, parentNode, snapshotId, customerId)
            }
        }

        val descendants = currentNode.signatureChildMap.values.flatMap {
            serializeGraphAddToNodes(it, currentNode, snapshotId, customerId)
        }

        if (currentNode.type == Node.Type.ROOT) {
            return descendants
        }

        return descendants + SnapshotNode(
            snapshotId = snapshotId,
            signature = currentNode.signature,
            lastInvokedAtMillis = currentNode.lastInvokedAtMillis,
            usedCount = currentNode.usedCount,
            unusedCount = currentNode.unusedCount,
            parent = parentNode.signature,
            type = currentNode.type.toString(),
            customerId = customerId
        )
    }

    data class Node(
        val signature: String,
        val type: Type,
        var usedCount: Int = 0,
        var unusedCount: Int = 0,
        var lastInvokedAtMillis: Long? = null,
        val signatureChildMap: MutableMap<String, Node> = hashMapOf()
    ) {
        enum class Type {
            ROOT, CLASS, METHOD, PACKAGE
        }
    }

    companion object {
        private const val BATCH_CHUNK_SIZE = 1000
        private val DELIMITERS = arrayOf(".", "$")
    }
}
