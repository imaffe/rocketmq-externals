package org.apache.rocketmq.connect.runtime.connectorwrapper;

/**
 * @author youhui.zhang
 */
public class WorkerTaskErrorSnapshot {

    private WorkerTaskState prev;

    private Throwable throwable;

    public WorkerTaskState getPrev() {
        return prev;
    }

    public void setPrev(WorkerTaskState prev) {
        this.prev = prev;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }

    public WorkerTaskErrorSnapshot() {
    }

    public WorkerTaskErrorSnapshot(WorkerTaskState prev, Throwable throwable) {
        this.prev = prev;
        this.throwable = throwable;
    }

    @Override
    public String toString() {
        return "WorkerTaskErrorSnapshot{" +
                "prev=" + prev +
                ", throwable=" + throwable +
                '}';
    }
}
