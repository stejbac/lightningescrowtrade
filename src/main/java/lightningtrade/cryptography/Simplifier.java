package lightningtrade.cryptography;

import com.google.common.primitives.Ints;
import lightningtrade.cryptography.Program.GateData;

import javax.annotation.Nullable;
import java.util.*;

import static lightningtrade.cryptography.Program.GateType.*;
import static lightningtrade.cryptography.Program.OutputScope.KNOWN_TO_BOTH;

public class Simplifier {
    private Simplifier() {
    }

    public static Program simplify(Program program) {
        return removeUnusedGates(simplifyGates(program));
    }

    private static Program simplifyGates(Program program) {
        List<GateData> rawGates = new ArrayList<>(program.rawGates());
        for (int i = 0; i < rawGates.size(); i++) {
            rawGates.set(i, simplify(rawGates.get(i), i, rawGates));
        }
        return new Program(rawGates, program.outputIndices());
    }

    private static GateData simplify(GateData rawGate, @Nullable Boolean firstInput) {
        if (firstInput == null) {
            return rawGate;
        }
        switch (rawGate.type()) {
            case IDENTITY:
                return firstInput ? GateData.TRUE : GateData.FALSE;
            case NOT:
                return GateData.create(firstInput ? FALSE : TRUE, KNOWN_TO_BOTH);
            case XOR:
                return GateData.create(firstInput ? NOT : IDENTITY, rawGate.outputScope(), rawGate.inputOffsets().get(1));
            case XNOR:
                return GateData.create(firstInput ? IDENTITY : NOT, rawGate.outputScope(), rawGate.inputOffsets().get(1));
            case AND:
                return firstInput ? GateData.create(IDENTITY, rawGate.outputScope(), rawGate.inputOffsets().get(1)) : GateData.FALSE;
            case NOT_AND:
                return firstInput ? GateData.FALSE : GateData.create(IDENTITY, rawGate.outputScope(), rawGate.inputOffsets().get(1));
            case AND_NOT:
                return firstInput ? GateData.create(NOT, rawGate.outputScope(), rawGate.inputOffsets().get(1)) : GateData.FALSE;
            case NOR:
                return firstInput ? GateData.FALSE : GateData.create(NOT, rawGate.outputScope(), rawGate.inputOffsets().get(1));
            case NAND:
                return firstInput ? GateData.create(NOT, rawGate.outputScope(), rawGate.inputOffsets().get(1)) : GateData.TRUE;
            case OR_NOT:
                return firstInput ? GateData.TRUE : GateData.create(NOT, rawGate.outputScope(), rawGate.inputOffsets().get(1));
            case NOT_OR:
                return firstInput ? GateData.create(IDENTITY, rawGate.outputScope(), rawGate.inputOffsets().get(1)) : GateData.TRUE;
            case OR:
                return firstInput ? GateData.TRUE : GateData.create(IDENTITY, rawGate.outputScope(), rawGate.inputOffsets().get(1));
            default:
                return rawGate;
        }
    }

    private static GateData simplify(GateData rawGate, @Nullable Boolean firstInput, @Nullable Boolean secondInput) {
        rawGate = simplify(rawGate, firstInput);
        if (secondInput == null) {
            return rawGate;
        }
        switch (rawGate.type()) {
            case IDENTITY:
                return secondInput ? GateData.TRUE : GateData.FALSE;
            case NOT:
                return secondInput ? GateData.FALSE : GateData.TRUE;
            case XOR:
                return GateData.create(secondInput ? NOT : IDENTITY, rawGate.outputScope(), rawGate.inputOffsets().get(0));
            case XNOR:
                return GateData.create(secondInput ? IDENTITY : NOT, rawGate.outputScope(), rawGate.inputOffsets().get(0));
            case AND:
                return secondInput ? GateData.create(IDENTITY, rawGate.outputScope(), rawGate.inputOffsets().get(0)) : GateData.FALSE;
            case NOT_AND:
                return secondInput ? GateData.create(NOT, rawGate.outputScope(), rawGate.inputOffsets().get(0)) : GateData.FALSE;
            case AND_NOT:
                return secondInput ? GateData.FALSE : GateData.create(IDENTITY, rawGate.outputScope(), rawGate.inputOffsets().get(0));
            case NOR:
                return secondInput ? GateData.FALSE : GateData.create(NOT, rawGate.outputScope(), rawGate.inputOffsets().get(0));
            case NAND:
                return secondInput ? GateData.create(NOT, rawGate.outputScope(), rawGate.inputOffsets().get(0)) : GateData.TRUE;
            case OR_NOT:
                return secondInput ? GateData.create(IDENTITY, rawGate.outputScope(), rawGate.inputOffsets().get(0)) : GateData.TRUE;
            case NOT_OR:
                return secondInput ? GateData.TRUE : GateData.create(NOT, rawGate.outputScope(), rawGate.inputOffsets().get(0));
            case OR:
                return secondInput ? GateData.TRUE : GateData.create(IDENTITY, rawGate.outputScope(), rawGate.inputOffsets().get(0));
            default:
                return rawGate;
        }
    }

    private static GateData mergeInputs(GateData rawGate) {
        switch (rawGate.type()) {
            case XOR:
            case NOT_AND:
            case AND_NOT:
                return GateData.FALSE;
            case XNOR:
            case OR_NOT:
            case NOT_OR:
                return GateData.TRUE;
            case AND:
            case OR:
                return GateData.create(IDENTITY, rawGate.outputScope(), rawGate.inputOffsets().get(0));
            case NOR:
            case NAND:
                return GateData.create(NOT, rawGate.outputScope(), rawGate.inputOffsets().get(0));
            default:
                throw new AssertionError(); // unreachable
        }
    }

