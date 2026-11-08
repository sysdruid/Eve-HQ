package eve.hq.features.support

import org.jdbscript.IDBSchema
import org.jdbscript.IJDBEngine

// The upstream Kotlin extensions cover insert/update/reset but not assertions.
inline fun <T : IDBSchema> IJDBEngine<T>.assertHas(crossinline block: T.() -> Unit) {
    assertDBHas { db -> db.block() }
}

inline fun <T : IDBSchema> IJDBEngine<T>.assertHasNot(crossinline block: T.() -> Unit) {
    assertDBHasNot { db -> db.block() }
}
