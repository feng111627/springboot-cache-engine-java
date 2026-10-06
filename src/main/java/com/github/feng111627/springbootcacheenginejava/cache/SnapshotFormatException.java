package com.github.feng111627.springbootcacheenginejava.cache;

/** Thrown when a snapshot cannot be restored, with a distinguishable reason. */
public class SnapshotFormatException extends RuntimeException {

    private final SnapshotCorruptReason reason;

    public SnapshotFormatException(SnapshotCorruptReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SnapshotFormatException(SnapshotCorruptReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public SnapshotCorruptReason reason() {
        return reason;
    }
}
