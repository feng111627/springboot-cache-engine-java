package com.github.feng111627.springbootcacheenginejava.cache;

/**
 * 回源加载失败的可区分原因。
 */
public enum LoadFailureReason {
    /** 下游源不可用（连接失败、5xx 等） */
    SOURCE_UNAVAILABLE,
    /** 加载超时 */
    TIMEOUT,
    /** 下游明确拒绝（如参数非法、权限不足） */
    REJECTED,
    /** 其他未分类失败 */
    UNKNOWN
}
