package jetbrains.exodus.entitystore.orientdb.iterate

import jetbrains.exodus.entitystore.StoreTransaction
import jetbrains.exodus.entitystore.orientdb.OQueryEntityIterable
import jetbrains.exodus.entitystore.orientdb.query.OQueryFunctions
import jetbrains.exodus.entitystore.orientdb.query.OSelect

class ODistinctEntityIterable(
    txn: StoreTransaction?,
    private val source: OQueryEntityIterable,
) : OQueryEntityIterableBase(txn) {

    override fun query(): OSelect {
        return OQueryFunctions.distinct(source.query())
    }
}