package lightningtrade.cryptography;

import com.google.common.collect.ImmutableList;

import java.util.AbstractList;
import java.util.List;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class GarbledBitVector extends AbstractList<GarbledBit> {
    private final List<GarbledBit> garbledBits;

    private GarbledBitVector(List<GarbledBit> garbledBits) {
        this.garbledBits = garbledBits;
    }

    public GarbledBitVector concat(GarbledBitVector other) {
        throw new UnsupportedOperationException();
    }

    public static GarbledBitVector zeros(int length) {
        throw new UnsupportedOperationException();
    }

    public static GarbledBitVector copyFrom(List<GarbledBit> garbledBits) {
        return new GarbledBitVector(ImmutableList.copyOf(garbledBits));
    }

    public static Collector<GarbledBit, ?, GarbledBitVector> toGarbledBitVector() {
        return Collectors.collectingAndThen(ImmutableList.toImmutableList(), GarbledBitVector::copyFrom);
    }

    @Override
    public GarbledBit get(int index) {
        return garbledBits.get(index);
    }

    @Override
    public int size() {
        return garbledBits.size();
    }
}
