/**
 * Copyright 2010 - 2022 JetBrains s.r.o.
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
package jetbrains.exodus.log


import jetbrains.exodus.ByteBufferByteIterable
import jetbrains.exodus.ExodusException
import jetbrains.exodus.InvalidSettingException
import jetbrains.exodus.core.dataStructures.ConcurrentIntObjectCache
import jetbrains.exodus.util.MathUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal abstract class LogCache {

    internal val memoryUsage: Long
    protected val memoryUsagePercentage: Int
    internal val pageSize: Int

    /**
     * @param memoryUsage treeSize of memory which the cache is allowed to occupy (in bytes).
     * @param pageSize    number of bytes in a page.
     * @throws InvalidSettingException if settings are invalid.
     */
    protected constructor(memoryUsage: Long, pageSize: Int) {
        checkPageSize(pageSize)
        this.pageSize = pageSize
        checkIntegerLogarithm(pageSize) {
            "Log cache page size should be a power of 2: $pageSize"
        }
        val maxMemory = Runtime.getRuntime().maxMemory()
        if (maxMemory <= memoryUsage) {
            throw InvalidSettingException("Memory usage cannot be greater than JVM maximum memory")
        }
        this.memoryUsage = memoryUsage
        memoryUsagePercentage = 0
    }

    /**
     * @param memoryUsagePercentage treeSize of memory which the cache is allowed to occupy (in percents to the max memory value).
     * @param pageSize              number of bytes in a page.
     * @throws InvalidSettingException if settings are invalid.
     */
    protected constructor(memoryUsagePercentage: Int, pageSize: Int) {
        checkPageSize(pageSize)
        if (memoryUsagePercentage < MINIMUM_MEM_USAGE_PERCENT) {
            throw InvalidSettingException("Memory usage percent cannot be less than $MINIMUM_MEM_USAGE_PERCENT")
        }
        if (memoryUsagePercentage > MAXIMUM_MEM_USAGE_PERCENT) {
            throw InvalidSettingException("Memory usage percent cannot be greater than $MAXIMUM_MEM_USAGE_PERCENT")
        }
        this.pageSize = pageSize
        checkIntegerLogarithm(pageSize) {
            "Log cache page size should be a power of 2: $pageSize"
        }
        val maxMemory = Runtime.getRuntime().maxMemory()
        memoryUsage = if (maxMemory == Long.MAX_VALUE) Long.MAX_VALUE else maxMemory / 100L * memoryUsagePercentage.toLong()
        this.memoryUsagePercentage = memoryUsagePercentage
    }

    abstract fun clear()

    abstract fun hitRate(): Float

    abstract fun cachePage(log: Log, pageAddress: Long, page: ByteBuffer)

    abstract fun getPage(log: Log, pageAddress: Long): ByteBuffer

    abstract fun getCachedPage(log: Log, pageAddress: Long): ByteBuffer?

    protected abstract fun getPageIterable(log: Log, pageAddress: Long): ByteBufferByteIterable

    internal abstract fun removePage(log: Log, pageAddress: Long)

    protected fun readFullPage(log: Log, pageAddress: Long): ByteBuffer {
        val fileAddress = log.getFileAddress(pageAddress)
        var readAheadMultiple = 1
        while (readAheadMultiple < log.config.cacheReadAheadMultiple) {
            if (log.getFileAddress(pageAddress + pageSize * readAheadMultiple) != fileAddress ||
                    getCachedPage(log, pageAddress + pageSize * readAheadMultiple) != null) {
                break
            }
            ++readAheadMultiple
        }
        return if (readAheadMultiple == 1) {
            allocPage().also { page -> readBytes(log, page, pageAddress) }
        } else {
            val pages = LogUtil.allocatePage(pageSize * readAheadMultiple)

            readBytes(log, pages, pageAddress)
            for (i in 1 until readAheadMultiple) {
                cachePage(log, pageAddress + pageSize * i,
                        pages.slice(i * pageSize, (i + 1) * pageSize).order(pages.order()))
            }

            pages.slice(0, pageSize).order(pages.order()).also { page ->
                cachePage(log, pageAddress, page)
            }
        }
    }

    fun allocPage(): ByteBuffer = LogUtil.allocatePage(pageSize)

    private fun readBytes(log: Log, bytes: ByteBuffer, pageAddress: Long) {
        val bytesRead = log.readBytes(bytes, pageAddress)
        if (bytesRead != bytes.limit()) {
            throw ExodusException("Can't read full page from log [" + log.location + "] with address "
                    + pageAddress + " (file " + LogUtil.getLogFilename(log.getFileAddress(pageAddress)) + "), offset: "
                    + pageAddress % log.fileLengthBound + ", read: " + bytesRead)
        }
    }

    companion object {

        protected const val MINIMUM_PAGE_SIZE = LogUtil.LOG_BLOCK_ALIGNMENT
        protected const val DEFAULT_OPEN_FILES_COUNT = 16
        protected const val MINIMUM_MEM_USAGE_PERCENT = 5
        protected const val MAXIMUM_MEM_USAGE_PERCENT = 95
        private val TAIL_PAGES_CACHE = ConcurrentIntObjectCache<ByteBuffer>(10)

        private fun checkPageSize(pageSize: Int) {
            if (pageSize < MINIMUM_PAGE_SIZE) {
                throw InvalidSettingException("Page size cannot be less than $MINIMUM_PAGE_SIZE")
            }
            if (pageSize % MINIMUM_PAGE_SIZE != 0) {
                throw InvalidSettingException("Page size should be multiple of $MINIMUM_PAGE_SIZE")
            }
        }

        private fun checkIntegerLogarithm(i: Int, exceptionMessage: () -> String) {
            if (1 shl MathUtil.integerLogarithm(i) != i) {
                throw InvalidSettingException(exceptionMessage())
            }
        }

        @JvmStatic
        protected fun postProcessTailPage(page: ByteBuffer): ByteBuffer {
            if (isTailPage(page)) {
                val length = page.limit()
                val cachedTailPage = getCachedTailPage(length)
                if (cachedTailPage != null) {
                    return cachedTailPage
                }
                TAIL_PAGES_CACHE.cacheObject(length, page)
            }
            return page
        }

        fun getCachedTailPage(cachePageSize: Int): ByteBuffer? {
            return TAIL_PAGES_CACHE.tryKey(cachePageSize)
        }

        private fun isTailPage(page: ByteBuffer): Boolean {
            for (i in 0 until page.limit()) {
                val b = page[i]
                if (b != 0x80.toByte()) {
                    return false
                }
            }
            return true
        }
    }
}
