package dev.localresume;

import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Normal mutations share a lock; backups, restores and inventories exclude mutations. */
@Component
public class WorkspaceGate {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    public Lease mutation() { return acquire(false, 5); }
    public Lease exclusive() { return acquire(true, 30); }
    private Lease acquire(boolean exclusive, int seconds) {
        var target = exclusive ? lock.writeLock() : lock.readLock();
        try {
            if (!target.tryLock(seconds, TimeUnit.SECONDS))
                throw new ApiException("WORKSPACE_BUSY", "备份、恢复或文件检查正在进行，本次操作尚未完成，请稍后重试。", 423);
            return target::unlock;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("WORKSPACE_BUSY", "操作被中断，请稍后重试。", 423);
        }
    }
    public interface Lease extends AutoCloseable { @Override void close(); }
}
