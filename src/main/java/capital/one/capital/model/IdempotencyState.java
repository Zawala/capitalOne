package capital.one.capital.model;

/**
 * Lifecycle state of an {@link IdempotencyRecord}.
 *
 * <pre>
 * IN_PROGRESS  the first caller has claimed the key and is still processing
 * COMPLETED    processing finished with a success (2xx) response, safe to replay
 * FAILED       processing finished with a non-success response; retryable
 * </pre>
 */
public enum IdempotencyState {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
