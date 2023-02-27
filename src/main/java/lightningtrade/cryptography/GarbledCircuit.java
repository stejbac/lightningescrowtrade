package lightningtrade.cryptography;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import lightningtrade.cryptography.Program.BinaryGate;
import lightningtrade.cryptography.Program.Gate;
import lightningtrade.cryptography.Program.GateType;
import lightningtrade.cryptography.Program.UnaryGate;

import javax.annotation.Nullable;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static lightningtrade.cryptography.GarbledBit.zero;

public class GarbledCircuit {
    private final Program program;
    private final List<BitVector> garbledGateDataList;
    private final Cipher cipher;
    // TODO: Should we make 'inputs' part of 'WorkingState' (making it a lazy GarbledBitVector in the Generator case)?
    private transient GarbledBitVector inputs;
    private transient BitVector currentGarbledGateData;
    private transient final WorkingState workingState = new WorkingState();

    private GarbledCircuit(Program program, List<BitVector> garbledGateDataList, Cipher cipher) {
        this.program = program;
        this.garbledGateDataList = garbledGateDataList;
        this.cipher = cipher;
    }

    public Result run(GarbledBitVector inputs) {
        this.inputs = inputs;
        program.gates().forEach(gate -> {
            currentGarbledGateData = this.garbledGateDataList.get(gate.index());
            GarbledBit output = evaluate(gate);
            put(workingState.knownGarbledBits, gate, output);
            if (gate.isOutputKnownToEvaluator()) {
                put(workingState.knownBits, gate, output.selectBit() ^ currentGarbledGateData.get(0));
            }
        });
        var outputs = program.ungarbledOutputIndices().stream()
                .map(workingState.knownBits::get)
                .collect(BitVector.toBitVector());
        var garbledOutputs = program.outputIndices().stream()
                .map(workingState.knownGarbledBits::get)
                .collect(GarbledBitVector.toGarbledBitVector());
        return new AutoValue_GarbledCircuit_Result(outputs, garbledOutputs);
    }

    public Result lazyRun(GarbledBitVector inputs) {
        this.inputs = inputs;
        throw new UnsupportedOperationException();
    }

    private GarbledBit evaluate(Gate gate) {
        BinaryGate binaryGate;
        Gate input1, input2;
        switch (gate.type()) {
            case INPUT:
            case RANDOM:
            case FALSE:
            case TRUE:
                if (gate.isOutputKnownToGenerator()) {
                    return zero();
                }
                return inputs.get(program.inputIndices().get(gate.index()));
            case IDENTITY:
            case NOT:
                return get(workingState.knownGarbledBits, ((UnaryGate) gate).input());
            case XOR:
            case XNOR:
                binaryGate = (BinaryGate) gate;
                input1 = binaryGate.firstInput();
                input2 = binaryGate.secondInput();
                return get(workingState.knownGarbledBits, input1).xor(get(workingState.knownGarbledBits, input2));
            default:
                binaryGate = (BinaryGate) gate;
                input1 = binaryGate.firstInput();
                input2 = binaryGate.secondInput();
                if (gate.isOutputKnownToGenerator() && gate.isOutputKnownToEvaluator()) {
                    return zero();
                }
                if (input1.isOutputKnownToGenerator() || input2.isOutputKnownToGenerator()) {
                    return evaluateHalfAndGate_GeneratorKnowsInput(binaryGate);
                }
                if (input1.isOutputKnownToEvaluator() || input2.isOutputKnownToEvaluator()) {
                    return evaluateHalfAndGate_EvaluatorKnowsInput(binaryGate);
                }
                return evaluateFullAndGate(binaryGate);
        }
    }

    private GarbledBit evaluateHalfAndGate_GeneratorKnowsInput(BinaryGate gate) {
        boolean conditionOnFirstInput = gate.firstInput().isOutputKnownToGenerator();
        var key = get(workingState.knownGarbledBits, conditionOnFirstInput ? gate.secondInput() : gate.firstInput());
        // FIXME: We should treat 'key.selectBit()' as secret - make constant-time:
        var ciphertext = key.selectBit() ? BitVector.copyFrom(new byte[16]) : currentGarbledGateData.subList(0, 128);
        currentGarbledGateData = currentGarbledGateData.subList(128, currentGarbledGateData.size());
        return cipher.decrypt(key, ciphertext, gate.index(), gate.subIndex());
    }

