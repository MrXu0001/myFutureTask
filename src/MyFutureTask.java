import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;


public class MyFutureTask<V> implements MyRunnableFuture<V> {
    private volatile int state;
    private static final int NEW          = 0;
    private static final int COMPLETING   = 1;
    private static final int NORMAL       = 2;
    private static final int EXCEPTIONAL  = 3;
    private static final int CANCELLED    = 4;
    private static final int INTERRUPTING = 5;
    private static final int INTERRUPTED  = 6;
    //========================================以下为静态属性初始化========================================//
    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }
    //========================================以上为静态属性初始化========================================//
    private Callable<V> callable;
    private V result;
    private Exception exception;
    private Object outcome;
    private volatile Thread runner;
    private volatile WaitNode waiters;
    //===========================================以下为两大构造方法======================================//
    public MyFutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;
    }
    public MyFutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;
    }
    //===========================================以上为两大构造方法======================================//

    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    public boolean isDone() {
        return state != NEW;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        // 1， state == NEW 表示当前任务处于运行中 or 处于线程池的任务队列中 只有这两种状态下的任务才可以被取消
        // 2， cas把状态改为CANCELED or INTERRUPTING 根据 mayInterruptIfRunning 决定
        if (!(state == NEW &&
                UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                        mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        try {    // in case call to interrupt throws exception
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null)
                        t.interrupt();
                } finally { // final state
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            finishCompletion();
        }
        return true;
    }

    //=====================================以下为线程调用任务的入口方法=================================//
    @Override
    public void run() {
        //state！= NEW 说明该任务已经被别人执行过了 或者 被取消掉了
        // or
        //cas将this的执行线程对象设置为当前线程不成功 说明发生了并发冲突
        //直接返回 不需要执行任务  因为已经有其他线程执行了这个任务
        if (state != NEW ||
                !UNSAFE.compareAndSwapObject(this, runnerOffset,
                        null, Thread.currentThread()))
            return;
        // 执行到这里 当前task一定是NEW状态 当前线程抢占task成功
        try {
            Callable<V> c = callable;
            // 1， c != null 表示当前任务没有被取消
            // 2， c.call() 执行任务
            if (c != null && state == NEW) {
                V result;
                boolean ran;
                try {
                    result = c.call();
                    ran = true;
                } catch (Throwable ex) {
                    result = null;
                    ran = false;
                    setException(ex);
                }
                // 1， ran == true 表示任务执行成功
                // 2， ran == false 表示任务执行失败
                if (ran)
                    set(result);
            }
        } finally {
            runner = null;
            int s = state;
            // 1， s == INTERRUPTING 表示当前任务被取消了，但是还没有执行完毕
            // 2， s == INTERRUPTED 表示当前任务被取消了，而且已经执行完毕
            if (s >= INTERRUPTING)
                // 这个方法为刚开始run就被cancel（打断），则会空循环等待到INTERRUPTING --> INTERRUPTED
                handlePossibleCancellationInterrupt(s);
        }
    }
    //=====================================以上为线程调用任务的入口方法=================================//

    protected void setException(Throwable t) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = t;
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state
            finishCompletion();
        }
    }
    protected void set(V v) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = v;
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
            finishCompletion();
        }
    }
    private void handlePossibleCancellationInterrupt(int s) {
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt
    }

    //==========================================以下为WaitNode内部类的定义==========================================//
    static final class WaitNode {
        volatile Thread thread;
        volatile MyFutureTask.WaitNode next;
        WaitNode() { thread = Thread.currentThread(); }
    }
    //==========================================以上为WaitNode内部类的定义==========================================//

    //=======================以下为两个get方法，由多个线程调用，任务还没结束，即run方法还未结束，可以阻塞=======================//
    public V get() throws InterruptedException, ExecutionException {
        int s = state;
        if (s <= COMPLETING)
            s = awaitDone(false, 0L);
        return report(s);
    }

    public V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        if (s <= COMPLETING &&
                (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        return report(s);
    }
    //===============================================以上为两个get方法==========================================//

    private int awaitDone(boolean timed, long nanos)
            throws InterruptedException {
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        MyFutureTask.WaitNode q = null;
        boolean queued = false;
        for (;;) {
            if (Thread.interrupted()) {
                removeWaiter(q);
                throw new InterruptedException();
            }

            int s = state;
            if (s > COMPLETING) {
                if (q != null)
                    q.thread = null;
                return s;
            }
            else if (s == COMPLETING)
                Thread.yield();
            else if (q == null)
                q = new MyFutureTask.WaitNode();
            else if (!queued)
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                        q.next = waiters, q);
            else if (timed) {
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                LockSupport.parkNanos(this, nanos);
            }
            else
                LockSupport.park(this);
        }
    }
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        if (s == NORMAL)
            return (V)x;
        if (s >= CANCELLED)
            throw new CancellationException();
        throw new ExecutionException((Throwable)x);
    }
    private void removeWaiter(MyFutureTask.WaitNode node) {
        if (node != null) {
            node.thread = null;
            retry:
            for (;;) {          // restart on removeWaiter race
                for (MyFutureTask.WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    if (q.thread != null)
                        pred = q;
                    else if (pred != null) {
                        pred.next = s;
                        if (pred.thread == null) // check for race
                            continue retry;
                    }
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                            q, s))
                        continue retry;
                }
                break;
            }
        }
    }



    // 任务结束或者被中断都会执行这个方法，用来唤醒所有等待的线程
    private void finishCompletion() {
        // assert state > COMPLETING;
        for (MyFutureTask.WaitNode q; (q = waiters) != null;) {
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                for (;;) {
                    Thread t = q.thread;
                    if (t != null) {
                        q.thread = null;
                        LockSupport.unpark(t);
                    }
                    MyFutureTask.WaitNode next = q.next;
                    if (next == null)
                        break;
                    q.next = null; // unlink to help gc
                    q = next;
                }
                break;
            }
        }
        done();
        callable = null;        // to reduce footprint
    }
    protected void done() { }
}
