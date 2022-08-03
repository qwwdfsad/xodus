package jetbrains.exodus.tree.ibtree;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import jetbrains.exodus.ByteBufferComparator;
import jetbrains.exodus.log.Log;
import jetbrains.exodus.log.LogUtil;
import jetbrains.exodus.log.Loggable;
import jetbrains.exodus.tree.ExpiredLoggableCollection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.AbstractList;
import java.util.Collections;
import java.util.RandomAccess;

final class MutableInternalPage implements MutablePage {
    @Nullable
    ImmutableInternalPage underlying;

    @Nullable
    ObjectArrayList<Entry> changedEntries;

    /**
     * Children inform parent that it should sort {@link #changedEntries} before performing their spill
     */
    boolean sortBeforeInternalSpill;

    @NotNull
    final KeyView keyView;

    @NotNull
    final ExpiredLoggableCollection expiredLoggables;

    @NotNull
    final Log log;

    final int pageSize;
    final int maxKeySize;

    final long pageAddress;

    long cachedTreeSize = -1;

    @NotNull
    final MutableBTree tree;

    boolean spilled;

    boolean unbalanced;

    MutableInternalPage(@NotNull MutableBTree tree, @Nullable ImmutableInternalPage underlying,
                        @NotNull ExpiredLoggableCollection expiredLoggables, @NotNull Log log,
                        int pageSize) {
        this.tree = tree;

        if (underlying != null) {
            pageAddress = underlying.address;
        } else {
            pageAddress = -1;
        }

        this.expiredLoggables = expiredLoggables;
        this.log = log;
        this.pageSize = pageSize;
        this.underlying = underlying;
        this.maxKeySize = pageSize / 4;

        if (underlying == null) {
            changedEntries = new ObjectArrayList<>();
        }

        keyView = new KeyView();
    }

    @Override
    public ByteBuffer key(int index) {
        return keyView.get(index);
    }

    MutablePage mutableChild(int index) {
        fetch();

        assert changedEntries != null;

        return changedEntries.get(index).mutablePage;
    }


    @Override
    public int getEntriesCount() {
        return keyView.size();
    }

    @Override
    public TraversablePage child(int index) {
        if (changedEntries == null) {
            assert underlying != null;
            return underlying.child(index);
        }

        return changedEntries.get(index).mutablePage;
    }

    @Override
    public int find(ByteBuffer key) {
        return Collections.binarySearch(keyView, key, ByteBufferComparator.INSTANCE);
    }

    @Override
    public boolean isInternalPage() {
        return true;
    }

    @Override
    public ByteBuffer value(int index) {
        throw new UnsupportedOperationException("Internal page can not contain values");
    }


    @Override
    public long save(int structureId, @Nullable MutableInternalPage parent) {
        if (changedEntries == null) {
            assert underlying != null;
            return underlying.address;
        }

        var newBuffer = LogUtil.allocatePage(serializedSize());

        assert changedEntries.size() >= 2;
        assert newBuffer.limit() <= pageSize || changedEntries.size() < 4;

        var buffer = newBuffer.slice(ImmutableBTree.LOGGABLE_TYPE_STRUCTURE_METADATA_OFFSET,
                        newBuffer.limit() - ImmutableBTree.LOGGABLE_TYPE_STRUCTURE_METADATA_OFFSET).
                order(ByteOrder.nativeOrder());

        //we add Long.BYTES to preserver (sub)tree size
        assert buffer.alignmentOffset(ImmutableBasePage.KEY_PREFIX_LEN_OFFSET + Long.BYTES, Integer.BYTES) == 0;
        buffer.putInt(ImmutableBasePage.KEY_PREFIX_LEN_OFFSET + Long.BYTES, 0);

        assert buffer.alignmentOffset(ImmutableBasePage.ENTRIES_COUNT_OFFSET + Long.BYTES, Integer.BYTES) == 0;
        buffer.putInt(ImmutableBasePage.ENTRIES_COUNT_OFFSET + Long.BYTES, changedEntries.size());

        int keyPositionsOffset = ImmutableBasePage.KEYS_OFFSET + Long.BYTES;
        int childAddressesOffset = keyPositionsOffset + Long.BYTES * changedEntries.size();
        int subTreeSizeOffset = childAddressesOffset + Long.BYTES * changedEntries.size();
        int keysDataOffset = subTreeSizeOffset + Integer.BYTES * changedEntries.size();

        int treeSize = 0;
        for (var entry : changedEntries) {
            var child = entry.mutablePage;
            //we need to save mutableChild first to cache tree size, otherwise it could impact big performance
            //overhead during save of the data
            var childAddress = child.save(structureId, this);

            long subTreeSize = child.treeSize();
            treeSize += subTreeSize;

            var key = entry.key;
            var keySize = key.limit();

            assert buffer.alignmentOffset(keyPositionsOffset, Integer.BYTES) == 0;
            assert buffer.alignmentOffset(keyPositionsOffset + Integer.BYTES, Integer.BYTES) == 0;

            buffer.putInt(keyPositionsOffset, keysDataOffset - Long.BYTES);
            buffer.putInt(keyPositionsOffset + Integer.BYTES, keySize);

            assert buffer.alignmentOffset(childAddressesOffset, Long.BYTES) == 0;
            buffer.putLong(childAddressesOffset, childAddress);

            assert buffer.alignmentOffset(subTreeSizeOffset, Integer.BYTES) == 0;
            buffer.putInt(subTreeSizeOffset, (int) subTreeSize);

            buffer.put(keysDataOffset, key, 0, keySize);

            keyPositionsOffset += Long.BYTES;
            subTreeSizeOffset += Integer.BYTES;
            keysDataOffset += keySize;
            childAddressesOffset += Long.BYTES;
        }

        assert buffer.alignmentOffset(0, Long.BYTES) == 0;
        buffer.putLong(0, treeSize);

        cachedTreeSize = treeSize;

        byte type;
        if (parent == null) {
            type = ImmutableBTree.INTERNAL_ROOT_PAGE;
        } else {
            type = ImmutableBTree.INTERNAL_PAGE;
        }

        return log.writeInsideSinglePage(type, structureId, newBuffer, true);
    }

