package jetbrains.exodus.entitystore.orientdb.iterate

import jetbrains.exodus.entitystore.Entity
import jetbrains.exodus.entitystore.EntityIterable
import jetbrains.exodus.entitystore.EntityIterableHandle
import jetbrains.exodus.entitystore.EntityIterator
import jetbrains.exodus.entitystore.StoreTransaction
import jetbrains.exodus.entitystore.asOQueryIterable
import jetbrains.exodus.entitystore.asOStore
import jetbrains.exodus.entitystore.asOStoreTransaction
import jetbrains.exodus.entitystore.iterate.EntityIterableBase
import jetbrains.exodus.entitystore.orientdb.OEntityIterableHandle
import jetbrains.exodus.entitystore.orientdb.OQueryEntityIterable
import jetbrains.exodus.entitystore.orientdb.OStoreTransaction
import jetbrains.exodus.entitystore.orientdb.iterate.binop.OConcatEntityIterable
import jetbrains.exodus.entitystore.orientdb.iterate.binop.OIntersectionEntityIterable
import jetbrains.exodus.entitystore.orientdb.iterate.binop.OMinusEntityIterable
import jetbrains.exodus.entitystore.orientdb.iterate.binop.OUnionEntityIterable
import jetbrains.exodus.entitystore.orientdb.iterate.link.OLinkIterableToEntityIterableFiltered
import jetbrains.exodus.entitystore.orientdb.iterate.link.OLinkSelectEntityIterable
import jetbrains.exodus.entitystore.orientdb.query.OCountSelect
import jetbrains.exodus.entitystore.orientdb.query.OSelect
import jetbrains.exodus.entitystore.util.unsupported
import java.util.concurrent.Executor

abstract class OQueryEntityIterableBase(tx: StoreTransaction?) : EntityIterableBase(tx), OQueryEntityIterable {

    private val otx: OStoreTransaction? = tx?.asOStoreTransaction()
    private val countExecutor: Executor? = tx?.store?.asOStore()?.countExecutor

    companion object {

        val EMPTY = object : OQueryEntityIterableBase(null) {

            override fun iterator(): EntityIterator {
                return OQueryEntityIterator.EMPTY
            }

            override fun query(): OSelect {
                unsupported { "Should never be called" }
            }

            override fun size() = 0L
            override fun getRoughSize() = 0L
            override fun count() = 0L
            override fun getRoughCount() = 0L
            override fun union(right: EntityIterable) = right
            override fun concat(right: EntityIterable) = right
            override fun intersect(right: EntityIterable) = this
            override fun distinct() = this
            override fun minus(right: EntityIterable) = this
            override fun selectMany(linkName: String) = this
            override fun selectManyDistinct(linkName: String) = this
            override fun selectDistinct(linkName: String) = this
        }
    }

    override fun iterator(): EntityIterator {
        if (otx == null) {
            return EMPTY.iterator()
        } else {
            val query = query()
            return OQueryEntityIterator.executeAndCreate(query, otx)
        }
    }

    override fun getIteratorImpl(txn: StoreTransaction): EntityIterator {
        unsupported { "Should never be called" }
    }

    override fun getHandleImpl(): EntityIterableHandle {
        return OEntityIterableHandle(query().sql())
    }

    override fun union(right: EntityIterable): EntityIterable {
        if (right == EMPTY) {
            return this
        }
        return OUnionEntityIterable(transaction, this, right.asOQueryIterable())
    }

    override fun intersectSavingOrder(right: EntityIterable): EntityIterable {
        if (right == EMPTY) {
            return this
        }
        return intersect(right)
    }

    override fun intersect(right: EntityIterable): EntityIterable {
        if (right == EMPTY) {
            return this
        }
        return OIntersectionEntityIterable(transaction, this, right.asOQueryIterable())
    }

    override fun concat(right: EntityIterable): EntityIterable {
        if (right == EMPTY) {
            return this
        }
        return OConcatEntityIterable(transaction, this, right.asOQueryIterable())
    }

    override fun distinct(): EntityIterable {
        return ODistinctEntityIterable(transaction, this)
    }

    override fun minus(right: EntityIterable): EntityIterable {
        if (right == EMPTY) {
            return this
        }
        return OMinusEntityIterable(transaction, this, right.asOQueryIterable())
    }

    override fun selectMany(linkName: String): EntityIterable {
        return OLinkSelectEntityIterable(transaction, this, linkName)
    }

    override fun selectManyDistinct(linkName: String): EntityIterable {
        return selectMany(linkName).distinct()
    }

    override fun selectDistinct(linkName: String): EntityIterable {
        return selectManyDistinct(linkName)
    }

    override fun findLinks(entities: EntityIterable, linkName: String): EntityIterable? {
        if (entities == EMPTY) {
            return EMPTY
        }
        return OLinkIterableToEntityIterableFiltered(transaction, entities.asOQueryIterable(), linkName, this)
    }

    override fun findLinks(entities: Iterable<Entity?>, linkName: String): EntityIterable? {
        if (entities !is OQueryEntityIterable) {
            unsupported { "findLinks with non-OrientDB entity iterable" }
        }
        return findLinks(entities, linkName)
    }

    override fun asSortResult(): EntityIterable {
        return this
    }

    @Volatile
    private var cachedSize: Long = -1

    override fun size(): Long {
        val sourceQuery = query()
        val countQuery = OCountSelect(sourceQuery)
        cachedSize = countQuery.count(otx?.activeSession)
        return cachedSize
    }

    override fun getRoughSize(): Long {
        val size = cachedSize
        return if (size != -1L) size else size()
    }

    override fun count(): Long {
        val size = cachedSize
        if (size == -1L) {
            countExecutor?.execute {
                otx?.activeSession?.activateOnCurrentThread()
                size()
            }
        }
        return size
    }

    override fun getRoughCount(): Long {
        return cachedSize
    }

    override fun isSortedById(): Boolean {
        return false
    }

    override fun canBeCached(): Boolean {
        return false
    }

    override fun asProbablyCached(): EntityIterableBase? {
        return this
    }
}
