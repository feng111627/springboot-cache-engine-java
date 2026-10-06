package com.github.feng111627.springbootcacheenginejava.cache;

/**
 * 回源加载失败异常。封装底层异常，不向调用方原样透出。
 */
public class CacheLoadException extends RuntimeException {

    private final LoadFailureReason reason;

    public CacheLoadException(LoadFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public LoadFailureReason getReason() {
        return reason;
    }
}