    private GarbledBit evaluateHalfAndGate_EvaluatorKnowsInput(BinaryGate gate) {
        boolean conditionOnFirstInput = gate.firstInput().isOutputKnownToEvaluator();
        boolean isErasure;
        GarbledBit key, otherInput;
        if (conditionOnFirstInput) {
            isErasure = get(workingState.knownBits, gate.firstInput()) == gate.isFirstInputNegated();
            key = get(workingState.knownGarbledBits, gate.firstInput());
            otherInput = get(workingState.knownGarbledBits, gate.secondInput());
        } else {
            isErasure = get(workingState.knownBits, gate.secondInput()) == gate.isSecondInputNegated();
            key = get(workingState.knownGarbledBits, gate.secondInput());
            otherInput = get(workingState.knownGarbledBits, gate.firstInput());
        }
        var ciphertext = currentGarbledGateData.subList(0, 128);
        currentGarbledGateData = currentGarbledGateData.subList(128, currentGarbledGateData.size());
        // FIXME: We should treat 'isErasure' as secret - make constant-time:
        return isErasure
                ? cipher.decrypt(key, BitVector.copyFrom(new byte[16]), gate.index(), gate.subIndex())
                : cipher.decrypt(key, ciphertext, gate.index(), gate.subIndex()).xor(otherInput);
    }

    private GarbledBit evaluateFullAndGate(BinaryGate gate) {
        workingState.knownBits.put(-2, get(workingState.knownGarbledBits, gate.secondInput()).selectBit());
        gate.subGates().forEach(subGate -> put(workingState.knownGarbledBits, subGate, evaluate(subGate)));
        return get(workingState.knownGarbledBits, gate);
    }

    private static int index(Gate gate) {
        return gate.subIndex() > 0 ? -gate.subIndex() : gate.index();
    }

    private static <V> V get(Map<Integer, V> map, Gate gate) {
        return map.get(index(gate));
    }

    private static <V> void put(Map<Integer, V> map, Gate gate, V value) {
        map.put(index(gate), value);
    }

    public static class Generator {
        private final Program program;
        private final GarbledBit secretOffset;
        private final Cipher cipher;
        private transient final BooleanSupplier inputSource;
        private transient final PushbackBooleanSupplier randomSource;
        private transient final Supplier<GarbledBit> garbledBitSource;
        private transient final WorkingState workingState = new WorkingState();

        public Generator(Program program, GarbledBit secretOffset, Cipher cipher, BitVector inputs,
                         @Nullable SecureRandom randomRnd, @Nullable SecureRandom garbledBitRnd) {
            checkArgument(secretOffset.selectBit(), "Select bit of secret offset must be set");
            this.program = program;
            this.secretOffset = secretOffset;
            this.cipher = cipher;
            this.inputSource = inputs.iterator()::nextBoolean;
            this.randomSource = new PushbackBooleanSupplier(() -> checkNotNull(randomRnd).nextBoolean());
            this.garbledBitSource = () -> nextGarbledBit(checkNotNull(garbledBitRnd));
        }

        private static GarbledBit nextGarbledBit(SecureRandom rnd) {
            byte[] bytes = new byte[16];
            rnd.nextBytes(bytes);
            return GarbledBit.copyFrom(bytes);
        }

        public GarbledCircuit generate() {
            //noinspection UnstableApiUsage
            var builder = ImmutableList.<BitVector>builderWithExpectedSize(program.gates().size());
            program.gates().forEach(gate -> {
                var ciphertext = garbleGate(gate);
                if (gate.isOutputKnownToEvaluator()) {
                    // TODO: Avoid writing the select bit in the case that the output is deducible (by the evaluator) from the inputs.
                    var garbledZero = get(workingState.knownGarbledBits, gate);
                    ciphertext = ciphertext.concat(BitVector.singleton(garbledZero.selectBit()));
                }
                builder.add(ciphertext);
            });
            return new GarbledCircuit(program, builder.build(), cipher);
        }

        private GarbledBit secretOffsetTimes(boolean x) {
            // FIXME: Need to make sure this is constant-time - should probably return a byte array or NEW GarbledBit:
            GarbledBit zero = zero();
            return x ? secretOffset : zero;
        }

