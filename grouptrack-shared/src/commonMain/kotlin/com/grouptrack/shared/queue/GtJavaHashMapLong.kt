package com.grouptrack.shared.queue

/**
 * STEP16B-2026-11-10: java.util.HashMap<Long, V>'s ITERATION ORDER, reproduced. 2.7e's VectorTiles.queueSquaresNamed joins z12 squares
 * into boxes through two HashMap<Long, Box> ("open" / "next"); the order it walks them in IS the order the boxes are queued in (the
 * job order in download_queue.json). The iPad's HashMap walks in another order, so the shared code uses this.
 * JDK 8+ HashMap, for Long keys: hash = h ^ (h >>> 16) with h = Long.hashCode = (int)(v ^ (v >>> 32)); table 16 on the first put,
 * doubled when size > 0.75 * capacity; a new key goes at the TAIL of its bin; a resize splits each bin into lo / hi keeping order;
 * iteration = bins 0..cap-1, each bin head to tail. A bin reaching 9 nodes: table < 64 -> resize (reproduced); table >= 64 -> Java
 * makes it a tree bin, whose order this does NOT reproduce -- [treeified] counts those (the cert reports it; never seen on real data:
 * it needs 49+ runs of squares in one z12 row).
 */
internal class GtJavaHashMapLong<V : Any> {
    private class Node<V>(val hash: Int, val key: Long, var value: V, var next: Node<V>?)

    private var table: Array<Node<V>?> = arrayOfNulls(0)
    private var threshold = 0
    var size = 0
        private set
    var treeified = 0
        private set

    private fun hashOf(k: Long): Int { val h = (k xor (k ushr 32)).toInt(); return h xor (h ushr 16) }

    operator fun get(k: Long): V? {
        if (table.isEmpty()) return null
        val h = hashOf(k)
        var e = table[h and (table.size - 1)]
        while (e != null) { if (e.hash == h && e.key == k) return e.value; e = e.next }
        return null
    }

    operator fun set(k: Long, v: V) = put(k, v)

    fun put(k: Long, v: V) {
        if (table.isEmpty()) resize()
        val h = hashOf(k)
        val i = h and (table.size - 1)
        val first = table[i]
        if (first == null) {
            table[i] = Node(h, k, v, null)
        } else {
            var p: Node<V> = first; var bin = 0
            while (true) {
                if (p.hash == h && p.key == k) { p.value = v; return }   // existing key: value replaced, no size change, no resize
                val e = p.next
                if (e == null) {
                    p.next = Node(h, k, v, null)
                    if (bin >= 7) { if (table.size < 64) resize() else treeified++ }   // TREEIFY_THRESHOLD - 1; MIN_TREEIFY_CAPACITY
                    break
                }
                p = e; bin++
            }
        }
        if (++size > threshold) resize()
    }

    private fun resize() {
        val old = table; val oldCap = old.size
        val newCap: Int
        if (oldCap == 0) { newCap = 16; threshold = 12 } else { newCap = oldCap shl 1; threshold = threshold shl 1 }
        val t = arrayOfNulls<Node<V>>(newCap)
        for (j in 0 until oldCap) {
            val e0 = old[j] ?: continue
            if (e0.next == null) { t[e0.hash and (newCap - 1)] = e0; continue }
            var loH: Node<V>? = null; var loT: Node<V>? = null; var hiH: Node<V>? = null; var hiT: Node<V>? = null
            var e: Node<V>? = e0
            while (e != null) {
                val nx = e.next
                if ((e.hash and oldCap) == 0) { if (loT == null) loH = e else loT.next = e; loT = e }
                else { if (hiT == null) hiH = e else hiT.next = e; hiT = e }
                e = nx
            }
            if (loT != null) { loT.next = null; t[j] = loH }
            if (hiT != null) { hiT.next = null; t[j + oldCap] = hiH }
        }
        table = t   // STEP16C-2026-11-11: the needless cast removed (16b's one iOS warning; arrayOfNulls already gives Array<Node<V>?>)
    }

    /** (key, value) in HashMap's iteration order. */
    fun entries(): List<Pair<Long, V>> {
        val out = ArrayList<Pair<Long, V>>(size)
        for (b in table) { var e = b; while (e != null) { out.add(e.key to e.value); e = e.next } }
        return out
    }

    fun values(): List<V> = entries().map { it.second }
}