    @Override
    public RebalanceResult rebalance(@Nullable MutableInternalPage parent, boolean rebalanceChildren) {
        if (!unbalanced) {
            return null;
        }

        assert changedEntries != null;

        unbalanced = false;

        boolean needsToBeRebalanced = false;
        final int entriesCount = changedEntries.size();

        if (rebalanceChildren) {
            boolean rebalanceCurrentChildChildren = true;

            for (int i = 0; i < changedEntries.size(); i++) {
                var entry = changedEntries.get(i);
                var page = entry.mutablePage;

                var result = page.rebalance(this, rebalanceCurrentChildChildren);
                rebalanceCurrentChildChildren = true;

                if (result != null) {
                    if (result.isEmpty) {
                        changedEntries.remove(i);
                        i--;
                    } else if (result.mergeWithSibling) {
                        if (changedEntries.size() == 1) {
                            needsToBeRebalanced = true;
                            break;
                        }


                        if (i == 0) {
                            var nextEntry = changedEntries.remove(i + 1);
                            var nextPage = nextEntry.mutablePage;

                            var nextResult = nextPage.rebalance(this,
                                    true);
                            if (nextResult != null) {
                                if (nextResult.isEmpty) {
                                    rebalanceCurrentChildChildren = false;
                                } else {
                                    rebalanceCurrentChildChildren = result.rebalanceChildrenAfterMerge ||
                                            nextResult.rebalanceChildrenAfterMerge;
                                    page.merge(nextPage);
                                }
                            } else {
                                rebalanceCurrentChildChildren = result.rebalanceChildrenAfterMerge;
                                nextPage.fetch();
                                page.merge(nextPage);
                            }
                        } else {
                            var prevEntry = changedEntries.get(i - 1);
                            var prevPage = prevEntry.mutablePage;

                            prevPage.merge(page);

                            changedEntries.remove(i);

                            //if we need to rebalance merged sibling we need to step
                            //one more step back, otherwise because current item
                            //is removed we will process next item
                            if (result.rebalanceChildrenAfterMerge) {
                                i--;
                            }
                        }

                        //because item is removed next item will have the same index
                        //so we step back to have the same result after the index
                        //increment by cycle
                        i--;
                    }
                }
            }
        }

        if (changedEntries.isEmpty()) {
            return new RebalanceResult(false, false, true);
        }

        if (changedEntries.size() < 2 ||
                entriesCount != changedEntries.size() && needsToBeMerged(pageSize / 4)) {
            return new RebalanceResult(true, needsToBeRebalanced, false);
        }


        return null;
    }

    @Override
    public void unbalance() {
        unbalanced = true;
    }

    private int serializedSize() {
        assert changedEntries != null;

        int size = ImmutableBTree.LOGGABLE_TYPE_STRUCTURE_METADATA_OFFSET + ImmutableLeafPage.KEYS_OFFSET +
                (2 * Long.BYTES + Integer.BYTES) * changedEntries.size() + Long.BYTES;

        for (Entry entry : changedEntries) {
            size += entry.key.limit();
        }

        return size;
    }

