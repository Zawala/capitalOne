package capital.one.capital.service;

import capital.one.capital.model.IdempotencyRecord;
import capital.one.capital.model.IdempotencyState;
import capital.one.capital.repository.IdempotencyRecordRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reusable idempotency guard implementing the <em>claim → process → record</em> pattern.
 * <p>
 * Wrap any write handler in {@link #execute(String, String, String, Supplier)}. The first
 * caller for a given {@code (scope, key)} runs the action and has its response stored; any
 * duplicate (concurrent or later) is served the stored outcome without re-running the work.
 *
 * <h3>Duplicate decision table</h3>
 * <ul>
 *   <li>fingerprint mismatch → {@code 409} (key reused with a different payload)</li>
 *   <li>{@code IN_PROGRESS} and not expired → {@code 409} (already in progress)</li>
 *   <li>{@code COMPLETED} → replay stored status + body</li>
 *   <li>{@code FAILED}, or {@code IN_PROGRESS} older than {@link #LOCK_TTL} → reclaim and reprocess</li>
 * </ul>
 *
 * @see IdempotencyRecord
 */
@Service
public class IdempotencyService {

    /** An {@code IN_PROGRESS} claim older than this is treated as a crashed owner and reclaimable. */
    static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository repository;

    public IdempotencyService(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * Runs {@code action} at most once per {@code (scope, key)}, replaying the stored response
     * for duplicates.
     *
     * @param scope          endpoint namespace, e.g. {@code payments.receive}
     * @param key            the idempotency key (ISO 20022 MsgId or client {@code Idempotency-Key});
     *                       blank keys are rejected with {@code 400}
     * @param requestPayload canonical request representation; its SHA-256 fingerprint detects a key
     *                       reused with a different body
     * @param action         the real handler; its {@link ResponseEntity} is stored and replayed
     */
    public ResponseEntity<String> execute(String scope, String key, String requestPayload,
                                          Supplier<ResponseEntity<String>> action) {

        if (key == null || key.isBlank()) {
            return ResponseEntity.badRequest()
                    .body("Idempotency key is required for " + scope);
        }

        String fingerprint = sha256Hex(requestPayload);

        // ── 1. Try to claim the key with a fresh IN_PROGRESS row ──────────────
        Optional<IdempotencyRecord> claimed = tryClaim(scope, key, fingerprint);
        if (claimed.isPresent()) {
            return process(claimed.get(), action);
        }

        // ── 2. A row already exists — decide how to serve this duplicate ──────
        IdempotencyRecord existing = repository.findByScopeAndIdempotencyKey(scope, key)
                .orElse(null);
        if (existing == null) {
            // Extremely unlikely: the row vanished between the failed insert and this read.
            log.warn("Idempotency row for scope={} key={} disappeared after a claim collision", scope, key);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Could not resolve idempotency state, please retry");
        }

        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            log.warn("Idempotency conflict — scope={} key={} reused with a different payload", scope, key);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Idempotency key '" + key + "' was already used with a different request payload");
        }

        switch (existing.getState()) {
            case COMPLETED -> {
                log.info("Idempotent replay — scope={} key={} status={}", scope, key, existing.getResponseStatus());
                return replay(existing);
            }
            case IN_PROGRESS -> {
                if (isExpired(existing)) {
                    log.warn("Reclaiming stale in-progress idempotency lock — scope={} key={}", scope, key);
                    existing.reclaim(fingerprint);
                    repository.saveAndFlush(existing);
                    return process(existing, action);
                }
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body("A request with idempotency key '" + key + "' is already in progress");
            }
            case FAILED -> {
                log.info("Retrying previously failed idempotent request — scope={} key={}", scope, key);
                existing.reclaim(fingerprint);
                repository.saveAndFlush(existing);
                return process(existing, action);
            }
            default -> {
                return replay(existing);
            }
        }
    }

    /**
     * Inserts a new IN_PROGRESS row, committing immediately so concurrent callers can see the claim.
     * Returns empty when the insert collides with an existing row (another caller won the race).
     */
    private Optional<IdempotencyRecord> tryClaim(String scope, String key, String fingerprint) {
        try {
            IdempotencyRecord record = new IdempotencyRecord(scope, key, fingerprint);
            return Optional.of(repository.saveAndFlush(record));
        } catch (DataIntegrityViolationException e) {
            return Optional.empty();
        }
    }

    /** Runs the action, then records the outcome (COMPLETED for 2xx, otherwise FAILED). */
    private ResponseEntity<String> process(IdempotencyRecord record, Supplier<ResponseEntity<String>> action) {
        try {
            ResponseEntity<String> response = action.get();
            IdempotencyState finalState = response.getStatusCode().is2xxSuccessful()
                    ? IdempotencyState.COMPLETED
                    : IdempotencyState.FAILED;
            record.complete(finalState, response.getStatusCode().value(), response.getBody());
            repository.saveAndFlush(record);
            return response;
        } catch (RuntimeException e) {
            record.complete(IdempotencyState.FAILED, HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage());
            repository.saveAndFlush(record);
            throw e;
        }
    }

    private ResponseEntity<String> replay(IdempotencyRecord record) {
        return ResponseEntity.status(record.getResponseStatus()).body(record.getResponseBody());
    }

    private boolean isExpired(IdempotencyRecord record) {
        return record.getUpdatedAt().isBefore(LocalDateTime.now().minus(LOCK_TTL));
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input == null ? new byte[0] : input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JDK algorithm; this cannot happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
