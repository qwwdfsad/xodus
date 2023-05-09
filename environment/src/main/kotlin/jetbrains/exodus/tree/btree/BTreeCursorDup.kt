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

import jetbrains.exodus.tree.TreeCursor

/**
 * BTree iterator with duplicates support
 */
internal class BTreeCursorDup(// hack to avoid casts
    traverser: BTreeTraverserDup
) : TreeCursor(traverser) {
    override fun getNextDup(): Boolean {
        val traverser = traverser as BTreeTraverserDup
        // move to next dup if in -1 position or dupCursor has next element
        return hasNext() && traverser.inDupTree && next && traverser.inDupTree
    }

    override fun getNextNoDup(): Boolean {
        val traverser = traverser as BTreeTraverserDup
        if (traverser.inDupTree) {
            traverser.popUntilDupRight()
            canGoDown = false
        }
        return next
    }

    override fun getPrevDup(): Boolean {
        val traverser = traverser as BTreeTraverserDup
        // move to next dup if in -1 position or dupCursor has next element
        return hasPrev() && traverser.inDupTree && prev && traverser.inDupTree
    }

    override fun getPrevNoDup(): Boolean {
        val traverser = traverser as BTreeTraverserDup
        traverser.popUntilDupLeft() // ignore duplicates
        return prev
    }

    override fun count(): Int {
        val traverser = traverser as BTreeTraverserDup
        return if (traverser.inDupTree) traverser.currentNode.tree.size.toInt() else super.count()
    }
}
