package com.github.feng111627.springbootcacheenginejava.cache.snapshot;

/**
 * 快照被拒绝的可区分原因。
 */
public enum SnapshotRejectReason {
    /** 文件损坏 / 校验和不匹配 */
    CORRUPTED,
    /** 版本号高于当前支持的版本 */
    UNSUPPORTED_VERSION,
    /** 字段类型或结构与该版本约定不兼容 */
    INCOMPATIBLE_FIELD,
    /** 快照文件不存在或不可读 */
    IO_ERROR
}
