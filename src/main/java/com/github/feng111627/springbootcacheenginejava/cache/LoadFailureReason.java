package com.github.feng111627.springbootcacheenginejava.cache;

/** Categorises why a {@link CacheLoader#load} invocation did not produce a value. */
public enum LoadFailureReason {
    /** The loader returned {@code null}: the key does not exist downstream. */
    NOT_FOUND,
    /** The loader threw an exception; the underlying throwable is never leaked to callers. */
    SOURCE_ERROR,
    /** The waiting thread was interrupted before the load finished. */
    INTERRUPTED
}