        private BitVector garbleGate(Gate gate) {
            var garbledBits = workingState.knownGarbledBits;
            var bits = workingState.knownBits;
            var outputOffset = gate.isOutputNegated() ? secretOffset : zero();
            BinaryGate binaryGate;
            UnaryGate unaryGate;
            Gate input, input1, input2;

            switch (gate.type()) {
                case INPUT:
                case RANDOM:
                    if (gate.isOutputKnownToGenerator()) {
                        boolean outputBit = (gate.type() == GateType.INPUT ? inputSource : randomSource).getAsBoolean();
                        put(garbledBits, gate, secretOffsetTimes(outputBit));
                        put(bits, gate, outputBit);
                    } else {
                        put(garbledBits, gate, garbledBitSource.get());
                    }
                    return BitVector.empty();
                case FALSE:
                case TRUE:
                    put(garbledBits, gate, outputOffset);
                    put(bits, gate, outputOffset.selectBit());
                    return BitVector.empty();
                case IDENTITY:
                case NOT:
                    unaryGate = (UnaryGate) gate;
                    input = unaryGate.input();
                    put(garbledBits, gate, get(garbledBits, input).xor(outputOffset));
                    if (gate.isOutputKnownToGenerator()) {
                        put(bits, gate, unaryGate.apply(get(bits, input)));
                    }
                    return BitVector.empty();
                case XOR:
                case XNOR:
                    binaryGate = (BinaryGate) gate;
                    input1 = binaryGate.firstInput();
                    input2 = binaryGate.secondInput();
                    put(garbledBits, gate, get(garbledBits, input1).xor(get(garbledBits, input2)).xor(outputOffset));
                    if (gate.isOutputKnownToGenerator()) {
                        put(bits, gate, binaryGate.apply(get(bits, input1), get(bits, input2)));
                    }
                    return BitVector.empty();
                default:
                    binaryGate = (BinaryGate) gate;
                    input1 = binaryGate.firstInput();
                    input2 = binaryGate.secondInput();
                    if (gate.isOutputKnownToGenerator()) {
                        put(bits, gate, binaryGate.apply(get(bits, input1), get(bits, input2)));
                        if (gate.isOutputKnownToEvaluator()) {
                            return garbleTrivialAndGate(binaryGate);
                        }
                    }
                    if (input1.isOutputKnownToGenerator() || input2.isOutputKnownToGenerator()) {
                        return garbleHalfAndGate_GeneratorKnownInput(binaryGate);
                    }
                    if (input1.isOutputKnownToEvaluator() || input2.isOutputKnownToEvaluator()) {
                        return garbleHalfAndGate_EvaluatorKnownInput(binaryGate);
                    }
                    return garbleFullAndGate(binaryGate);
            }
        }

        private BitVector garbleTrivialAndGate(BinaryGate gate) {
            boolean x = get(workingState.knownBits, gate.firstInput());
            boolean y = get(workingState.knownBits, gate.secondInput());
            put(workingState.knownGarbledBits, gate, secretOffsetTimes(gate.apply(x, y)));
            return BitVector.empty();
        }

        private BitVector garbleHalfAndGate_GeneratorKnownInput(BinaryGate gate) {
            boolean z0, z1;
            GarbledBit key0;
            int index = gate.index(), subIndex = gate.subIndex();
            boolean conditionOnFirstInput = gate.firstInput().isOutputKnownToGenerator();
            if (conditionOnFirstInput) {
                boolean x = get(workingState.knownBits, gate.firstInput());
                z0 = gate.apply(x, false);
                z1 = gate.apply(x, true);
                key0 = get(workingState.knownGarbledBits, gate.secondInput());
            } else {
                boolean y = get(workingState.knownBits, gate.secondInput());
                z0 = gate.apply(false, y);
                z1 = gate.apply(true, y);
                key0 = get(workingState.knownGarbledBits, gate.firstInput());
            }
            GarbledBit key1 = key0.xor(secretOffset);
            GarbledBit plaintext;
            BitVector ciphertext;
            // FIXME: Secret-dependent branch:
            if (key0.selectBit()) {
                plaintext = cipher.decrypt(key0, BitVector.copyFrom(new byte[16]), index, subIndex).xor(secretOffsetTimes(z0 ^ z1));
                ciphertext = cipher.encrypt(key1, plaintext, index, subIndex);
                put(workingState.knownGarbledBits, gate, plaintext.xor(secretOffsetTimes(z1)));
            } else {
                plaintext = cipher.decrypt(key1, BitVector.copyFrom(new byte[16]), index, subIndex).xor(secretOffsetTimes(z0 ^ z1));
                ciphertext = cipher.encrypt(key0, plaintext, index, subIndex);
                put(workingState.knownGarbledBits, gate, plaintext.xor(secretOffsetTimes(z0)));
            }
            return ciphertext;
        }

