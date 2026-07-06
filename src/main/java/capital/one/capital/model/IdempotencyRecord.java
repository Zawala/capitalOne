package capital.one.capital.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * Idempotency ledger entry — one row per {@code (scope, idempotencyKey)}.
 * <p>
 * The composite unique constraint is the concurrency guarantee: the first caller to
 * insert a row "claims" the key (state {@link IdempotencyState#IN_PROGRESS}); any
 * concurrent or later duplicate collides on the constraint and is served from the
 * stored outcome instead of re-running the work.
 *
 * @see capital.one.capital.service.IdempotencyService
 */
@Entity
@Table(name = "idempotency_record",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_idempotency_scope_key",
               columnNames = {"scope", "idempotency_key"}))
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Endpoint namespace, e.g. {@code payments.receive}. Keeps keys from colliding across endpoints. */
    @Column(nullable = false)
    private String scope;

    /** The idempotency key — an ISO 20022 MsgId or a client-supplied {@code Idempotency-Key}. */
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    /** SHA-256 hex of the canonical request payload; detects a key reused with a different body. */
    @Column(nullable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdempotencyState state;

    /** HTTP status of the stored response, replayed to duplicates. */
    private Integer responseStatus;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    protected IdempotencyRecord() {
        // JPA
    }

    public IdempotencyRecord(String scope, String idempotencyKey, String requestFingerprint) {
        this.scope = scope;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.state = IdempotencyState.IN_PROGRESS;
    }

    @PrePersist
    void onPersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** Records a terminal outcome to be replayed to future duplicates. */
    public void complete(IdempotencyState finalState, int responseStatus, String responseBody) {
        this.state = finalState;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    /** Re-claims a stale/failed row so its owner can reprocess. */
    public void reclaim(String requestFingerprint) {
        this.requestFingerprint = requestFingerprint;
        this.state = IdempotencyState.IN_PROGRESS;
        this.responseStatus = null;
        this.responseBody = null;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public Long getId() { return id; }

    public String getScope() { return scope; }

    public String getIdempotencyKey() { return idempotencyKey; }

    public String getRequestFingerprint() { return requestFingerprint; }

    public IdempotencyState getState() { return state; }
    public void setState(IdempotencyState state) { this.state = state; }

    public Integer getResponseStatus() { return responseStatus; }

    public String getResponseBody() { return responseBody; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
