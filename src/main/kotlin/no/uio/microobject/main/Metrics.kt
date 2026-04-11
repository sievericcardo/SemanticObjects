package no.uio.microobject.main

import java.util.concurrent.atomic.AtomicLong

/**
 * Accumulated performance metrics for a SMOL interpreter run.
 *
 * All counters are updated from a single thread (the interpreter loop) so
 * plain [AtomicLong]s are used both for atomicity and for visibility across
 * threads (e.g. if a caller asks for counts while the interpreter is running).
 */
class Metrics {
    /** Number of times the full Jena model (knowledge graph) was built for the heap. */
    val kgConstructionCount = AtomicLong(0)
    /** Accumulated wall-clock nanoseconds spent constructing the knowledge graph. */
    val kgConstructionNs = AtomicLong(0)

    /** Number of times [HeapGraph.graphBaseFind] was invoked to lift heap triples. */
    val heapLiftCount = AtomicLong(0)
    /** Accumulated wall-clock nanoseconds spent inside [HeapGraph.graphBaseFind]. */
    val heapLiftNs = AtomicLong(0)

    /** Number of times a SPARQL/OWL query was executed. */
    val queryCount = AtomicLong(0)
    /** Accumulated wall-clock nanoseconds spent executing queries. */
    val queryNs = AtomicLong(0)

    /** Number of times query answers were reified (turned back into heap objects). */
    val reificationCount = AtomicLong(0)
    /** Accumulated wall-clock nanoseconds spent on reification. */
    val reificationNs = AtomicLong(0)

    fun recordKgConstruction(ns: Long) {
        kgConstructionCount.incrementAndGet()
        kgConstructionNs.addAndGet(ns)
    }

    fun recordHeapLift(ns: Long) {
        heapLiftCount.incrementAndGet()
        heapLiftNs.addAndGet(ns)
    }

    fun recordQuery(ns: Long) {
        queryCount.incrementAndGet()
        queryNs.addAndGet(ns)
    }

    fun recordReification(ns: Long) {
        reificationCount.incrementAndGet()
        reificationNs.addAndGet(ns)
    }

    fun print() {
        println("=== Runtime Metrics ===")
        println(
            "KG heap construction : count=${kgConstructionCount.get()}" +
                    ", total=${formatNs(kgConstructionNs.get())}"
        )
        println(
            "Heap lifting         : count=${heapLiftCount.get()}" +
                    ", total=${formatNs(heapLiftNs.get())}"
        )
        println(
            "Queries              : count=${queryCount.get()}" +
                    ", total=${formatNs(queryNs.get())}"
        )
        println(
            "Reification          : count=${reificationCount.get()}" +
                    ", total=${formatNs(reificationNs.get())}"
        )
    }

    private fun formatNs(ns: Long): String = when {
        ns < 1_000L              -> "${ns}ns"
        ns < 1_000_000L          -> "${ns / 1_000}µs"
        ns < 1_000_000_000L      -> "${ns / 1_000_000}ms"
        else                     -> String.format("%.3fs", ns / 1_000_000_000.0)
    }
}
