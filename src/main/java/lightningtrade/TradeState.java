package lightningtrade;

import com.google.common.hash.Hashing;
import com.google.protobuf.ByteString;
import lightningtrade.cryptography.*;

public interface TradeState {
    @Access("buyer")
    BuyerState buyer();

    @Access("seller")
    SellerState seller();

    @Access("escrow")
    EscrowState escrow();

    @DependsOn("escrow.secret1")
    @DependsOn("escrow.garbledCircuit")
    @Access("ANY")
    default ByteString depositHash() {
        if (escrow().secret1() != null) {
            return sha256(escrow().secret1());
        }
        return byteString(escrow().garbledCircuit().lazyRun(GarbledBitVector.zeros(256)).ungarbledOutputs().subList(0, 256));
    }

    @DependsOn("escrow.secret2")
    @DependsOn("escrow.garbledCircuit")
    @Access("ANY")
    default ByteString commitment() {
        if (escrow().secret2() != null) {
            return sha256(escrow().secret2());
        }
        return byteString(escrow().garbledCircuit().lazyRun(GarbledBitVector.zeros(256)).ungarbledOutputs().subList(256, 512));
    }

    @DependsOn({"seller.secret", "escrow.secret1", "escrow.secret2"})
    @Access("seller.finalizesPayout => ANY")
    default ByteString payoutPreimage() {
        return xor(xor(seller().secret(), escrow().secret1()), escrow().secret2());
    }

    @DependsOn("payoutPreimage")
    @DependsOn({"seller.garbledCircuitResult", "escrow.garbledCircuitGenerator"})
    @DependsOn("seller.garbledCircuitResult")
    @Access("ANY")
    default ByteString payoutHash() {
        if (payoutPreimage() != null) {
            return sha256(payoutPreimage());
        }
        if (escrow().garbledCircuitGenerator() != null) {
            return byteString(escrow().garbledCircuitGenerator().ungarbleOutputs(seller().garbledCircuitResult().outputs()));
        }
        return byteString(seller().garbledCircuitResult().ungarbledOutputs().subList(512, 768));
    }

    interface BuyerState extends Child<TradeState> {
        @Event
        void receivesPendingPayout();

        @Action
        @DependsOn({"depositHash", "escrow.secret2", "buyer.receivesPendingPayout"})
        void sendsDeposit();

        @Event
        void getsDepositReceipt();

        @Event
        void startsCounterCurrencyPayment();
    }

    interface SellerState extends Child<TradeState> {
        ByteString secret();

        ObliviousTransferGenerator<BitVector, GarbledBitVector> obliviousTransferSpec();

        @DependsOn({"seller.obliviousTransferSpec", "seller.secret"})
        @Access("escrow")
        default ObliviousRequest<BitVector> obliviousSecret() {
            return obliviousTransferSpec().encrypt(bitVector(secret()));
        }

        @DependsOn({"seller.obliviousSecret", "escrow.garbledCircuitGenerator"})
        default ObliviousResponse<GarbledBitVector> obliviousGarbledSecret() {
            return parent().escrow().garbledCircuitGenerator().garbleInputs(obliviousSecret(), 0);
        }

        @DependsOn({"seller.obliviousTransferSpec", "seller.obliviousGarbledSecret", "escrow.garbledCircuit"})
        default GarbledCircuit.Result garbledCircuitResult() {
            GarbledBitVector sellerGarbledSecret = obliviousTransferSpec().decrypt(obliviousGarbledSecret());
            return parent().escrow().garbledCircuit().run(sellerGarbledSecret);
        }

        @Event
        void receivesPendingPayout();

        @Action
        @DependsOn({"depositHash", "buyer.sendsDeposit", "seller.receivesPendingPayout"})
        void sendsDeposit();

        @Event
        void getsDepositReceipt();

        @Event
        void confirmsCounterCurrencyPayment();

        @Action
        @DependsOn({"payoutPreimage", "seller.confirmsCounterCurrencyPayment"})
        void finalizesPayout();
    }

    interface EscrowState extends Child<TradeState> {
        @DependsOn("buyer.getsDepositReceipt")
        @DependsOn("seller.getsDepositReceipt")
        @Access("escrow.finalizesDeposits => ANY")
        ByteString secret1();

        @Access("buyer")
        ByteString secret2();

        @DependsOn("escrow.garbledCircuitGenerator")
        @Access("seller")
        default GarbledCircuit garbledCircuit() {
            return garbledCircuitGenerator().generate();
        }

        @Access("escrow.finalizesDeposits => buyer")
        GarbledCircuit.Generator garbledCircuitGenerator();

        @Action
        @DependsOn("payoutHash")
        void sendsPayout();

        @Event
        void receivesPendingBuyerDeposit();

        @Event
        void receivesPendingSellerDeposit();

        @Action
        @DependsOn({"escrow.receivesPendingBuyerDeposit", "escrow.receivesPendingSellerDeposit"})
        void finalizesDeposits();

        @Event
        void getsPayoutReceipt();
    }

    private static ByteString byteString(BitVector bitVector) {
        return ByteString.copyFrom(bitVector.bytes());
    }

    private static BitVector bitVector(ByteString byteString) {
        return BitVector.copyFrom(byteString.toByteArray());
    }

    private static ByteString sha256(ByteString message) {
        //noinspection UnstableApiUsage
        return ByteString.copyFrom(Hashing.sha256().hashBytes(message.toByteArray()).asBytes());
    }

    private static ByteString xor(ByteString s1, ByteString s2) {
        byte[] result = new byte[Math.min(s1.size(), s2.size())];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (s1.byteAt(i) ^ s2.byteAt(i));
        }
        return ByteString.copyFrom(result);
    }
}
