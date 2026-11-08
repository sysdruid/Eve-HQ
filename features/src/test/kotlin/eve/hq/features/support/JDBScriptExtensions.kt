// Copied from jdbscript examples/12-kotlin-dsl (Apache-2.0); these extensions are not part of the jdbscript jar.
package eve.hq.features.support

import org.jdbscript.IDBSchema
import org.jdbscript.IJDBEngine

/**
 * Idiomatic Kotlin extensions for [IJDBEngine].
 *
 * Provides receiver lambda blocks (`T.() -> Unit`), turning `db -> db.table()`
 * into direct, type-safe DSL calls: `insert { table() ... }`.
 */
inline fun <T : IDBSchema> IJDBEngine<T>.insert(crossinline block: T.() -> Unit) {
    insertDB { db -> db.block() }
}

/**
 * In-place row modification using receiver lambda.
 */
inline fun <T : IDBSchema> IJDBEngine<T>.update(crossinline block: T.() -> Unit) {
    updateDB { db -> db.block() }
}

/**
 * Complete database reset and re-population using receiver lambda.
 */
inline fun <T : IDBSchema> IJDBEngine<T>.reset(crossinline block: T.() -> Unit) {
    resetDB { db -> db.block() }
}

/**
 * Reified helper to switch schema into a domain-specific sub-interface DSL without
 * passing Java `.class` tokens explicitly: `engine.asDSL<ISaaSDomainDSL>()`.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified D : IDBSchema> IJDBEngine<*>.asDSL(): IJDBEngine<D> {
    return (this as IJDBEngine<IDBSchema>).`as`(D::class.java) as IJDBEngine<D>
}

/**
 * Convenient one-liner to insert test data using a domain-specific sub-interface DSL.
 */
inline fun <reified D : IDBSchema> IJDBEngine<*>.insertDSL(crossinline block: D.() -> Unit) {
    asDSL<D>().insert(block)
}

/**
 * Convenient one-liner to reset the database and seed using a domain-specific sub-interface DSL.
 */
inline fun <reified D : IDBSchema> IJDBEngine<*>.resetDSL(crossinline block: D.() -> Unit) {
    asDSL<D>().reset(block)
}

/**
 * Convenient one-liner to update test data using a domain-specific sub-interface DSL.
 */
inline fun <reified D : IDBSchema> IJDBEngine<*>.updateDSL(crossinline block: D.() -> Unit) {
    asDSL<D>().update(block)
}
