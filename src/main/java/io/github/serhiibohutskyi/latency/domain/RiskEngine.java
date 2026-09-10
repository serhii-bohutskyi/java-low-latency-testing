package io.github.serhiibohutskyi.latency.domain;

/**
 * A small, branchy, allocation-free pre-trade risk check.
 *
 * <p>Three checks, in increasing cost order so the cheap rejections short-circuit:
 * fat-finger quantity, notional limit, and a running net-position limit. The position
 * update makes this stateful, which matters for the benchmark: a stateless pure function
 * is much easier for the JIT to optimise into nothing, and a benchmark of nothing is
 * a benchmark of the harness.
 */
public final class RiskEngine {

    private static final int MAX_QUANTITY = 10_000;
    private static final long MAX_NOTIONAL_MICROS = 500_000_000_000L;   // 500k units
    private static final long MAX_NET_POSITION = 1_000_000L;

    private long netPosition;
    private long accepted;
    private long rejected;

    public boolean accept(OrderView order) {
        int quantity = order.quantity();

        if (quantity <= 0 || quantity > MAX_QUANTITY) {
            rejected++;
            return false;
        }

        long notional = order.priceMicros() * quantity;
        if (notional > MAX_NOTIONAL_MICROS) {
            rejected++;
            return false;
        }

        long signed = order.side() == Side.BUY ? quantity : -quantity;
        long projected = netPosition + signed;

        if (projected > MAX_NET_POSITION || projected < -MAX_NET_POSITION) {
            rejected++;
            return false;
        }

        netPosition = projected;
        accepted++;
        return true;
    }

    public long netPosition() {
        return netPosition;
    }

    public long acceptedCount() {
        return accepted;
    }

    public long rejectedCount() {
        return rejected;
    }

    public void reset() {
        netPosition = 0;
        accepted = 0;
        rejected = 0;
    }
}
