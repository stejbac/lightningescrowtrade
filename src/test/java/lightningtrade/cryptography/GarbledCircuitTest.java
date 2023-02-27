package lightningtrade.cryptography;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import lightningtrade.cryptography.Program.GateData;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static lightningtrade.cryptography.Program.GateType.*;
import static lightningtrade.cryptography.Program.OutputScope.KNOWN_TO_BOTH;
import static lightningtrade.cryptography.Program.OutputScope.KNOWN_TO_NEITHER;

class GarbledCircuitTest {
    private static Program TEST_PROGRAM = Program.builder()
            .addGate(GateData.create(INPUT, KNOWN_TO_NEITHER))
            .addGate(GateData.create(INPUT, KNOWN_TO_NEITHER))
            .addGate(GateData.create(AND, KNOWN_TO_NEITHER, 2, 1))
            .outputIndices(List.of(2))
            .build();
    private static Program TEST_8_BIT_ADDER = createAdder(8);
    private static Program TEST_64_BIT_ADDER = createAdder(64);

    @Test
    void testSplitGate() {
        TEST_PROGRAM.gates().forEach(System.out::println);
        System.out.println();
        ((Program.BinaryGate) TEST_PROGRAM.gates().get(2)).subGates().forEach(System.out::println);
        System.out.println();
        System.out.println(GateData.create(AND, KNOWN_TO_NEITHER, 2, 1));
    }

    @Test
    void testCipher() {
        var cipher = new GarbledCircuit.AesHashCipher(new byte[16]);
        var ciphertext = cipher.encrypt(GarbledBit.zero(), GarbledBit.zero(), 0, 0);
        System.out.println(ciphertext);
        ciphertext = cipher.encrypt(GarbledBit.zero(), GarbledBit.zero(), 1, 0);
        System.out.println(ciphertext);
        ciphertext = cipher.encrypt(GarbledBit.zero(), GarbledBit.zero(), 0, 1);
        System.out.println(ciphertext);
        var plaintext = cipher.decrypt(GarbledBit.zero(), ciphertext, 0, 1);
        System.out.println(plaintext);
    }

    @Test
    void testGenerate() throws Exception {
        var cipher = new GarbledCircuit.AesHashCipher(new byte[16]);
        var secretOffset = GarbledBit.copyFrom(new byte[]{-1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0});
        var garbledBitRnd = SecureRandom.getInstance("SHA1PRNG");
        garbledBitRnd.setSeed(new byte[0]);
        var generator = new GarbledCircuit.Generator(TEST_PROGRAM, secretOffset, cipher, BitVector.empty(), null, garbledBitRnd);
        var circuit = generator.generate();

        var inputs = BitVector.copyFrom(new byte[]{-64}).subList(0, 2);
        var garbledInputs = generator.garbleInputs(inputs, 0);
        var result = circuit.run(garbledInputs);
        System.out.println(result);
        System.out.println(generator.ungarbleOutputs(result.outputs()));
    }

    @Test
    void testAdder() throws Exception {
        var cipher = new GarbledCircuit.AesHashCipher(new byte[16]);
        var secretOffset = GarbledBit.copyFrom(new byte[]{-1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0});
        var garbledBitRnd = SecureRandom.getInstance("SHA1PRNG");
        garbledBitRnd.setSeed(new byte[0]);
        var generator = new GarbledCircuit.Generator(TEST_64_BIT_ADDER, secretOffset, cipher, BitVector.empty(), null, garbledBitRnd);
        var circuit = generator.generate();

        var inputs = BitVector.copyFrom(Longs.toByteArray(12345)).concat(BitVector.copyFrom(Longs.toByteArray(54321)));
        var garbledInputs = generator.garbleInputs(inputs, 0);
        var result = circuit.run(garbledInputs);
        System.out.println(result);
        System.out.println(generator.ungarbleOutputs(result.outputs()));
        System.out.println(Integer.toHexString(66666));
    }

    @Test
    void testShift() {
        System.out.println(Stream.iterate((byte) 1, GarbledCircuit.AesHashCipher::shift)
                .limit(52)
                .collect(Collectors.toList()));
    }

    private static Program createAdder(int bitWidth) {
        var builder = ImmutableList.<GateData>builder();
        for (int i = 0; i < bitWidth * 2; i++) {
            builder.add(GateData.create(INPUT, KNOWN_TO_NEITHER));
        }
        builder.add(GateData.create(FALSE, KNOWN_TO_BOTH)); // carry: c_0
        for (int i = 0; i < bitWidth; i++) {
            builder.add(GateData.create(IDENTITY, KNOWN_TO_NEITHER, 2 + bitWidth + i * 8)); // a_i
            builder.add(GateData.create(IDENTITY, KNOWN_TO_NEITHER, 3 + i * 8));            // b_i
            builder.add(GateData.create(XOR, KNOWN_TO_NEITHER, 3, 2)); // c_i ^ a_i
            builder.add(GateData.create(XOR, KNOWN_TO_NEITHER, 4, 2)); // c_i ^ b_i
            builder.add(GateData.create(XOR, KNOWN_TO_NEITHER, 2, 3)); // c_i ^ a_i ^ b_i
            builder.add(GateData.create(AND, KNOWN_TO_NEITHER, 3, 2)); // (c_i ^ a_i) & (c_i ^ b_i)
            builder.add(GateData.create(XOR, KNOWN_TO_NEITHER, 7, 1)); // c_(i+1) = MAJORITY(a_i, b_i, c_i)
        }
        return Program.builder()
                .addAllGates(builder.build())
                .outputIndices(Ints.asList(IntStream.range(0, bitWidth).map(i -> bitWidth * 9 - i * 7 - 2).toArray()))
                .build();
    }
}
