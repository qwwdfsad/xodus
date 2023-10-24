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
package jetbrains.vectoriadb.index.util.collections;

import org.apache.commons.lang3.ArrayUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class NBHMLLConcurrentInsertRemoveTest {
    private static final int THREAD_INSERTION_COUNT = 10;
    private static final int THREAD_DELETION_COUNT = 10;
    private static final int INSERT_COUNT = 100_000;

    @Test
    public void testConcurrentInsertRemove() {
        var nbhmll = new NonBlockingHashMapLongLong();
        try (var executor = Executors.newCachedThreadPool()) {
            var insertFutures = new Future<?>[THREAD_INSERTION_COUNT];
            var removeFutures = new Future<?>[THREAD_DELETION_COUNT];

            var latch = new CountDownLatch(1);
            var added = new ConcurrentHashMap<Long, Long>();

            for (var i = 0; i < THREAD_INSERTION_COUNT; i++) {
                insertFutures[i] = executor.submit(new NBHMLLConcurrentInsertRunnable(i * INSERT_COUNT,
                        (i + 1) * INSERT_COUNT, latch, nbhmll, added));
            }

            var lastIteration = new AtomicBoolean(false);
            for (var i = 0; i < THREAD_DELETION_COUNT; i++) {
                removeFutures[i] = executor.submit(new NBHMLLConcurrentRemoveRunnable(latch, nbhmll, added,
                        lastIteration));
            }

            latch.countDown();

            for (var future : insertFutures) {
                try {
                    future.get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }

            lastIteration.set(true);

            for (var future : removeFutures) {
                try {
                    future.get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }

            for (long i = 0; i < THREAD_INSERTION_COUNT * INSERT_COUNT; i++) {
                Assert.assertEquals("key  = " + i, i % 2 == 1 ? -1 : 2 * i, nbhmll.get(i));
            }
        }
    }

    private static final class NBHMLLConcurrentInsertRunnable implements Callable<Void> {
        private final long startIndex;
        private final long endIndex;

        private final CountDownLatch latch;

        private final NonBlockingHashMapLongLong nbhmll;

        private final ConcurrentHashMap<Long, Long> added;

        private NBHMLLConcurrentInsertRunnable(long startIndex, long endIndex, CountDownLatch latch,
                                               NonBlockingHashMapLongLong nbhmll,
                                               ConcurrentHashMap<Long, Long> added) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.latch = latch;
            this.nbhmll = nbhmll;
            this.added = added;
        }

        @Override
        public Void call() {
            var keys = new long[(int) (endIndex - startIndex)];
            for (long i = startIndex; i < endIndex; i++) {
                keys[(int) (i - startIndex)] = i;
            }
            ArrayUtils.shuffle(keys);

            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            for (var value : keys) {
                nbhmll.put(value, 2 * value);
                added.put(value, 2 * value);
            }

            return null;

        }
    }

    private static final class NBHMLLConcurrentRemoveRunnable implements Callable<Void> {
        private final CountDownLatch latch;

        private final NonBlockingHashMapLongLong nbhmll;

        private final ConcurrentHashMap<Long, Long> added;

        private final AtomicBoolean lastIteration;

        private NBHMLLConcurrentRemoveRunnable(CountDownLatch latch,
                                               NonBlockingHashMapLongLong nbhmll,
                                               ConcurrentHashMap<Long, Long> added, AtomicBoolean lastIteration) {
            this.latch = latch;
            this.nbhmll = nbhmll;
            this.added = added;
            this.lastIteration = lastIteration;
        }

        @Override
        public Void call() {
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            do {
                for (var key : added.keySet()) {
                    if (key % 2 == 1) {
                        nbhmll.remove(key, 2 * key);
                        added.remove(key, 2 * key);
                    }

                }
            } while (!lastIteration.get());

            for (var key : added.keySet()) {
                if (key % 2 == 1) {
                    nbhmll.remove(key, 2 * key);
                    added.remove(key, 2 * key);
                }
            }

            return null;

        }
    }
}
