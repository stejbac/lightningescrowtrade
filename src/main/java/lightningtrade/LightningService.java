package lightningtrade;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.protobuf.ByteString;
import invoicesrpc.InvoicesGrpc;
import invoicesrpc.InvoicesOuterClass;
import io.grpc.CallCredentials;
import io.grpc.Channel;
import lnrpc.Rpc;
import routerrpc.RouterGrpc;
import routerrpc.RouterOuterClass;

public class LightningService {
    private final InvoicesGrpc.InvoicesBlockingStub invoicesStub;
    private final RouterGrpc.RouterBlockingStub routerStub;

    public LightningService(Channel channel, CallCredentials callCredentials) {
        invoicesStub = InvoicesGrpc.newBlockingStub(channel).withCallCredentials(callCredentials);
        routerStub = RouterGrpc.newBlockingStub(channel).withCallCredentials(callCredentials);
    }

    public String addHoldInvoice(String memo, ByteString hash, long valueMsat, long cltvExpiry, Rpc.RouteHint... routeHints) {
        var response = invoicesStub.addHoldInvoice(
                InvoicesOuterClass.AddHoldInvoiceRequest.newBuilder()
                        .setMemo(memo)
                        .setHash(hash)
                        .setCltvExpiry(cltvExpiry)
                        .setValueMsat(valueMsat)
//                        .setPrivate(true)
                        .addAllRouteHints(ImmutableList.copyOf(routeHints))
                        .build()
        );
        return response.getPaymentRequest();
    }

    public Rpc.Payment payInvoice(String invoice, long outgoingChanId, ByteString lastHopPubkey) {
        var response = routerStub.sendPaymentV2(
                RouterOuterClass.SendPaymentRequest.newBuilder()
                        .setPaymentRequest(invoice)
//                        .setFinalCltvDelta(720)
                        .addOutgoingChanIds(outgoingChanId)
                        .setLastHopPubkey(lastHopPubkey)
                        .setFeeLimitSat(3000)
                        .setTimeoutSeconds(300)
                        .setMaxParts(4)
                        .setAllowSelfPayment(true)
                        .build()
        );
        //noinspection UnstableApiUsage
        return Streams.stream(response)
                .filter(p -> {
                    if (p.getStatus() == Rpc.Payment.PaymentStatus.SUCCEEDED || p.getStatus() == Rpc.Payment.PaymentStatus.FAILED) {
                        return true;
                    }
                    if (p.getStatus() == Rpc.Payment.PaymentStatus.IN_FLIGHT && p.getHtlcsCount() > 0 &&
                            p.getHtlcs(p.getHtlcsCount() - 1).getStatus() == Rpc.HTLCAttempt.HTLCStatus.IN_FLIGHT) {
                        return true;
                    }
                    System.out.println("GOT PAYMENT UPDATE: " + p);
                    return false;
                })
//                .filter(p -> p.getStatus() == Rpc.Payment.PaymentStatus.SUCCEEDED || p.getStatus() == Rpc.Payment.PaymentStatus.FAILED)
                .findFirst()
                .orElseThrow();
    }

    public void settleInvoice(ByteString preimage) {
        //noinspection ResultOfMethodCallIgnored
        invoicesStub.settleInvoice(
                InvoicesOuterClass.SettleInvoiceMsg.newBuilder().setPreimage(preimage).build()
        );
    }
}
