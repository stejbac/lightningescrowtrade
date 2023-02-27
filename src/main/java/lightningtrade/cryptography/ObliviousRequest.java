package lightningtrade.cryptography;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class ObliviousRequest<T> {
    private final Class<T> clazz;

    ObliviousRequest(Class<T> clazz) {
        this.clazz = clazz;
    }

    @SuppressWarnings("unchecked")
    public <U> ObliviousRequest<U> cast(Class<U> clazz) {
        if (this.clazz != clazz) {
            throw new ClassCastException("Cannot cast ObliviousRequest<" + this.clazz.getName() +
                    "> to ObliviousRequest<" + clazz.getName() + ">");
        }
        return (ObliviousRequest<U>) this;
    }

    public static abstract class Compound<T> extends ObliviousRequest<T> {
        Compound(Class<T> clazz) {
            super(clazz);
        }

        public abstract List<ObliviousRequest<?>> subRequests();
    }

    public static abstract class Simple<T> extends ObliviousRequest<T> {
        Simple(Class<T> clazz) {
            super(clazz);
        }

        public abstract List<T> choices();

        public abstract <R> ObliviousResponse<R> bindResponses(Iterable<R> responses);

        public <R> ObliviousResponse<R> bindResponses(Function<T, R> responseFn) {
            return bindResponses(choices().stream().map(responseFn).collect(Collectors.toList()));
        }
    }
}
