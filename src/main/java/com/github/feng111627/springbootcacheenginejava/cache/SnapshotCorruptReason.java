package com.github.feng111627.springbootcacheenginejava.cache;

/** Distinct reasons a snapshot file can be rejected during restore. */
public enum SnapshotCorruptReason {
    /** File cannot be read or is not syntactically valid JSON. */
    UNREADABLE,
    /** The snapshot format is from a newer, unsupported version. */
    UNSUPPORTED_VERSION,
    /** Required fields are missing or have incompatible types. */
    MALFORMED_FIELDS,
    /** The same key appears more than once. */
    DUPLICATE_KEY
}
