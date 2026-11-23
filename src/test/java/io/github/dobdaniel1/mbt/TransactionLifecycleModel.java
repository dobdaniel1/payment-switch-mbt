package io.github.dobdaniel1.mbt;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dobdaniel1.mbt.ExpectedSwitch.Authorization;
import io.github.dobdaniel1.mbt.ExpectedSwitch.Batch;
import io.github.dobdaniel1.mbt.ExpectedSwitch.Status;
import io.github.dobdaniel1.mbt.ExpectedSwitch.Txn;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.graphwalker.core.machine.ExecutionContext;
import org.junit.jupiter.api.function.Executable;

/**
 * Runs each model step against the switch. Edges act, vertices verify: at every vertex the
 * followed transaction and every other transaction must match {@link ExpectedSwitch}.
 */
public class TransactionLifecycleModel extends ExecutionContext implements TransactionLifecycle {

    private static final String VISA = "4111111111111111";
    private static final int STEPS_KEPT = 25;

    private final SwitchClient sw;
    private final Random random;
    private final ExpectedSwitch expected = new ExpectedSwitch();
    private final Deque<String> recentSteps = new ArrayDeque<>();
    private int stepCount;
    private int stan;
    private Txn current;
    private Map<String, Object> currentRequest;

    public TransactionLifecycleModel(SwitchClient sw, Random random) {
        this.sw = sw;
        this.random = random;
    }

    int stepCount() {
        return stepCount;
    }

    int transactionCount() {
        return expected.txns.size();
    }

    List<String> recentSteps() {
        return List.copyOf(recentSteps);
    }

    // Edges: actions

    @Override
    public void e_Start() {
        log("e_Start reset switch, all hosts up");
        assertEquals(204, sw.reset().status(), "reset failed; is ENABLE_ADMIN on?");
        setHost("host-a", "up");
        setHost("host-c", "up");
    }

    @Override
    public void e_Purchase() {
        authorize("purchase", amount(), false);
    }

    @Override
    public void e_Refund() {
        authorize("refund", amount(), false);
    }

    @Override
    public void e_PurchaseInsufficientFunds() {
        // Test hosts decline purchases whose minor units end in 51.
        authorize("purchase", amount() + 51, true);
    }

    @Override
    public void e_PurchaseNoHost() {
        authorize("purchase", amount(), false);
    }

    @Override
    public void e_Reverse() {
        Status before = current.status;
        int expectedHttp = expected.reverse(current);
        SwitchClient.Response r = sw.reverse(current.id);
        log("e_Reverse %s %s -> HTTP %d, expected %d".formatted(current.id.substring(0, 8), before, r.status(), expectedHttp));
        assertEquals(expectedHttp, r.status(), "reversal of a " + before + " transaction");
        if (expectedHttp == 200) {
            assertEquals(current.status.name(), r.body().path("status").asText(), "status after reversal");
        }
    }

    @Override
    public void e_Resend() {
        SwitchClient.Response r = sw.authorize(currentRequest);
        log("e_Resend terminal and STAN " + currentRequest.get("stan") + " -> HTTP " + r.status());
        assertEquals(200, r.status(), "a repeated terminal and STAN must not create a transaction");
        assertAll(
                () -> assertEquals(current.id, r.body().path("id").asText(), "original transaction returned"),
                () -> assertTrue(r.body().path("duplicate").asBoolean(), "flagged as duplicate"),
                () -> assertEquals(current.status.name(), r.body().path("status").asText(), "current status returned"));
    }

    @Override
    public void e_RetryReversals() {
        Set<String> delivered = new HashSet<>(expected.retryReversals());
        Set<String> pending = new HashSet<>(expected.pendingReversals());
        SwitchClient.Response r = sw.retryReversals();
        log("e_RetryReversals expected delivered=%d pending=%d".formatted(delivered.size(), pending.size()));
        assertEquals(200, r.status());
        assertAll(
                () -> assertEquals(delivered, ids(r.body().path("delivered")), "delivered reversals"),
                () -> assertEquals(pending, ids(r.body().path("still_pending")), "reversals still queued"));
    }

    @Override
    public void e_CloseBatch() {
        Batch batch = expected.closeBatch();
        SwitchClient.Response r = sw.closeBatch();
        log("e_CloseBatch expected %s -> HTTP %d".formatted(batch == null ? "nothing to settle" : batch, r.status()));
        if (batch == null) {
            assertEquals(409, r.status(), "closing a batch with nothing eligible");
            return;
        }
        assertEquals(201, r.status(), "closing a batch");
        JsonNode totals = r.body().path("totals").path("566");
        assertAll(
                () -> assertEquals(batch.count(), r.body().path("transaction_count").asInt(), "transactions settled"),
                () -> assertEquals(batch.purchases(), totals.path("purchase").asLong(), "purchase total"),
                () -> assertEquals(batch.refunds(), totals.path("refund").asLong(), "refund total"),
                () -> assertEquals(batch.purchases() - batch.refunds(), totals.path("net").asLong(), "net total"));
    }

    @Override
    public void e_NextTransaction() {
        log("e_NextTransaction");
        current = null;
        currentRequest = null;
    }

