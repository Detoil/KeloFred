package com.kelofred.keloray

import java.util.TreeMap

/** Order-insensitive, number-type-insensitive view of a JSON-ish value for assertions. */
fun canon(v: Any?): Any? = when (v) {
    is Map<*, *> -> TreeMap<String, Any?>().apply { v.forEach { (k, x) -> put(k.toString(), canon(x)) } }
    is List<*> -> v.map { canon(it) }
    is Boolean -> v
    is Number -> v.toDouble()
    else -> v
}
