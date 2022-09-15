/*
 * *
 *  * Copyright 2010 - 2022 JetBrains s.r.o.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * https://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package jetbrains.exodus.tree.ibtree;

import jetbrains.exodus.ByteIterable;
import jetbrains.exodus.util.ArrayBackedByteIterable;
import jetbrains.exodus.tree.ITree;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

interface BTree extends ITree {
    @Nullable
    TraversablePage getRoot();

    @Nullable
    default ByteIterable get(@NotNull ByteIterable key) {
        return find(key);
    }

    default ByteIterable find(ByteIterable key) {
        var root = getRoot();
        if (root == null) {
            return null;
        }

        assert root.getKeyPrefixSize() == 0;

        var page = root;
        ArrayBackedByteIterable currentKey = key.toArrayBackedIterable();
        final int basicOffset = currentKey.offset;

        try {
            while (true) {
                int index = page.find(currentKey);

                if (!page.isInternalPage()) {
                    if (index < 0) {
                        return null;
                    }

                    return page.value(index);
                }

                //there is no exact match of the key
                if (index < 0) {
                    //index of the first page which contains all keys which are bigger than current one
                    index = -index - 1;

                    if (index > 0) {
                        index--;
                    } else {
                        //all keys in the tree bigger than provided
                        return null;
                    }
                }

                page = page.child(index);

                var keyPrefixSize = page.getKeyPrefixSize();


                if (keyPrefixSize > 0) {
                    if (currentKey.limit >= keyPrefixSize) {
                        currentKey.offset = basicOffset + keyPrefixSize;
                    } else {
                        return null;
                    }
                }
            }
        } finally {
            currentKey.offset = basicOffset;
        }
    }

    default boolean hasPair(@NotNull ByteIterable key, @NotNull ByteIterable value) {
        var foundValue = find(key);
        if (foundValue == null) {
            return false;
        }

        return foundValue.equals(value);
    }

    default boolean hasKey(@NotNull ByteIterable key) {
        return find(key) != null;
    }
}