    @Override
    public void e_HostADown() {
        setHost("host-a", "down");
    }

    @Override
    public void e_HostATimeout() {
        setHost("host-a", "timeout");
    }

    @Override
    public void e_HostAUp() {
        setHost("host-a", "up");
    }

    @Override
    public void e_HostCDown() {
        setHost("host-c", "down");
    }

    @Override
    public void e_HostCUp() {
        setHost("host-c", "up");
    }

    // Vertices: checks

    @Override
    public void v_Ready() {
        verifyEverything();
    }

    @Override
    public void v_Approved() {
        verifyCurrent(Status.APPROVED, false);
    }

    @Override
    public void v_Declined() {
        verifyCurrent(Status.DECLINED, false);
    }

    @Override
    public void v_ReversalPending() {
        verifyCurrent(Status.REVERSAL_PENDING, false);
    }

    @Override
    public void v_Reversed() {
        verifyCurrent(Status.REVERSED, false);
    }

    @Override
    public void v_Settled() {
        verifyCurrent(Status.APPROVED, true);
    }

    // Helpers

    private void authorize(String type, long amount, boolean insufficientFunds) {
        Authorization exp = expected.authorize(insufficientFunds);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("pan", VISA);
        body.put("expiry", "3012");
        body.put("amount", amount);
        body.put("currency", "566");
        body.put("stan", "%06d".formatted(++stan));
        body.put("terminal_id", "MBT00001");
        body.put("merchant_id", "MERCHANTMBT0001");

        SwitchClient.Response r = sw.authorize(body);
        JsonNode t = r.body();
        log("e_%s %s %d -> HTTP %d %s via %s, expected %s via %s".formatted(stepName(), type, amount, r.status(),
                t == null ? "" : t.path("response_code").asText(), t == null ? "" : t.path("host_id").asText(null),
                exp.responseCode(), exp.host()));
        assertEquals(201, r.status(), "new terminal and STAN must create a transaction");
        Status status = exp.responseCode().equals("00") ? Status.APPROVED : Status.DECLINED;
        assertAll(
                () -> assertEquals(exp.responseCode(), t.path("response_code").asText(), "response code"),
                () -> assertEquals(status.name(), t.path("status").asText(), "status"),
                () -> assertEquals(exp.host(), t.path("host_id").asText(null), "answering host"),
                () -> assertEquals(exp.attempts(), attempts(t.path("attempts")), "routing attempts"));

        current = new Txn(t.path("id").asText(), type, amount, exp.host(), exp.responseCode(), status);
        currentRequest = body;
        expected.record(current);
    }

    private void setHost(String host, String status) {
        expected.hosts.put(host, status);
        SwitchClient.Response r = sw.setHost(host, status);
        log("set %s %s -> HTTP %d".formatted(host, status, r.status()));
        assertEquals(200, r.status());
        assertEquals(status, r.body().path("status").asText());
    }

    /** The graph and the oracle must agree first, then the switch must agree with both. */
    private void verifyCurrent(Status status, boolean settled) {
        assertAll("model and oracle disagree; fix the model",
                () -> assertEquals(status, current.status),
                () -> assertEquals(settled, current.settled));
        verifyEverything();
    }

    private void verifyEverything() {
        SwitchClient.Response r = sw.list();
        assertEquals(200, r.status());
        Map<String, JsonNode> actual = new HashMap<>();
        r.body().forEach(t -> actual.put(t.path("id").asText(), t));

        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertEquals(expected.txns.keySet(), actual.keySet(), "transactions on the switch"));
        for (Txn txn : expected.txns.values()) {
            JsonNode t = actual.get(txn.id);
            if (t == null) {
                continue;
            }
            checks.add(() -> assertEquals(txn.status.name(), t.path("status").asText(), txn + ": status"));
            checks.add(() -> assertEquals(txn.settled, !t.path("batch_id").isNull(), txn + ": settled"));
        }
        SwitchClient.Response hosts = sw.hosts();
        hosts.body().forEach(h -> {
            String id = h.path("id").asText();
            if (expected.hosts.containsKey(id)) {
                checks.add(() -> assertEquals(expected.hosts.get(id), h.path("status").asText(), id + " status"));
            }
        });
        assertAll("switch state after step " + stepCount, checks);
    }

    private long amount() {
        return (random.nextInt(4999) + 1) * 100L;
    }

    private static List<String> attempts(JsonNode attempts) {
        return StreamSupport.stream(attempts.spliterator(), false)
                .map(a -> a.path("host").asText() + ":" + a.path("result").asText())
                .toList();
    }

    private static Set<String> ids(JsonNode array) {
        Set<String> ids = new HashSet<>();
        array.forEach(n -> ids.add(n.asText()));
        return ids;
    }

    private String stepName() {
        return getCurrentElement() == null ? "?" : getCurrentElement().getName().replaceFirst("^e_", "");
    }

    private void log(String step) {
        stepCount++;
        recentSteps.addLast(stepCount + ". " + step);
        if (recentSteps.size() > STEPS_KEPT) {
            recentSteps.removeFirst();
        }
    }
}
