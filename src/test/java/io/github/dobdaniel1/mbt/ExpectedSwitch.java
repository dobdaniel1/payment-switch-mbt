package io.github.dobdaniel1.mbt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The oracle: what the switch should hold after every step, for every transaction, not only
 * the one the model is following. Rules come from the switch's documented behaviour.
 */
final class ExpectedSwitch {

    enum Status { APPROVED, DECLINED, REVERSAL_PENDING, REVERSED }

    static final class Txn {
        final String id;
        final String type;
        final long amount;
        final String host;
        final String responseCode;
        Status status;
        boolean settled;

        Txn(String id, String type, long amount, String host, String responseCode, Status status) {
            this.id = id;
            this.type = type;
            this.amount = amount;
            this.host = host;
            this.responseCode = responseCode;
            this.status = status;
        }

        @Override
        public String toString() {
            return "%s %s %d %s via %s%s".formatted(id.substring(0, 8), type, amount, status, host,
                    settled ? " settled" : "");
        }
    }

    /** Outcome the switch should give for an authorization sent now. */
    record Authorization(String host, String responseCode, List<String> attempts) {}

    /** Batch the switch should close now, or null when there is nothing to settle (409). */
    record Batch(int count, long purchases, long refunds) {}

    final Map<String, String> hosts = new LinkedHashMap<>(Map.of("host-a", "up", "host-c", "up"));
    final Map<String, Txn> txns = new LinkedHashMap<>();

    /** Visa cards route to host-a and fail over to host-c. Attempts are "host:result". */
    Authorization authorize(boolean insufficientFunds) {
        List<String> attempts = new ArrayList<>();
        for (String host : List.of("host-a", "host-c")) {
            switch (hosts.get(host)) {
                case "down" -> attempts.add(host + ":unavailable");
                case "timeout" -> attempts.add(host + ":timeout");
                default -> {
                    String code = insufficientFunds ? "51" : "00";
                    attempts.add(host + ":" + code);
                    return new Authorization(host, code, attempts);
                }
            }
        }
        return new Authorization(null, "91", attempts);
    }

    void record(Txn txn) {
        txns.put(txn.id, txn);
    }

    /** Expected HTTP status of a reversal request; updates the transaction when it is accepted. */
    int reverse(Txn txn) {
        if (txn.status == Status.REVERSED || txn.status == Status.REVERSAL_PENDING) {
            return 200;
        }
        if (txn.status != Status.APPROVED || txn.settled) {
            return 409;
        }
        txn.status = reachable(txn.host) ? Status.REVERSED : Status.REVERSAL_PENDING;
        return 200;
    }

    /** Store and forward: queued reversals are delivered to hosts that are back up. */
    List<String> retryReversals() {
        List<String> delivered = new ArrayList<>();
        for (Txn txn : txns.values()) {
            if (txn.status == Status.REVERSAL_PENDING && reachable(txn.host)) {
                txn.status = Status.REVERSED;
                delivered.add(txn.id);
            }
        }
        return delivered;
    }

    List<String> pendingReversals() {
        return txns.values().stream().filter(t -> t.status == Status.REVERSAL_PENDING).map(t -> t.id).toList();
    }

    /** Only approved, unsettled transactions settle. Pending and completed reversals stay out. */
    Batch closeBatch() {
        List<Txn> eligible = txns.values().stream()
                .filter(t -> t.status == Status.APPROVED && !t.settled)
                .toList();
        if (eligible.isEmpty()) {
            return null;
        }
        eligible.forEach(t -> t.settled = true);
        long purchases = eligible.stream().filter(t -> t.type.equals("purchase")).mapToLong(t -> t.amount).sum();
        long refunds = eligible.stream().filter(t -> t.type.equals("refund")).mapToLong(t -> t.amount).sum();
        return new Batch(eligible.size(), purchases, refunds);
    }

    private boolean reachable(String host) {
        return "up".equals(hosts.get(host));
    }
}