    private boolean needsToBeMerged(int threshold) {
        assert changedEntries != null;

        int size = ImmutableBTree.LOGGABLE_TYPE_STRUCTURE_METADATA_OFFSET + ImmutableLeafPage.KEYS_OFFSET +
                (2 * Long.BYTES + Integer.BYTES) * changedEntries.size() + Long.BYTES;

        for (Entry entry : changedEntries) {
            size += entry.key.limit();

            if (size >= threshold) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void merge(MutablePage page) {
        fetch();

        assert changedEntries != null;

        var internalPage = (MutableInternalPage) page;

        changedEntries.addAll(internalPage.changedEntries);

        unbalanced = true;
    }

    @Override
    public void spill(@Nullable MutableInternalPage parent) {
        if (spilled || changedEntries == null) {
            return;
        }

        //spill children first
        for (var childEntry : changedEntries) {
            childEntry.mutablePage.spill(this);
        }

        //new children were appended sort them
        if (sortBeforeInternalSpill) {
            changedEntries.sort(null);
        }

        var page = this;
        while (true) {
            var nextSiblingEntries = page.splitAtPageSize();

            if (nextSiblingEntries == null) {
                break;
            }

            if (parent == null) {
                parent = new MutableInternalPage(tree, null, expiredLoggables, log, pageSize);
                assert tree.root == this;
                tree.root = parent;

                parent.addChild(changedEntries.get(0).key, page);
            }

            page = new MutableInternalPage(tree, null, expiredLoggables, log, pageSize);
            page.changedEntries = nextSiblingEntries;
            page.spilled = true;

            parent.addChild(nextSiblingEntries.get(0).key, page);
            parent.sortBeforeInternalSpill = true;
        }

        spilled = true;
        assert changedEntries.size() <= 2 || serializedSize() <= pageSize;

        //parent first spill children then itself
        //so we do not need sort children of parent or spill parent itself
    }

    private ObjectArrayList<Entry> splitAtPageSize() {
        assert changedEntries != null;

        //each page should contain at least two entries, root page can contain less entries
        if (changedEntries.size() < 4) {
            return null;
        }

        var firstEntry = changedEntries.get(0);
        var secondEntry = changedEntries.get(1);


        int size = ImmutableBTree.LOGGABLE_TYPE_STRUCTURE_METADATA_OFFSET +
                ImmutableInternalPage.KEYS_OFFSET + 2 * (2 * Long.BYTES + Integer.BYTES) + Long.BYTES;

        size += firstEntry.key.limit();
        size += secondEntry.key.limit();

        int indexSplitAt = 1;

        for (int i = 2; i < changedEntries.size(); i++) {
            var entry = changedEntries.get(i);
            size += 2 * Long.BYTES + Integer.BYTES + entry.key.limit();

            if (size > pageSize) {
                break;
            }

            indexSplitAt = i;
        }

        var splitResultSize = changedEntries.size() - (indexSplitAt + 1);
        if (splitResultSize == 1) {
            indexSplitAt = indexSplitAt - (2 - splitResultSize);
        }

        ObjectArrayList<Entry> result = null;

        if (indexSplitAt < changedEntries.size() - 1) {
            result = new ObjectArrayList<>();
            result.addAll(0, changedEntries.subList(indexSplitAt + 1, changedEntries.size()));

            changedEntries.removeElements(indexSplitAt + 1, changedEntries.size());
        }

        return result;
    }

    void addChild(ByteBuffer key, MutablePage page) {
        fetch();

        assert changedEntries != null;

        changedEntries.add(new Entry(key, page));
    }

    public boolean fetch() {
        if (underlying == null) {
            return false;
        }

        expiredLoggables.add(pageAddress, pageSize);

        final int size = underlying.getEntriesCount();
        changedEntries = new ObjectArrayList<>(size);

        for (int i = 0; i < size; i++) {
            var key = underlying.key(i);
            var child = underlying.child(i);

            changedEntries.add(new Entry(key, child.toMutable(tree, expiredLoggables)));
        }

        underlying = null;
        return true;
    }

    @Override
    public long treeSize() {
        if (underlying != null) {
            return underlying.getTreeSize();
        }

        assert changedEntries != null;

        if (cachedTreeSize >= 0) {
            return cachedTreeSize;
        }

        int treeSize = 0;
        for (var entry : changedEntries) {
            treeSize += entry.mutablePage.treeSize();
        }

        return treeSize;
    }

    @Override
    public long address() {
        if (underlying != null) {
            return underlying.address;
        }

        return Loggable.NULL_ADDRESS;
    }

    static final class Entry implements Comparable<Entry> {
        ByteBuffer key;
        MutablePage mutablePage;

        public Entry(ByteBuffer key, MutablePage mutablePage) {
            assert key != null;

            this.key = key;
            this.mutablePage = mutablePage;
        }

        @Override
        public int compareTo(@NotNull Entry entry) {
            return ByteBufferComparator.INSTANCE.compare(key, entry.key);
        }
    }

    final class KeyView extends AbstractList<ByteBuffer> implements RandomAccess {
        @Override
        public ByteBuffer get(int index) {
            if (changedEntries != null) {
                return changedEntries.get(index).key;
            }

            assert underlying != null;

            return underlying.key(index);
        }

        @Override
        public int size() {
            if (changedEntries != null) {
                return changedEntries.size();
            }

            assert underlying != null;

            return underlying.getEntriesCount();
        }
    }
}