        private BitVector garbleHalfAndGate_EvaluatorKnownInput(BinaryGate gate) {
            GarbledBit firstInputOffset = gate.isFirstInputNegated() ? secretOffset : zero();
            GarbledBit secondInputOffset = gate.isSecondInputNegated() ? secretOffset : zero();
            GarbledBit outputOffset = gate.isOutputNegated() ? secretOffset : zero();
            GarbledBit keyZ, keyI, newGarbledZero, plaintext;
            BitVector ciphertext;
            int index = gate.index(), subIndex = gate.subIndex();
            boolean conditionOnFirstInput = gate.firstInput().isOutputKnownToEvaluator();
            // TODO: Deduplicate:
            if (conditionOnFirstInput) {
                keyZ = get(workingState.knownGarbledBits, gate.firstInput()).xor(firstInputOffset);
                keyI = keyZ.xor(secretOffset);
                newGarbledZero = cipher.decrypt(keyZ, BitVector.copyFrom(new byte[16]), index, subIndex).xor(outputOffset);
                plaintext = newGarbledZero.xor(get(workingState.knownGarbledBits, gate.secondInput())).xor(secondInputOffset);
                ciphertext = cipher.encrypt(keyI, plaintext, index, subIndex);
                put(workingState.knownGarbledBits, gate, newGarbledZero);
            } else {
                keyZ = get(workingState.knownGarbledBits, gate.secondInput()).xor(secondInputOffset);
                keyI = keyZ.xor(secretOffset);
                newGarbledZero = cipher.decrypt(keyZ, BitVector.copyFrom(new byte[16]), index, subIndex).xor(outputOffset);
                plaintext = newGarbledZero.xor(get(workingState.knownGarbledBits, gate.firstInput())).xor(firstInputOffset);
                ciphertext = cipher.encrypt(keyI, plaintext, index, subIndex);
                put(workingState.knownGarbledBits, gate, newGarbledZero);
            }
            return ciphertext;
        }

        private BitVector garbleFullAndGate(BinaryGate gate) {
            randomSource.pushBack(get(workingState.knownGarbledBits, gate.secondInput()).selectBit());
            // NOTE: Stateful map function:
            return gate.subGates().stream().map(this::garbleGate).reduce(BitVector.empty(), BitVector::concat);
        }

        public GarbledBit garbleInput(boolean input, int index) {
            // TODO: Make sure this Generator is initialised, to populate 'workingState'.
            return workingState.knownGarbledBits.get(program.inputIndices().get(index)).xor(secretOffsetTimes(input));
        }

        public ObliviousFunction<Boolean, GarbledBit> garbleInputFn(int index) {
            return ObliviousFunction.liftSimple(input -> garbleInput(input, index));
        }

        public ObliviousFunction<BitVector, GarbledBitVector> garbleInputsFn(int startIndex, int endIndex) {
            var obliviousFns = IntStream.range(startIndex, endIndex)
                    .mapToObj(this::garbleInputFn)
                    .collect(Collectors.toUnmodifiableList());
            return ObliviousFunction.product(v -> v, GarbledBitVector::copyFrom, Boolean.class, obliviousFns);
        }

        public GarbledBitVector garbleInputs(BitVector inputs, int startIndex) {
            return garbleInputsFn(startIndex, startIndex + inputs.size()).apply(inputs);
        }

        public ObliviousResponse<GarbledBitVector> garbleInputs(ObliviousRequest<BitVector> inputs, int startIndex) {
            int size = ((ObliviousRequest.Compound<?>) inputs).subRequests().size();
            return garbleInputsFn(startIndex, startIndex + size).apply(inputs);
        }

