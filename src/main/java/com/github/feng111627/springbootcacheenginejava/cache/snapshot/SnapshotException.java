package com.github.feng111627.springbootcacheenginejava.cache.snapshot;

/**
 * 快照读写异常，携带可区分的拒绝原因。
 */
public class SnapshotException extends RuntimeException {

    private final SnapshotRejectReason reason;

    public SnapshotException(SnapshotRejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SnapshotException(SnapshotRejectReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public SnapshotRejectReason getReason() {
        return reason;
    }
}
