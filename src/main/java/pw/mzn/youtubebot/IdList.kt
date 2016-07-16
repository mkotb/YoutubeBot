/*
 * Copyright (c) 2016, Mazen Kotb, mazenkotb@gmail.com
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package pw.mzn.youtubebot

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