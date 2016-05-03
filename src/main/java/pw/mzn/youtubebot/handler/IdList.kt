package pw.mzn.youtubebot.handler

import java.util.*

class IdList<T> {
    val map = HashMap<Int, T>()
    var nextId = 0

    fun get(id: Int): T? {
        return map[id]
    }

    fun add(obj: T): Int {
        map.put(nextId, obj)
        return findNextId()
    }

    fun remove(obj: T): Int {
        var entry = map.entries.filter { e -> e.value!!.equals(obj) }.firstOrNull() ?: return -1 // -1 if no entry

        map.remove(entry.key)
        findNextId()
        return entry.key
    }

    private fun findNextId(): Int {
        var old = nextId

        for (i in 0..map.size) {
            if (!map.containsKey(i)) {
                nextId = i
                return old
            }
        }

        nextId = map.size
        return old
    }
}