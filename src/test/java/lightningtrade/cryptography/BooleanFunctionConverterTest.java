package lightningtrade.cryptography;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import org.junit.jupiter.api.Test;
import securecompute.algebra.BooleanField;
import securecompute.algebra.FiniteField;
import securecompute.algebra.polynomial.BasePolynomialExpression;
import securecompute.circuit.AlgebraicFunction;
import securecompute.circuit.ArithmeticCircuit;
import securecompute.circuit.BooleanFunction;
import securecompute.circuit.cryptography.Sha2;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static lightningtrade.cryptography.Program.OutputScope.*;

class BooleanFunctionConverterTest {
    private static final String SHA256_IV_HEX = "6a09e667 bb67ae85 3c6ef372 a54ff53a 510e527f 9b05688c 1f83d9ab 5be0cd19";
    private static final String BLOCK_TEMPLATE_HEX = "80000000 00000000 00000000 00000000 00000000 00000000 00000000 00000100";

    @Test
    void testBuildProgram() {
        var program = new BooleanFunctionConverter()
                .outputScopeFn(i -> i == 0 ? KNOWN_TO_GENERATOR : i == 1 ? KNOWN_TO_EVALUATOR : KNOWN_TO_NEITHER)
                .isRandomInputFn(i -> i == 0)
                .buildProgram(BooleanFunction.OR);
        program.gates().forEach(System.out::println);
        System.out.println(program.ungarbledOutputIndices());
        System.out.println(program.outputIndices());

        program = Simplifier.simplify(program);
        program.gates().forEach(System.out::println);
        System.out.println(program.ungarbledOutputIndices());
        System.out.println(program.outputIndices());
    }

