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
package jetbrains.vectoriadb.index.diskcache;

import jetbrains.vectoriadb.index.hash.XxHash;

/**
 * A probabilistic multiset for estimating the popularity of an element within a time window. The
 * maximum frequency of an element is limited to 15 (4-bits) and an aging process periodically
 * halves the popularity of all elements.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class FrequencySketch {
    /*
     * This class maintains a 4-bit CountMinSketch [1] with periodic aging to provide the popularity
     * history for the TinyLfu admission policy [2]. The time and space efficiency of the sketch
     * allows it to cheaply estimate the frequency of an entry in a stream of cache access events.
     *
     * The counter matrix is represented as a single-dimensional array holding 16 counters per slot. A
     * fixed depth of four balances the accuracy and cost, resulting in a width of four times the
     * length of the array. To retain an accurate estimation, the array's length equals the maximum
     * number of entries in the cache, increased to the closest power-of-two to exploit more efficient
     * bit masking. This configuration results in a confidence of 93.75% and an error bound of
     * e / width.
     *
     * To improve hardware efficiency, an item's counters are constrained to a 64-byte block, which is
     * the size of an L1 cache line. This differs from the theoretical ideal where counters are
     * uniformly distributed to minimize collisions. In that configuration, the memory accesses are
     * not predictable and lack spatial locality, which may cause the pipeline to need to wait for
     * four memory loads. Instead, the items are uniformly distributed to blocks, and each counter is
     * uniformly selected from a distinct 16-byte segment. While the runtime memory layout may result
     * in the blocks not being cache-aligned, the L2 spatial prefetcher tries to load aligned pairs of
     * cache lines, so the typical cost is only one memory access.
     *
     * The frequency of all entries is aged periodically using a sampling window based on the maximum
     * number of entries in the cache. This is referred to as the reset operation by TinyLfu and keeps
     * the sketch fresh by dividing all counters by two and subtracting based on the number of odd
     * counters found. The O(n) cost of aging is amortized, ideal for hardware prefetching, and uses
     * inexpensive bit manipulations per array location.
     *
     * [1] An Improved Data Stream Summary: The Count-Min Sketch and its Applications
     * http://dimacs.rutgers.edu/~graham/pubs/papers/cm-full.pdf
     * [2] TinyLFU: A Highly Efficient Cache Admission Policy
     * https://dl.acm.org/citation.cfm?id=3149371
     * [3] Hash Function Prospector: Three round functions
     * https://github.com/skeeto/hash-prospector#three-round-functions
     */


    private static final long RESET_MASK = 0x7777777777777777L;
    private static final long ONE_MASK = 0x1111111111111111L;

    int sampleSize;
    private final int blockMask;
    private final long[] table;
    private int size;

    /**
     * Creates a lazily initialized frequency sketch.
     */
    @SuppressWarnings("NullAway.Init")
    public FrequencySketch(int size) {
        int maximum = (int) Math.min((long) size, Integer.MAX_VALUE >>> 1);

        var table = new long[Math.max(Integer.highestOneBit(maximum - 1) << 1, 8)];

        sampleSize = ((long) size == 0) ? 10 : (10 * maximum);
        blockMask = (table.length >>> 3) - 1;
        if (sampleSize <= 0) {
            sampleSize = Integer.MAX_VALUE;
        }
        this.size = 0;

        this.table = table;
    }


    /**
     * Returns the estimated number of occurrences of an element, up to the maximum (15).
     *
     * @param e the element to count occurrences of
     * @return the estimated number of occurrences of the element; possibly zero but never negative
     */
    public int frequency(long e) {
        int[] count = new int[4];
        int blockHash = (int) XxHash.hash(e);
        int counterHash = rehash(blockHash);
        int block = (blockHash & blockMask) << 3;
        for (int i = 0; i < 4; i++) {
            int h = counterHash >>> (i << 3);
            int index = (h >>> 1) & 15;
            int offset = h & 1;
            count[i] = (int) ((table[block + offset + (i << 1)] >>> (index << 2)) & 0xfL);
        }
        return Math.min(Math.min(count[0], count[1]), Math.min(count[2], count[3]));
    }

    /**
     * Increments the popularity of the element if it does not exceed the maximum (15). The popularity
     * of all elements will be periodically down sampled when the observed events exceed a threshold.
     * This process provides a frequency aging to allow expired long term entries to fade away.
     *
     * @param e the element to add
     */
    @SuppressWarnings("ShortCircuitBoolean")
    public void increment(long e) {

        int[] index = new int[8];
        int blockHash = (int) XxHash.hash(e);
        int counterHash = rehash(blockHash);
        int block = (blockHash & blockMask) << 3;
        for (int i = 0; i < 4; i++) {
            int h = counterHash >>> (i << 3);
            index[i] = (h >>> 1) & 15;
            int offset = h & 1;
            index[i + 4] = block + offset + (i << 1);
        }
        boolean added =
                incrementAt(index[4], index[0])
                        | incrementAt(index[5], index[1])
                        | incrementAt(index[6], index[2])
                        | incrementAt(index[7], index[3]);

        if (added && (++size == sampleSize)) {
            reset();
        }
    }

    /** Applies another round of hashing for additional randomization. */
    static int rehash(int x) {
        x *= 0x31848bab;
        x ^= x >>> 14;
        return x;
    }

    /**
     * Increments the specified counter by 1 if it is not already at the maximum value (15).
     *
     * @param i the table index (16 counters)
     * @param j the counter to increment
     * @return if incremented
     */
    boolean incrementAt(int i, int j) {
        int offset = j << 2;
        long mask = (0xfL << offset);
        if ((table[i] & mask) != mask) {
            table[i] += (1L << offset);
            return true;
        }
        return false;
    }

    /** Reduces every counter by half of its original value. */
    void reset() {
        int count = 0;
        for (int i = 0; i < table.length; i++) {
            count += Long.bitCount(table[i] & ONE_MASK);
            table[i] = (table[i] >>> 1) & RESET_MASK;
        }
        size = (size - (count >>> 2)) >>> 1;
    }
}
