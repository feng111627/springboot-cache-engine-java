package com.github.feng111627.springbootcacheenginejava.cache;

/**
 * Raised when a single-flight load cannot produce a value.
 *
 * <p>The failure is categorised via {@link LoadFailureReason}; the original
 * throwable (if any) is kept for diagnostics but never rethrown unwrapped.
 */
public class CacheLoadException extends RuntimeException {

    private final LoadFailureReason reason;

    public CacheLoadException(LoadFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public LoadFailureReason reason() {
        return reason;
    }
}
