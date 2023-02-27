package lightningtrade.cryptography;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import java.util.*;
import java.util.stream.IntStream;

public class Program {
    private final List<GateData> rawGates;
    private final List<Gate> gates;
    private final List<Integer> inputIndices;
    private final List<Integer> ungarbledOutputIndices;
    private final List<Integer> outputIndices;

    Program(Iterable<GateData> rawGates, Iterable<Integer> outputIndices) {
        this.rawGates = ImmutableList.copyOf(rawGates);
        gates = transformWithIndex(this.rawGates, this::newGate);
        inputIndices = gates.stream()
                .filter(gate -> gate.type() == GateType.INPUT && !gate.isOutputKnownToGenerator())
                .map(Gate::index)
                .collect(ImmutableList.toImmutableList());
        this.outputIndices = ImmutableList.copyOf(outputIndices);
        ungarbledOutputIndices = this.outputIndices.stream()
                .filter(i -> gates.get(i).isOutputKnownToEvaluator())
                .collect(ImmutableList.toImmutableList());
    }

    List<GateData> rawGates() {
        return rawGates;
    }

    public List<Gate> gates() {
        return gates;
    }

    public List<Integer> inputIndices() {
        return inputIndices;
    }

    public List<Integer> ungarbledOutputIndices() {
        return ungarbledOutputIndices;
    }

    public List<Integer> outputIndices() {
        return outputIndices;
    }

    private interface FunctionWithIndex<F, T> {
        T apply(F value, int index);
    }

    private static <F, T> List<T> transformWithIndex(List<F> list, FunctionWithIndex<F, T> fn) {
        return new AbstractList<T>() {
            @Override
            public T get(int index) {
                return fn.apply(list.get(index), index);
            }

            @Override
            public int size() {
                return list.size();
            }
        };
    }

    private Gate getGate(int index, int inputOffset) {
        return inputOffset > 0
                ? gates.get(index - inputOffset)
                : newGate(rawGates.get(index).splitNonlinearGate().get(-inputOffset), index, -inputOffset);
    }

    private Gate newGate(GateData gateData, int index) {
        return newGate(gateData, index, 0);
    }

