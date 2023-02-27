package lightningtrade.cryptography;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import lightningtrade.cryptography.Program.GateData;
import lightningtrade.cryptography.Program.GateType;
import lightningtrade.cryptography.Program.OutputScope;
import securecompute.algebra.BooleanField;
import securecompute.algebra.Ring;
import securecompute.algebra.polynomial.BasePolynomialExpression;
import securecompute.algebra.polynomial.PolynomialExpression;
import securecompute.circuit.BooleanFunction;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static lightningtrade.cryptography.Program.GateType.*;
import static lightningtrade.cryptography.Program.OutputScope.KNOWN_TO_NEITHER;

public class BooleanFunctionConverter {
    private final Program.Builder programBuilder = Program.builder();
    private final List<Integer> variableGateIndices = new ArrayList<>();
    private IntFunction<OutputScope> outputScopeFn = i -> KNOWN_TO_NEITHER;
    private IntPredicate isRandomInputFn = i -> false;

    public BooleanFunctionConverter outputScopeFn(IntFunction<OutputScope> outputScopeFn) {
        this.outputScopeFn = outputScopeFn;
        return this;
    }

    public BooleanFunctionConverter isRandomInputFn(IntPredicate isRandomInputFn) {
        this.isRandomInputFn = isRandomInputFn;
        return this;
    }

    // TODO: Method can't be safely re-invoked - improve this API:
    public Program buildProgram(BooleanFunction fn) {
        for (int i = 0; i < fn.inputLength(); i++) {
            addRawGate(isRandomInputFn.test(i) ? RANDOM : INPUT);
            variableGateIndices.add(i);
        }
        var sortedExpressions = new ArrayList<PolynomialExpression<Boolean>>(Collections.nCopies(fn.length(), null));
        for (var expr : fn.parityCheckTerms()) {
            var index = checkLinearInLastVariable(expr);
            // Repeated constraints at any given index are assumed compatible and all but the last are ignored.
            sortedExpressions.set(index, removeVariable(expr, index));
        }
        for (var expr : sortedExpressions.subList(fn.inputLength(), fn.length())) {
            Preconditions.checkNotNull(expr,
                    "Last variable index set of the constraints must be gap free. Gap at index %s", variableGateIndices.size());
            variableGateIndices.add(emitGates(expr));
        }
        int outputStart = fn.inputLength() + fn.auxiliaryLength();
        return programBuilder.outputIndices(Ints.asList(
                IntStream.range(outputStart, outputStart + fn.outputLength())
                        .map(variableGateIndices::get)
                        .toArray()
        )).build();
    }

    private void addRawGate(GateType type, int... inputOffsets) {
        programBuilder.addGate(GateData.create(type, outputScopeFn.apply(variableGateIndices.size()), inputOffsets));
    }

    // FIXME: In fields of nonzero characteristic, taking partial derivatives is not a valid way to determine linearity in a given variable.
    private static int checkLinearInLastVariable(PolynomialExpression<Boolean> expr) {
        int lvIndex = variableIndices(expr).max()
                .orElseThrow(() -> new IllegalArgumentException("Unexpected constant expression"));
        var derivative = partialDerivative(expr, lvIndex, BooleanField.INSTANCE);
        var derivativeAsConstant = asConstant(derivative);
        Preconditions.checkNotNull(derivativeAsConstant,
                "Should be linear with constant gradient in last variable, but got variable gradient for %s", expr);
        Preconditions.checkArgument(derivativeAsConstant,
                "Should be linear with nonzero gradient in last variable, but got zero gradient for %s", expr);
        return lvIndex;
    }

    private static Boolean asConstant(PolynomialExpression<Boolean> expr) {
        return expr.evaluate(new NullableRing<>(BooleanField.INSTANCE), i -> null);
    }

    private static PolynomialExpression<Boolean> removeVariable(PolynomialExpression<Boolean> expr, int index) {
        return expr.mapVariablesAndConstants(
                i -> i == index ? BasePolynomialExpression.constant(false) : BasePolynomialExpression.variable(i),
                BasePolynomialExpression::constant);
    }