    @Test
    void testSha256Program() {
        String initialStateHex = "00" + BLOCK_TEMPLATE_HEX + "8000000000000000" + SHA256_IV_HEX + SHA256_IV_HEX;
        BitVector initialState = BitVector.copyFrom(
                BaseEncoding.base16().lowerCase().decode(initialStateHex.replace(" ", ""))
        ).subList(7, 840);

        BooleanFunction constantFn = (BooleanFunction) constantFn(BooleanField.INSTANCE, initialState);
        BooleanFunction xorFn = (BooleanFunction) BooleanFunction.vectorFn(BooleanFunction.XOR, 256);
        BooleanFunction sha2StepFn = (BooleanFunction) Sha2.rawSha2StepCircuit(false).asFunction();
        ArithmeticCircuit.Gate<Boolean> g0, g1, g2, g3, g4, g5, g6;

        ArithmeticCircuit.Builder<Boolean> builder = ArithmeticCircuit.builder(BooleanField.INSTANCE).maximumFanOut(1).maximumFanIn(1)
                .addGate(g0 = new ArithmeticCircuit.InputPort<>(BooleanField.INSTANCE, 512))
                .addGate(g1 = new ArithmeticCircuit.Gate<>(xorFn, "XorInputs"))
                .addGate(g2 = new ArithmeticCircuit.Gate<>(constantFn, "InitialState"))
                .addGate(g3 = new ArithmeticCircuit.Gate<>(sha2StepFn, "Sha2Step-0"))
                .addGate(g5 = new ArithmeticCircuit.Gate<>(unusedOutputsFn(), "UnusedOutputs"))
                .addGate(g6 = new ArithmeticCircuit.OutputPort<>(BooleanField.INSTANCE, 256))
                .addWires(g0, g1, 512)
                .addWire(g2, g3)
                .addWires(g1, g3, 256)
//                .addWires(g1, 0x00, 1, g3, 0x20, -1, 32)
//                .addWires(g1, 0x20, 1, g3, 0x40, -1, 32)
//                .addWires(g1, 0x40, 1, g3, 0x60, -1, 32)
//                .addWires(g1, 0x60, 1, g3, 0x80, -1, 32)
//                .addWires(g1, 0x80, 1, g3, 0xa0, -1, 32)
//                .addWires(g1, 0xa0, 1, g3, 0xc0, -1, 32)
//                .addWires(g1, 0xc0, 1, g3, 0xe0, -1, 32)
//                .addWires(g1, 0xe0, 1, g3, 0x100, -1, 32)
                .addWires(g2, g3, 832);

        for (int i = 1; i < 64; i++, g3 = g4) {
            builder.addGate(g4 = new ArithmeticCircuit.Gate<>(sha2StepFn, "Sha2Step-" + i)).addWires(g3, g4, 1089);
        }

        ArithmeticCircuit<Boolean> sha256Circuit = builder
                .addWires(g3, g5, 833)
                .addWires(g3, g6, 256)
//                .addWires(g3, 0x341, 1, g6, 0x1f, -1, 32)
//                .addWires(g3, 0x361, 1, g6, 0x3f, -1, 32)
//                .addWires(g3, 0x381, 1, g6, 0x5f, -1, 32)
//                .addWires(g3, 0x3a1, 1, g6, 0x7f, -1, 32)
//                .addWires(g3, 0x3c1, 1, g6, 0x9f, -1, 32)
//                .addWires(g3, 0x3e1, 1, g6, 0xbf, -1, 32)
//                .addWires(g3, 0x401, 1, g6, 0xdf, -1, 32)
//                .addWires(g3, 0x421, 1, g6, 0xff, -1, 32)
                .build();
        BooleanFunction sha256Fn = (BooleanFunction) sha256Circuit.asFunction();

        SecureRandom rnd = new SecureRandom();
        byte[] inputBytes = new byte[64];
        rnd.nextBytes(inputBytes);
        BitVector inputs = BitVector.copyFrom(inputBytes);
        BitVector outputs = BitVector.copyFrom(sha256Fn.apply(inputs));

        System.out.println(outputs);
        //noinspection UnstableApiUsage
        System.out.println(Hashing.sha256().hashBytes(BitVector.copyFrom(xorFn.apply(inputs)).bytes()));
        System.out.println(sha256Fn.auxiliaryLength());
        System.out.println(sha256Fn.parityCheckTerms().size());

        Program program = new BooleanFunctionConverter().buildProgram(sha256Fn);
        System.out.println(program.gates().size());
        System.out.println(program.gates().stream().filter(g -> g.type() == Program.GateType.AND).count());

        System.out.println("Simplifying...");
        program = Simplifier.simplify(program);
        System.out.println(program.gates().size());
        System.out.println(program.gates().stream().filter(g -> g.type().ordinal() >= Program.GateType.AND.ordinal()).count());
//        program.gates().stream().limit(5000).forEach(System.out::println);

        byte[] secretOffsetBytes = new byte[16], aesKey = new byte[16];
        rnd.nextBytes(secretOffsetBytes);
        rnd.nextBytes(aesKey);
        secretOffsetBytes[0] |= -0x80;
        GarbledCircuit.Generator generator = new GarbledCircuit.Generator(
                program,
                GarbledBit.copyFrom(secretOffsetBytes),
                new GarbledCircuit.AesHashCipher(aesKey),
                BitVector.empty(),
                rnd,
                rnd
        );
        Stopwatch stopwatch = Stopwatch.createStarted();
        GarbledCircuit circuit = generator.generate();
        System.out.println("Circuit generated in " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms.");
        stopwatch.reset().start();
        GarbledCircuit.Result result = circuit.run(generator.garbleInputs(inputs, 0));
        System.out.println("Circuit ran in " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + " ms.");
        System.out.println(result);
        System.out.println(generator.ungarbleOutputs(result.outputs()));
    }

    @SuppressWarnings("SameParameterValue")
    private static <E> AlgebraicFunction<E> constantFn(FiniteField<E> field, List<E> constantVector) {
        //noinspection UnstableApiUsage
        return AlgebraicFunction.builder(field).degree(1).inputLength(0).auxiliaryLength(0).outputLength(constantVector.size())
                .baseFn(v -> constantVector)
                .parityCheckTerms(Streams.mapWithIndex(constantVector.stream(), (x, i) ->
                        BasePolynomialExpression.<E>variable((int) i).add(BasePolynomialExpression.constant(field.negative(x))))
                        .collect(ImmutableList.toImmutableList()))
                .build();
    }

    private static BooleanFunction unusedOutputsFn() {
        return BooleanFunction.builder().inputLength(833).degree(-1).parityCheckTerms(ImmutableList.of()).simpleBaseFn().build();
    }
}