    private static GateData simplify(GateData rawGate, int index, List<GateData> rawGates) {
        for (int i = 0; i < rawGate.inputOffsets().size(); i++) {
            int inputIndex = index - rawGate.inputOffsets().get(i);
            GateData input = rawGates.get(inputIndex);

            // Make sure not to slide back an input if it would reduce its OutputScope. TODO: Exercise with a unit test.
            if (input.inputOffsets().size() != 1 || !isFirstInputScopeAtLeastOutputScope(input, inputIndex, rawGates)) {
                continue;
            }

            if (input.type() == IDENTITY) {
                rawGate = moveBackInput(rawGate, i, input.inputOffsets().get(0));
            } else if (input.type() == NOT) {
                Program.GateType newType = i == 0 ? negateFirstInput(rawGate.type()) : negateSecondInput(rawGate.type());
                rawGate = GateData.create(newType, rawGate.outputScope(), Ints.toArray(rawGate.inputOffsets()));
                rawGate = moveBackInput(rawGate, i, input.inputOffsets().get(0));
            }
        }
        if (rawGate.inputOffsets().size() == 2 && rawGate.inputOffsets().get(0).equals(rawGate.inputOffsets().get(1))) {
            rawGate = mergeInputs(rawGate);
        }
        Boolean firstInput = rawGate.inputOffsets().isEmpty() ? null :
                typeToBoolean(rawGates.get(index - rawGate.inputOffsets().get(0)));
        Boolean secondInput = rawGate.inputOffsets().size() < 2 ? null :
                typeToBoolean(rawGates.get(index - rawGate.inputOffsets().get(1)));
        return simplify(rawGate, firstInput, secondInput);
    }

    private static boolean isFirstInputScopeAtLeastOutputScope(GateData rawGate, int index, List<GateData> rawGates) {
        GateData input = rawGates.get(index - rawGate.inputOffsets().get(0));
        return input.outputScope().join(rawGate.outputScope()) == rawGate.outputScope();
    }

    private static Boolean typeToBoolean(GateData rawGate) {
        return rawGate.type() == TRUE ? Boolean.TRUE : rawGate.type() == FALSE ? Boolean.FALSE : null;
    }

    private static GateData moveBackInput(GateData rawGate, int i, int delta) {
        if (delta == 0) {
            return rawGate;
        }
        int[] inputOffsets = Ints.toArray(rawGate.inputOffsets());
        inputOffsets[i] += delta;
        return GateData.create(rawGate.type(), rawGate.outputScope(), inputOffsets);
    }

    private static Program.GateType negateFirstInput(Program.GateType type) {
        switch (type) {
            case IDENTITY:
                return NOT;
            case NOT:
                return IDENTITY;
            case XOR:
                return XNOR;
            case XNOR:
                return XOR;
            case AND:
                return NOT_AND;
            case NOT_AND:
                return AND;
            case AND_NOT:
                return NOR;
            case NOR:
                return AND_NOT;
            case NAND:
                return OR_NOT;
            case OR_NOT:
                return NAND;
            case NOT_OR:
                return OR;
            case OR:
                return NOT_OR;
            default:
                throw new IllegalArgumentException("No unary or binary gate of type: " + type);
        }
    }

    private static Program.GateType negateSecondInput(Program.GateType type) {
        switch (type) {
            case XOR:
                return XNOR;
            case XNOR:
                return XOR;
            case AND:
                return AND_NOT;
            case NOT_AND:
                return NOR;
            case AND_NOT:
                return AND;
            case NOR:
                return NOT_AND;
            case NAND:
                return NOT_OR;
            case OR_NOT:
                return OR;
            case NOT_OR:
                return NAND;
            case OR:
                return OR_NOT;
            default:
                throw new IllegalArgumentException("No binary gate of type: " + type);
        }
    }

    private static Program removeUnusedGates(Program program) {
        List<GateData> rawGates = new ArrayList<>(program.rawGates());
        PriorityQueue<Wire> queue = new PriorityQueue<>(Comparator.comparing(w -> -w.startIndex));
        LinkedHashMap<Integer, Integer> outputIndexMap = new LinkedHashMap<>();

        program.outputIndices().forEach(i -> {
            outputIndexMap.put(i, null);
            queue.add(new Wire(i, rawGates.size(), -1));
        });

        int removed = 0;
        for (int i = rawGates.size(); i-- > 0; ) {
            if (!queue.isEmpty() && i == queue.element().startIndex) {
                GateData rawGate = rawGates.get(i);
                for (int j = 0; j < rawGate.inputOffsets().size(); j++) {
                    int offset = rawGate.inputOffsets().get(j);
                    queue.add(new Wire(i - offset, i + removed, j));
                    rawGate = moveBackInput(rawGate, j, removed);
                }
                rawGates.set(i + removed, rawGate);
            } else {
                removed++;
            }
            while (!queue.isEmpty() && i == queue.element().startIndex) {
                Wire wire = queue.remove();
                if (wire.endIndex < rawGates.size()) {
                    GateData rawGate = rawGates.get(wire.endIndex);
                    rawGates.set(wire.endIndex, moveBackInput(rawGate, wire.offsetIndex, -removed));
                } else {
                    outputIndexMap.put(i, i + removed);
                }
            }
        }

        int finalRemoved = removed;
        return Program.builder()
                .addAllGates(rawGates.subList(removed, rawGates.size()))
                .outputIndices(Ints.asList(outputIndexMap.values().stream().mapToInt(i -> i - finalRemoved).toArray()))
                .build();
    }

    private static class Wire {
        final int startIndex, endIndex, offsetIndex;

        Wire(int startIndex, int endIndex, int offsetIndex) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.offsetIndex = offsetIndex;
        }
    }
}