    private int emitGates(PolynomialExpression<Boolean> expr) {
        int currentIndex = programBuilder.rawGates().size();
        switch (expr.expressionType()) {
            case CONSTANT:
                programBuilder.addGate(expr.constantValue() ? GateData.TRUE : GateData.FALSE);
                return currentIndex;
            case VARIABLE:
                int variableGateIndex = variableGateIndices.get(expr.variableIndex());
                addRawGate(IDENTITY, currentIndex - variableGateIndex);
                return currentIndex;
            case SUM:
                return emitChain(GateData.FALSE, XOR, expr.subTerms().stream().mapToInt(this::emitGates).toArray());
            case PRODUCT:
                return emitChain(GateData.TRUE, AND, expr.subTerms().stream().mapToInt(this::emitGates).toArray());
        }
        throw new AssertionError(expr.expressionType()); // unreachable
    }

    private int emitChain(GateData source, GateType op, int... inputIndices) {
        int currentIndex = programBuilder.rawGates().size();
        if (inputIndices.length == 0) {
            programBuilder.addGate(source);
            return currentIndex;
        }
        if (inputIndices.length == 1) {
            addRawGate(IDENTITY, currentIndex - inputIndices[0]);
            return currentIndex;
        }
        addRawGate(op, currentIndex - inputIndices[0], currentIndex - inputIndices[1]);
        for (int i = 2; i < inputIndices.length; i++) {
            addRawGate(op, ++currentIndex - inputIndices[i], 1);
        }
        return currentIndex;
    }

    private static IntStream variableIndices(PolynomialExpression<?> expr) {
        switch (expr.expressionType()) {
            case CONSTANT:
                return IntStream.empty();
            case VARIABLE:
                return IntStream.of(expr.variableIndex());
            case SUM:
            case PRODUCT:
                return expr.subTerms().stream().flatMapToInt(BooleanFunctionConverter::variableIndices);
        }
        throw new AssertionError(expr.expressionType()); // unreachable
    }

    private static <E> PolynomialExpression<E> partialDerivative(PolynomialExpression<E> expr, int index, Ring<E> ring) {
        switch (expr.expressionType()) {
            case CONSTANT:
                return BasePolynomialExpression.constant(ring.zero());
            case VARIABLE:
                return BasePolynomialExpression.constant(expr.variableIndex() == index ? ring.one() : ring.zero());
            case SUM:
                return BasePolynomialExpression.sum(expr.subTerms().stream()
                        .map(subExpr -> partialDerivative(subExpr, index, ring))
                        .collect(ImmutableList.toImmutableList()));
            case PRODUCT:
                return BasePolynomialExpression.sum(mapEachInTurn(expr.subTerms(), subExpr -> partialDerivative(subExpr, index, ring))
                        .map(BasePolynomialExpression::product)
                        .collect(ImmutableList.toImmutableList()));
        }
        throw new AssertionError(expr.expressionType()); // unreachable
    }

    private static <E> Stream<List<E>> mapEachInTurn(List<E> list, Function<E, E> fn) {
        return IntStream.range(0, list.size()).mapToObj(i -> ImmutableList.<E>builder()
                .addAll(list.subList(0, i))
                .add(fn.apply(list.get(i)))
                .addAll(list.subList(i + 1, list.size()))
                .build());
    }

    private static class NullableRing<E> implements Ring<E> {
        private final Ring<E> baseRing;
        private final E zero;

        private NullableRing(Ring<E> baseRing) {
            this.baseRing = baseRing;
            zero = baseRing.zero();
        }

        @Override
        public E fromBigInteger(BigInteger n) {
            return baseRing.fromBigInteger(n);
        }

        @Override
        public E product(E left, E right) {
            return left != null && right != null ? baseRing.product(left, right) :
                    zero.equals(left) || zero.equals(right) ? zero : null;
        }

        @Override
        public E sum(E left, E right) {
            return left != null && right != null ? baseRing.sum(left, right) : null;
        }

        @Override
        public E negative(E elt) {
            return baseRing.negative(elt);
        }
    }
}
