package capital.one.capital.service;

import capital.one.capital.model.IdempotencyRecord;
import capital.one.capital.model.IdempotencyState;
import capital.one.capital.repository.IdempotencyRecordRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link IdempotencyService} against a real H2-backed repository so the
 * unique-constraint claim/collision path is covered faithfully.
 * <p>
 * {@code NOT_SUPPORTED} disables the test-managed transaction so each {@code saveAndFlush}
 * commits in its own transaction — a duplicate insert then surfaces the constraint violation
 * exactly as it would at runtime, instead of poisoning a single test-wide transaction.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdempotencyServiceTest {

    private static final String SCOPE = "test.scope";

    @Autowired
    private IdempotencyRecordRepository repository;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository);
        repository.deleteAll();
    }

    private Supplier<ResponseEntity<String>> okAction(AtomicInteger counter, String body) {
        return () -> {
            counter.incrementAndGet();
            return ResponseEntity.ok(body);
        };
    }

    @Test
    void blankKeyIsRejected() {
        AtomicInteger runs = new AtomicInteger();
        ResponseEntity<String> response =
                service.execute(SCOPE, "  ", "payload", okAction(runs, "ok"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(0, runs.get(), "action must not run for a blank key");
    }

    @Test
    void firstCallRunsActionAndPersistsCompleted() {
        AtomicInteger runs = new AtomicInteger();
        ResponseEntity<String> response =
                service.execute(SCOPE, "k1", "payload", okAction(runs, "done"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("done", response.getBody());
        assertEquals(1, runs.get());

        IdempotencyRecord stored = repository.findByScopeAndIdempotencyKey(SCOPE, "k1").orElseThrow();
        assertEquals(IdempotencyState.COMPLETED, stored.getState());
        assertEquals(200, stored.getResponseStatus());
        assertEquals("done", stored.getResponseBody());
    }

    @Test
    void duplicateWithSamePayloadReplaysWithoutRerunning() {
        AtomicInteger runs = new AtomicInteger();
        service.execute(SCOPE, "k2", "payload", okAction(runs, "first"));

        ResponseEntity<String> replay =
                service.execute(SCOPE, "k2", "payload", okAction(runs, "second"));

        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertEquals("first", replay.getBody(), "stored response is replayed, not re-computed");
        assertEquals(1, runs.get(), "action runs only once across duplicates");
    }

    @Test
    void sameKeyDifferentPayloadIsRejectedAsConflict() {
        AtomicInteger runs = new AtomicInteger();
        service.execute(SCOPE, "k3", "payload-A", okAction(runs, "A"));

        ResponseEntity<String> conflict =
                service.execute(SCOPE, "k3", "payload-B", okAction(runs, "B"));

        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertTrue(conflict.getBody().contains("different request payload"));
        assertEquals(1, runs.get());
    }

    @Test
    void duplicateWhileInProgressIsRejectedAsConflict() {
        // Simulate a first caller that has claimed the key and is still processing.
        IdempotencyRecord inFlight = repository.saveAndFlush(
                new IdempotencyRecord(SCOPE, "k4", sha256("payload")));
        assertEquals(IdempotencyState.IN_PROGRESS, inFlight.getState());

        AtomicInteger runs = new AtomicInteger();
        ResponseEntity<String> conflict =
                service.execute(SCOPE, "k4", "payload", okAction(runs, "x"));

        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertTrue(conflict.getBody().contains("already in progress"));
        assertEquals(0, runs.get());
    }

    @Test
    void previouslyFailedRequestIsRetried() {
        IdempotencyRecord failed = new IdempotencyRecord(SCOPE, "k5", sha256("payload"));
        failed.complete(IdempotencyState.FAILED, 502, "downstream down");
        repository.saveAndFlush(failed);

        AtomicInteger runs = new AtomicInteger();
        ResponseEntity<String> response =
                service.execute(SCOPE, "k5", "payload", okAction(runs, "recovered"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("recovered", response.getBody());
        assertEquals(1, runs.get(), "a failed record is reprocessed on retry");

        IdempotencyRecord stored = repository.findByScopeAndIdempotencyKey(SCOPE, "k5").orElseThrow();
        assertEquals(IdempotencyState.COMPLETED, stored.getState());
    }

    // Mirrors the service's internal fingerprinting so the seeded fingerprint matches.
    private String sha256(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
