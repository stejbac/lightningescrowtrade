package lightningtrade.cryptography;

import java.util.List;
import java.util.function.Function;

public abstract class ObliviousResponse<T> {
    public static <T, Ts> ObliviousResponse<Ts> product(Function<List<T>, Ts> toResponseFn, List<ObliviousResponse<T>> subResponses) {
        throw new UnsupportedOperationException();
    }
}
