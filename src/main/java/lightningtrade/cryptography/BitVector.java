package lightningtrade.cryptography;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import lightningtrade.App;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public interface BitVector extends List<Boolean> {
    byte[] bytes();

    @Override
    BooleanIterator iterator();

    @Override
    default Boolean get(int index) {
        return getAsBoolean(index);
    }

    boolean getAsBoolean(int index);

    @Override
    BitVector subList(int fromIndex, int toIndex);

    BitVector concat(BitVector other);

    static BitVector copyFrom(byte[] bytes) {
        return new ImmutableArrayBitVector(bytes.clone(), 0, bytes.length * 8);
    }

    private static int get(List<Boolean> list, int i, int x) {
        return Boolean.TRUE.equals(list.get(i)) ? x : 0;
    }

    static BitVector copyFrom(Iterable<Boolean> bits) {
        var os = new ByteArrayOutputStream();
        int[] count = new int[1];
        Iterables.paddedPartition(bits, 8).forEach(list -> {
            int b = get(list, 0, 128) ^ get(list, 1, 64) ^ get(list, 2, 32) ^ get(list, 3, 16)
                    ^ get(list, 4, 8) ^ get(list, 5, 4) ^ get(list, 6, 2) ^ get(list, 7, 1);
            os.write(b);
            count[0] += list.stream().filter(Objects::nonNull).count();
        });
        return copyFrom(os.toByteArray()).subList(0, count[0]);
    }

    static BitVector empty() {
        return ImmutableArrayBitVector.EMPTY;
    }

    static BitVector singleton(boolean value) {
        // TODO: Should we always return a new BitVector, to avoid a potential secret-dependent lookup?
        return value ? ImmutableArrayBitVector.SINGLETON_1 : ImmutableArrayBitVector.SINGLETON_0;
    }

    static Collector<Boolean, ?, BitVector> toBitVector() {
        return Collectors.collectingAndThen(Collectors.toList(), BitVector::copyFrom);
    }

    interface BooleanIterator extends Iterator<Boolean> {
        boolean nextBoolean();

        @Override
        default Boolean next() {
            return nextBoolean();
        }
    }

    class ImmutableArrayBitVector extends AbstractList<Boolean> implements BitVector {
        private static BitVector EMPTY = copyFrom(new byte[0]);
        private static BitVector SINGLETON_0 = copyFrom(new byte[]{0x00}).subList(0, 1);
        private static BitVector SINGLETON_1 = copyFrom(new byte[]{-0x80}).subList(0, 1);
        private final int start, end;
        private final byte[] bytes;

        ImmutableArrayBitVector(byte[] bytes, int start, int end) {
            this.start = start;
            this.end = end;
            this.bytes = bytes;
        }

        @Override
        public boolean getAsBoolean(int index) {
            return (bytes[(index += start) / 8] & (0x80 >>> (index & 7))) != 0;
        }

        @Override
        public int size() {
            return end - start;
        }

        @Override
        public byte[] bytes() {
            if ((start & 7) == 0) {
                byte[] result = Arrays.copyOfRange(bytes, start / 8, (end + 7) / 8);
                if ((end & 7) != 0) {
                    result[result.length - 1] &= 0x0ff << (-end & 7);
                }
                return result;
            }
            throw new UnsupportedOperationException();
        }

        @Override
        public BitVector concat(BitVector other) {
            if (other.isEmpty()) {
                return this;
            }
            if (isEmpty()) {
                return other;
            }
            if ((start & 7) == 0 && (end & 7) == 0) {
                var os = new ByteArrayOutputStream((size() + other.size() + 7) / 8);
                byte[] tmp;
                os.write((tmp = bytes()), 0, tmp.length);
                os.write((tmp = other.bytes()), 0, tmp.length);
                return new ImmutableArrayBitVector(os.toByteArray(), 0, size() + other.size());
            }
            throw new UnsupportedOperationException();
        }

        @Override
        public ImmutableArrayBitVector subList(int fromIndex, int toIndex) {
            Preconditions.checkPositionIndexes(fromIndex, toIndex, size());
            return new ImmutableArrayBitVector(bytes, this.start + fromIndex, this.start + toIndex);
        }

        @Override
        public Boolean get(int index) {
            return BitVector.super.get(index);
        }

        @Override
        public BooleanIterator iterator() {
            return new BooleanIterator() {
                private int index;

                @Override
                public boolean nextBoolean() {
                    return getAsBoolean(index++);
                }

                @Override
                public boolean hasNext() {
                    return index < size();
                }
            };
        }

        @Override
        public String toString() {
            return "BitVector{size=" + size() + ",bytes=0x" + App.toHexString(bytes()) + "}";
        }
    }
}
