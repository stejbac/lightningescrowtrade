package lightningtrade.cryptography;

import com.google.common.collect.Streams;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public interface ObliviousFunction<T, R> extends Function<T, R> {
    ObliviousResponse<R> apply(ObliviousRequest<T> t);

    private static <T, R> Function<List<T>, List<R>> product(List<? extends Function<T, R>> fns) {
        //noinspection UnstableApiUsage
        return ts -> Streams.zip(fns.stream(), ts.stream(), Function::apply).collect(Collectors.toUnmodifiableList());
    }

    static <T, Ts, R, Rs> ObliviousFunction<Ts, Rs> product(Function<Ts, List<T>> toRequestFn,
                                                            Function<List<R>, Rs> toResponseFn,
                                                            Class<T> subRequestClass,
                                                            List<? extends ObliviousFunction<T, R>> obliviousFns) {
        Function<List<T>, List<R>> product = product(obliviousFns);
        return new ObliviousFunction<Ts, Rs>() {
            @Override
            public ObliviousResponse<Rs> apply(ObliviousRequest<Ts> t) {
                var fns = obliviousFns.stream();
                var requests = ((ObliviousRequest.Compound<?>) t).subRequests().stream();
                //noinspection UnstableApiUsage
                var responses = Streams.zip(fns, requests, (fn, r) -> fn.apply(r.cast(subRequestClass)));
                return ObliviousResponse.product(toResponseFn, responses.collect(Collectors.toUnmodifiableList()));
            }

            @Override
            public Rs apply(Ts ts) {
                return toResponseFn.apply(product.apply(toRequestFn.apply(ts)));
            }
        };
    }

    static <T, R> ObliviousFunction<T, R> liftSimple(Function<T, R> fn) {
        return new ObliviousFunction<T, R>() {
            @Override
            public ObliviousResponse<R> apply(ObliviousRequest<T> t) {
                return ((ObliviousRequest.Simple<T>) t).bindResponses(fn);
            }

            @Override
            public R apply(T t) {
                return fn.apply(t);
            }
        };
    }
}
