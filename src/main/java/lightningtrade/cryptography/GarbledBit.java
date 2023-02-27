package lightningtrade.cryptography;

import lightningtrade.App;

import java.util.Arrays;

public final class GarbledBit {
    private static GarbledBit ZERO = new GarbledBit(new byte[16]);

    private final byte[] bytes;

    GarbledBit(byte[] bytes) {
        this.bytes = bytes;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public boolean selectBit() {
        return bytes[0] < 0;
    }

    public static GarbledBit copyFrom(byte[] bytes) {
        return new GarbledBit(bytes.clone());
    }

    public static GarbledBit zero() {
        return ZERO;
    }

    public GarbledBit xor(GarbledBit other) {
        byte[] result = bytes();
        for (int i = 0; i < 16; i++) {
            result[i] ^= other.bytes[i];
        }
        return new GarbledBit(result);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || obj instanceof GarbledBit && Arrays.equals(bytes, ((GarbledBit) obj).bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "0x" + App.toHexString(bytes);
    }
}
