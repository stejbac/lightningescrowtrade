package lightningtrade;

import chainrpc.ChainNotifierGrpc;
import chainrpc.Chainnotifier;
import com.google.common.base.Stopwatch;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Files;
import com.google.common.primitives.Bytes;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.netty.NettySslContextChannelCredentials;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.SslContextBuilder;
import lnrpc.Rpc;

import javax.net.ssl.SSLException;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class App {
    private static final String LND_DIR = "/home/steven/.lnd";
    private static final String THIS_PUBKEY = "0394008a27a758a4a05b54a069bc23a82f174578b03490d91f623e34354e2ae0c6";
    private static final String WALLET_OF_SATOSHI_PUBKEY = "035e4ff418fc8b5554c5d9eea66396c227bd429a3251c8cbc711002ba215bfc226";
    private static final long WALLET_OF_SATOSHI_CHANNEL_ID = 786553235327090689L;
    private static final String ACINQ_PUBKEY = "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f";
    private static final long ACINQ_CHANNEL_ID = 785963896971984897L;
    private static final String LNBIG_PUBKEY = "034ea80f8b148c750463546bd999bf7321a0e6dfc60aaf84bd0400a2e8d376c0d5";
    private static final long LNBIG_CHANNEL_ID = 789399870875041793L;

    public static void main(String[] args) throws IOException {
//        byte[] rndBytes = new byte[16];
//        new SecureRandom().nextBytes(rndBytes);
//        System.out.println(toHexString(rndBytes));
//        System.exit(0);

        var sslCredentials = loadSslChannelCredentials();
        var callCredentials = loadMainnetAdminCallCredentials();

        var channel = Grpc.newChannelBuilderForAddress("127.0.0.1", 10009, sslCredentials).build();
        var lightningService = new LightningService(channel, callCredentials);

        var preimage = newRandomPreimage(new SecureRandom());
//        var preimage = ByteString.copyFrom(fromHexString("56fae7349ee354fe1b02d35fa0ae3051437c3ab3e7b2e0737ba5d9523955964f"));
        var hash = sha256Hash(preimage);
        System.out.println(toHexString(preimage.toByteArray()));
        System.out.println(toHexString(hash.toByteArray()));

        var stopwatch = Stopwatch.createStarted();
        String invoice = lightningService.addHoldInvoice("Test reroute 400000 sats 10", hash, 400_000_000, 1440);
        System.out.println(invoice);
        System.out.println("Time to create invoice: " + stopwatch.stop());

        Uninterruptibles.sleepUninterruptibly(20, TimeUnit.SECONDS);

        stopwatch.reset().start();
//        String invoice = "lnbc4m1p3lwwx7pp5fs3gqca3uu7x3xm2rr8rrk4ygnu6z5g8nlhyl88uuaxj4kgsl4xqdp223jhxapqwfjhymm4w3" +
//                "jjqdpsxqcrqvpqwdshgueq8qcqzkssp5clk0g08rsx5ntrwfukx6r6hua6232c7qstjtjdvljh8yncgrg25s9qyyss" +
//                "qhg5a0q84t0y43w30h7rsjrt9djmdf3deykt6kvdz268v044v3vj5gg96s7rx88ruqsusvmd6pqewz8prdthhkcrvu" +
//                "d9f4kqppfltpagpf4d5g7";
        var payments = lightningService.payInvoice(invoice,
                WALLET_OF_SATOSHI_CHANNEL_ID,
//                ACINQ_CHANNEL_ID,
//                LNBIG_CHANNEL_ID,
//                ByteString.copyFrom(fromHexString(WALLET_OF_SATOSHI_PUBKEY))
//                ByteString.copyFrom(fromHexString(ACINQ_PUBKEY))
                ByteString.copyFrom(fromHexString(LNBIG_PUBKEY))
        );

        System.out.println(payments);
        System.out.println("Time to pay invoice: " + stopwatch.stop());

        Uninterruptibles.sleepUninterruptibly(15, TimeUnit.MINUTES);

        stopwatch.reset().start();
        lightningService.settleInvoice(preimage);
        System.out.println("Time to settle invoice: " + stopwatch.stop());

        /*var lightningStub = LightningGrpc.newBlockingStub(channel).withCallCredentials(callCredentials);

        var walletBalanceResponse = lightningStub.walletBalance(Rpc.WalletBalanceRequest.getDefaultInstance());
        System.out.println(walletBalanceResponse);
        System.out.println(lightningStub.getInfo(Rpc.GetInfoRequest.getDefaultInstance()));

        var stopwatch = Stopwatch.createStarted();
        var queryRoutesRequest = Rpc.QueryRoutesRequest.newBuilder()
                .setPubKey(THIS_PUBKEY)
                .setOutgoingChanId(WALLET_OF_SATOSHI_CHANNEL_ID)
                .setLastHopPubkey(ByteString.copyFrom(fromHexString(ACINQ_PUBKEY)))
                .setAmt(300000)
                .setFinalCltvDelta(1440)
//                .setFeeLimit(Rpc.FeeLimit.newBuilder().setFixed(300).build())
                .build();
        var queryRoutesResponse = lightningStub.queryRoutes(queryRoutesRequest);
        System.out.println(queryRoutesResponse);
        System.out.println("Time to find route: " + stopwatch.stop());*/

        /*var routerStub = RouterGrpc.newStub(channel).withCallCredentials(callCredentials);
        routerStub.sendToRouteV2(
                RouterOuterClass.SendToRouteRequest.newBuilder()
                        .setRoute(queryRoutesResponse.getRoutes(0))
                        .setPaymentHash(ByteString.copyFrom(new byte[32]))
                        .build(),
                new ClientResponseObserver<>() {
                    @Override
                    public void onNext(Rpc.HTLCAttempt value) {
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }

                    @Override
                    public void beforeStart(ClientCallStreamObserver<Object> requestStream) {
                    }
                }
        );
        var htlcInterceptResponseObserver = routerStub.htlcInterceptor(new ClientResponseObserver<>() {
            @Override
            public void onNext(RouterOuterClass.ForwardHtlcInterceptRequest value) {
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }

            @Override
            public void beforeStart(ClientCallStreamObserver<Object> requestStream) {
            }
        });
        htlcInterceptResponseObserver.onNext(RouterOuterClass.ForwardHtlcInterceptResponse.newBuilder()
                .setAction(RouterOuterClass.ResolveHoldForwardAction.SETTLE)
                .setPreimage(ByteString.copyFrom(new byte[32]))
                .build()
        );*/

        var chainNotifierStub = ChainNotifierGrpc.newStub(channel).withCallCredentials(callCredentials);

        var finishLatch = new CountDownLatch(1);
        var streamObserverRef = new AtomicReference<ClientCallStreamObserver<?>>();
        chainNotifierStub.registerBlockEpochNtfn(
                Chainnotifier.BlockEpoch.newBuilder().setHeight(670000).build(),
                new ClientResponseObserver<>() {
                    @Override
                    public void beforeStart(ClientCallStreamObserver<Object> requestStream) {
                        streamObserverRef.set(requestStream);
                    }

                    @Override
                    public void onNext(Chainnotifier.BlockEpoch value) {
                        System.out.println("Got block epoch: " + value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        System.out.println("Got error status: " + Status.fromThrowable(t));
                        finishLatch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        System.out.println("Completed");
                        finishLatch.countDown();
                    }
                }
        );
        Uninterruptibles.awaitUninterruptibly(finishLatch, 3, TimeUnit.SECONDS);
        if (finishLatch.getCount() > 0) {
            streamObserverRef.get().cancel("cancelled by client", null);
        }

        System.out.println(Arrays.toString(Rpc.ResolutionType.values()));
        System.out.println("Hello, world!");
    }

    private static ChannelCredentials loadSslChannelCredentials() throws SSLException {
        var sslContext = SslContextBuilder.forClient()
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.FATAL_ALERT,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.FATAL_ALERT,
                        "h2"
                ))
                .trustManager(new File(LND_DIR, "tls.cert"))
                .build();
        return NettySslContextChannelCredentials.create(sslContext);
    }

    private static LndCallCredentials loadMainnetAdminCallCredentials() throws IOException {
        var macaroonBytes = Files.asByteSource(new File(LND_DIR, "data/chain/bitcoin/mainnet/admin.macaroon")).read();
        var macaroon = toHexString(macaroonBytes);
        return new LndCallCredentials(macaroon);
    }

    private static ByteString newRandomPreimage(Random rnd) {
        byte[] bytes = new byte[32];
        rnd.nextBytes(bytes);
        return ByteString.copyFrom(bytes);
    }

    private static ByteString sha256Hash(ByteString preimage) {
        //noinspection UnstableApiUsage
        return ByteString.copyFrom(Hashing.sha256().hashBytes(preimage.toByteArray()).asBytes());
    }

    private static byte[] fromHexString(String hex) {
        return BaseEncoding.base16().lowerCase().decode(hex);
    }

    public static String toHexString(byte[] bytes) {
        return Bytes.asList(bytes).stream()
                .map(b -> Integer.toHexString((int) b & 0x0ff | 0x100).substring(1))
                .collect(Collectors.joining());
    }

    private static class LndCallCredentials extends CallCredentials {
        private final String macaroon;

        LndCallCredentials(String macaroon) {
            this.macaroon = macaroon;
        }

        @Override
        public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
            var headers = new Metadata();
            headers.put(Metadata.Key.of("macaroon", Metadata.ASCII_STRING_MARSHALLER), macaroon);
            applier.apply(headers);
        }

        @Override
        public void thisUsesUnstableApi() {
        }
    }
}
