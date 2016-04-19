/**
 * Copyright 2010 - 2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.entitystore;

import org.jetbrains.annotations.Nullable;

import java.util.Iterator;

public interface EntityIterator extends Iterator<Entity> {

    /**
     * Skips a number of entities from the iterable.
     *
     * @param number number of entities to skip.
     * @return true if there are more entities available.
     */
    boolean skip(final int number);

    /**
     * Returns entity id the next element in the iteration.
     *
     * @return entity id of the next element in the iteration
     * @throws java.util.NoSuchElementException if the iteration has no more elements
     */
    @Nullable
    EntityId nextId();

    /**
     * @return true if the iterator was actually disposed
     */
    boolean dispose();

    boolean shouldBeDisposed();

    @Deprecated
    int getCurrentVersion();
}