    private Gate newGate(GateData gateData, int index, int subIndex) {
        switch (gateData.type()) {
            case INPUT:
            case RANDOM:
            case FALSE:
            case TRUE:
                return new Source(index, subIndex, gateData);
            case IDENTITY:
            case NOT:
                return new UnaryGate(index, subIndex, gateData);
            default:
                return new BinaryGate(index, subIndex, gateData);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<GateData> rawGates = new ArrayList<>();
        private final List<GateData> unmodifiableRawGates = Collections.unmodifiableList(rawGates);
        private final Map<GateData, GateData> cache = new HashMap<>();
        private List<Integer> outputIndices;

        private Builder() {
        }

        public List<GateData> rawGates() {
            return unmodifiableRawGates;
        }

        public Builder outputIndices(Iterable<Integer> outputIndices) {
            this.outputIndices = ImmutableList.copyOf(outputIndices);
            return this;
        }

        public Builder addGate(GateData rawGate) {
            cache.putIfAbsent(rawGate, rawGate);

            int[] inputOffsets = Ints.toArray(rawGate.inputOffsets());
            OutputScope minOutputScope = Arrays.stream(inputOffsets)
                    .mapToObj(i -> rawGates.get(rawGates.size() - i).outputScope())
                    .reduce(minSourceOutputScope(rawGate.type()), OutputScope::join);

            rawGate = GateData.create(rawGate.type(), rawGate.outputScope().meet(minOutputScope), inputOffsets);
            rawGates.add(Objects.requireNonNullElse(cache.putIfAbsent(rawGate, rawGate), rawGate));
            return this;
        }

        public Builder addAllGates(Iterable<GateData> rawGates) {
            rawGates.forEach(this::addGate);
            return this;
        }

        public Program build() {
            return new Program(rawGates, outputIndices);
        }

        private static OutputScope minSourceOutputScope(GateType type) {
            return type == GateType.INPUT || type == GateType.RANDOM ? OutputScope.KNOWN_TO_NEITHER : OutputScope.KNOWN_TO_BOTH;
        }
    }

    @AutoValue
    public abstract static class GateData {
        static final GateData FALSE = create(GateType.FALSE, OutputScope.KNOWN_TO_BOTH);
        static final GateData TRUE = create(GateType.TRUE, OutputScope.KNOWN_TO_BOTH);

        public abstract GateType type();

        public abstract OutputScope outputScope();

        public abstract List<Integer> inputOffsets();

        static GateData create(GateType type, OutputScope outputScope, int... inputOffsets) {
            return new AutoValue_Program_GateData(type, outputScope, Ints.asList(inputOffsets));
        }

        private List<GateData> splitInto(GateType type1, GateType type2) {
            int a = inputOffsets().get(0), b = inputOffsets().get(1);
            return ImmutableList.of(
                    create(GateType.RANDOM, OutputScope.KNOWN_TO_GENERATOR),
                    create(type1, OutputScope.KNOWN_TO_NEITHER, a, -4),
                    create(GateType.XOR, OutputScope.KNOWN_TO_EVALUATOR, b, -4),
                    create(type2, OutputScope.KNOWN_TO_NEITHER, a, -2),
                    create(GateType.XOR, OutputScope.KNOWN_TO_NEITHER, -1, -3)
            ).reverse();
        }

        @Memoized
        List<GateData> splitNonlinearGate() {
            switch (type()) {
                case AND:
                    return splitInto(GateType.AND, GateType.AND);
                case NOT_AND:
                    return splitInto(GateType.NOT_AND, GateType.NOT_AND);
                case AND_NOT:
                    return splitInto(GateType.AND, GateType.AND_NOT);
                case NOR:
                    return splitInto(GateType.NOT_AND, GateType.NOR);
                case NAND:
                    return splitInto(GateType.NAND, GateType.AND);
                case OR_NOT:
                    return splitInto(GateType.OR_NOT, GateType.NOT_AND);
                case NOT_OR:
                    return splitInto(GateType.NAND, GateType.AND_NOT);
                case OR:
                    return splitInto(GateType.OR_NOT, GateType.NOR);
                default:
                    throw new IllegalArgumentException();
            }
        }
    }

    public abstract static class Gate {
        private final int index;
        private final int subIndex;
        private final GateData gateData;

        Gate(int index, int subIndex, GateData gateData) {
            this.index = index;
            this.subIndex = subIndex;
            this.gateData = gateData;
        }

        public int index() {
            return index;
        }

        int subIndex() {
            return subIndex;
        }

        public GateType type() {
            return gateData.type();
        }

        public OutputScope outputScope() {
            return gateData.outputScope();
        }

        List<Integer> inputOffsets() {
            return gateData.inputOffsets();
        }

        boolean isOutputNegated() {
            switch (type()) {
                case TRUE:
                case NOT:
                case XNOR:
                case NAND:
                case OR_NOT:
                case NOT_OR:
                case OR:
                    return true;
                default:
                    return false;
            }
        }

        public boolean isOutputKnownToGenerator() {
            return outputScope() == OutputScope.KNOWN_TO_GENERATOR || outputScope() == OutputScope.KNOWN_TO_BOTH;
        }

        public boolean isOutputKnownToEvaluator() {
            return outputScope() == OutputScope.KNOWN_TO_EVALUATOR || outputScope() == OutputScope.KNOWN_TO_BOTH;
        }

        @Override
        public String toString() {
            return subIndex > 0
                    ? getClass().getSimpleName() + "{index=" + index + ",subIndex=" + subIndex + ",gateData=" + gateData + "}"
                    : getClass().getSimpleName() + "{index=" + index + ",gateData=" + gateData + "}";
        }
    }

    public static class Source extends Gate {
        Source(int index, int subIndex, GateData gateData) {
            super(index, subIndex, gateData);
        }
    }

    public class UnaryGate extends Gate {
        UnaryGate(int index, int subIndex, GateData gateData) {
            super(index, subIndex, gateData);
        }

        public Gate input() {
            return getGate(index(), inputOffsets().get(0));
        }

        boolean apply(boolean x) {
            return x ^ isOutputNegated();
        }
    }

    public class BinaryGate extends Gate {
        BinaryGate(int index, int subIndex, GateData gateData) {
            super(index, subIndex, gateData);
        }

        public Gate firstInput() {
            return getGate(index(), inputOffsets().get(0));
        }

        public Gate secondInput() {
            return getGate(index(), inputOffsets().get(1));
        }

        boolean isLinear() {
            return type() == GateType.XOR || type() == GateType.XNOR;
        }

        boolean isFirstInputNegated() {
            switch (type()) {
                case NOT_AND:
                case NOR:
                case OR_NOT:
                case OR:
                    return true;
                default:
                    return false;
            }
        }

        boolean isSecondInputNegated() {
            switch (type()) {
                case AND_NOT:
                case NOR:
                case NOT_OR:
                case OR:
                    return true;
                default:
                    return false;
            }
        }

        boolean apply(boolean x, boolean y) {
            return isLinear() ? x ^ y ^ isOutputNegated() :
                    ((x ^ isFirstInputNegated()) & (y ^ isSecondInputNegated())) ^ isOutputNegated();
        }

        List<Gate> subGates() {
            return IntStream.range(-4, 1)
                    .mapToObj(i -> getGate(index(), i))
                    .collect(ImmutableList.toImmutableList());
        }
    }

    public enum GateType {
        INPUT, RANDOM, FALSE, TRUE, IDENTITY, NOT, XOR, XNOR, AND, NOT_AND, AND_NOT, NOR, NAND, OR_NOT, NOT_OR, OR
    }

    public enum OutputScope {
        KNOWN_TO_NEITHER, KNOWN_TO_GENERATOR, KNOWN_TO_EVALUATOR, KNOWN_TO_BOTH;

        public OutputScope join(OutputScope other) {
            return values()[ordinal() & other.ordinal()];
        }

        public OutputScope meet(OutputScope other) {
            return values()[ordinal() | other.ordinal()];
        }
    }
}
