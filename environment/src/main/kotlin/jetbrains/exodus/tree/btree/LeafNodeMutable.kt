/*
 * Copyright 2010 - 2023 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.tree.btree

import jetbrains.exodus.*
import jetbrains.exodus.log.CompressedUnsignedLongByteIterable.Companion.fillBytes
import jetbrains.exodus.log.Loggable
import jetbrains.exodus.tree.ITree

/**
 * Stateful leaf node for mutable tree
 */
internal class LeafNodeMutable(override val key: ByteIterable, override val value: ByteIterable) :
    BaseLeafNodeMutable() {
    override var address = Loggable.NULL_ADDRESS
        private set

    override fun save(tree: ITree): Long {
        check(address == Loggable.NULL_ADDRESS) { "Leaf already saved" }
        val keyLength = key.length
        val mutableTree = tree as BTreeMutable
        val output = mutableTree.leafStream!!
        fillBytes(keyLength.toLong(), output)
        ByteIterableBase.fillBytes(key, output)
        ByteIterableBase.fillBytes(value, output)
        address = tree.log.write(
            mutableTree.leafType, tree.structureId, output.asArrayByteIterable(),
            tree.expiredLoggables
        )
        return address
    }

    override fun delete(value: ByteIterable?): Boolean {
        throw UnsupportedOperationException("Supported by dup node only")
    }

    override fun toString(): String {
        return "LN* {key:$key} @ $address"
    }
}