        public Boolean ungarbleOutput(GarbledBit garbledOutput, int index) {
            var garbledZero = workingState.knownGarbledBits.get(program.outputIndices().get(index));
            // FIXME: This should probably be constant-time:
            return garbledOutput.equals(garbledZero) ? Boolean.FALSE :
                    garbledOutput.equals(garbledZero.xor(secretOffset)) ? Boolean.TRUE : null;
        }

        private boolean ungarbleOutputAsBoolean(GarbledBit garbledOutput, long index) {
            Boolean result = ungarbleOutput(garbledOutput, (int) index);
            if (result == null) {
                throw new IllegalArgumentException("Invalid garbled output " + garbledOutput + " at index " + index);
            }
            return result;
        }

        public BitVector ungarbleOutputs(GarbledBitVector garbledOutputs) {
            //noinspection UnstableApiUsage
            return Streams.mapWithIndex(garbledOutputs.stream(), this::ungarbleOutputAsBoolean)
                    .collect(BitVector.toBitVector());
        }
    }

    @AutoValue
    public static abstract class Result {
        public abstract BitVector ungarbledOutputs();

        public abstract GarbledBitVector outputs();
    }

    private static class WorkingState {
        final Map<Integer, GarbledBit> knownGarbledBits = new HashMap<>();
        final Map<Integer, Boolean> knownBits = new HashMap<>();
    }

    private static class PushbackBooleanSupplier implements BooleanSupplier {
        private final Deque<Boolean> pushedBooleans = new ArrayDeque<>();
        private final BooleanSupplier delegate;

        PushbackBooleanSupplier(BooleanSupplier delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean getAsBoolean() {
            return !pushedBooleans.isEmpty() ? pushedBooleans.pop() : delegate.getAsBoolean();
        }

        void pushBack(boolean b) {
            pushedBooleans.push(b);
        }
    }

    interface Cipher {
        BitVector encrypt(GarbledBit garbledInput, GarbledBit garbledOutput, long gateIndex, long gateSubIndex);

        GarbledBit decrypt(GarbledBit garbledInput, BitVector ciphertext, long gateIndex, long gateSubIndex);
    }

    abstract static class HashCipher implements Cipher {
        protected abstract byte[] hash(byte[] bytes);

        private GarbledBit hashedInputAndIndex(GarbledBit garbledInput, long gateIndex, long gateSubIndex) {
            var bytes = garbledInput.bytes();
            var longBuffer = ByteBuffer.wrap(bytes).asLongBuffer();
            longBuffer.put(0, longBuffer.get(0) ^ gateIndex);
            longBuffer.put(1, longBuffer.get(1) ^ gateSubIndex);
            return new GarbledBit(hash(bytes));
        }

        @Override
        public BitVector encrypt(GarbledBit garbledInput, GarbledBit garbledOutput, long gateIndex, long gateSubIndex) {
            return new BitVector.ImmutableArrayBitVector(
                    garbledOutput.xor(hashedInputAndIndex(garbledInput, gateIndex, gateSubIndex)).bytes(), 0, 128
            );
        }

        @Override
        public GarbledBit decrypt(GarbledBit garbledInput, BitVector ciphertext, long gateIndex, long gateSubIndex) {
            return new GarbledBit(ciphertext.bytes()).xor(hashedInputAndIndex(garbledInput, gateIndex, gateSubIndex));
        }
    }

    // NOTE: Not thread-safe:
    static class AesHashCipher extends HashCipher {
        private final javax.crypto.Cipher jceCipher;

        AesHashCipher(byte[] aesKey) {
            checkArgument(aesKey.length == 16, "Expected: a 16-byte AES key");
            try {
                jceCipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
                jceCipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
            } catch (GeneralSecurityException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        protected byte[] hash(byte[] bytes) {
            // NOTE: Davies-Meyer construction on its own is insecure - need to modify the input before XORing it onto the output.
            var result = jceCipher.update(bytes);
            for (int i = 0; i < 16; i++) {
                result[i] ^= shift(bytes[i]);
            }
            return result;
        }

        // Multiplication by 0b10 in the AES field (period 51):
        @VisibleForTesting
        static byte shift(byte x) {
            return (byte) (x << 1 ^ x >> 8 & 0b100011011);
        }
    }
}
