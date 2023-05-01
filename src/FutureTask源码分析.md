# FutureTask

## 异步特性

```java
// Future是一个接口，FutureTask是Future的实现类
public class Demo02 {
    public static void main(String[] args) throws ExecutionException, InterruptedException, TimeoutException {
        long start = System.currentTimeMillis();
        FutureTask<Integer> futureTask1 = new FutureTask<Integer>(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                // 睡了2000毫秒
                Thread.sleep(2000);
                return 1;
            }
        });

        FutureTask<Integer> futureTask2 = new FutureTask<Integer>(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                // 睡了1000毫秒
                Thread.sleep(1000);
                return 2;
            }
        });
        // 这个线程要睡2000毫秒
        new Thread(futureTask1).start();
        // 这个线程要睡1000毫秒
        new Thread(futureTask2).start();
        Integer s1 = futureTask1.get();
        Integer s2 = futureTask2.get();
        Integer s3 = s1 + s2;
        long end = System.currentTimeMillis();
        System.out.println("结果：" + s3);
        // 输出总共执行的时间
        System.out.println("花费时间：" + (end - start) + "毫秒");  // 输出2013ms，说明异步
    }  
}
```

## 继承体系

![在这里插入图片描述](https://img-blog.csdnimg.cn/ea50c7f183c440e591ec5670a5ce670f.png#pic_center)



## 一，FutureTask对象

```java
public class FutureTask<V> implements RunnableFuture<V> {
    //表示当前task的状态
    private volatile int state;
    //当前任务尚未执行
    private static final int NEW          = 0;
    //当前任务正在结束 正在结束 处于一种过度的状态 completing
    private static final int COMPLETING   = 1;
    //当前任务正常结束 normal
    private static final int NORMAL       = 2;
    //当前任务执行的过程中发生了异常 exceptional
    private static final int EXCEPTIONAL  = 3;
    //当前任务已经取消了 cancelled
    private static final int CANCELLED    = 4;
    //当前任务中断中 interrupting
    private static final int INTERRUPTING = 5;
    //当前任务已经中断
    private static final int INTERRUPTED  = 6;

    // 我们在使用FutureTask对象的时候，会传入一个Callable实现类或Runnable实现类，这个callable存储的就是
	// 传入的Callable实现类或Runnable实现类（Runnable会被使用修饰者设计模式伪装为）callable
    //submit(callable/runnable):其中runnable使用了装饰者设计模式伪装成了callable
    // 综上：传来的任务会保存到这个成员变量 --》 适配器模式
    private Callable<V> callable;
    //任务正常执行结果  outcome保存执行结果
    //任务执行中出现异常  也就是  成员变量callable.run()出现了异常 出现的异常会保存到outcome
    private Object outcome; // non-volatile, protected by state reads/writes
    //保存执行这个任务的线程
    private volatile Thread runner;
    //因为会有很多线程去get（）获得该任务的结果  所以这里使用了一种数据结构 头插 头取的队列
    private volatile WaitNode waiters;

    /**
     * Returns result or throws exception for completed task.
     *
     * @param s completed state value
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        //正常情况下
        if (s == NORMAL)
            //直接返回结果
            return (V)x;
        if (s >= CANCELLED)
            //已经取消的情况 抛出异常
            throw new CancellationException();
        //这是抛出执行过程中出现的异常
        throw new ExecutionException((Throwable)x);
    }

    //callable的构造方法
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        //callable就是程序员自己实现的业务类
        this.callable = callable;
        //设置当前任务状态为NEW 就是新建的状态 任务尚未执行
        this.state = NEW;       // ensure visibility of callable
    }

    //Runnable的构造方法
    public FutureTask(Runnable runnable, V result) {
        //result可能为null
        this.callable = Executors.callable(runnable, result);
        //设置当前任务状态为NEW 就是新建的状态 任务尚未执行
        this.state = NEW;       // ensure visibility of callable
    }
  //==========================Executors.callable（）================================//
    public static <T> Callable<T> callable(Runnable task, T result) {
        if (task == null)
            throw new NullPointerException();
        //不难看到这是一个适配器模式
        return new RunnableAdapter<T>(task, result);
    }
    //适配Runnable 接口和 Callable接口 
    //Runnable接口作为 RunnableAdapter 适配器的内部类 
    //实现Callable接口的run方法 实际上是调用的 实现了Runnable接口的成员变量的方法 
    static final class RunnableAdapter<T> implements Callable<T> {
        final Runnable task;
        final T result;
        RunnableAdapter(Runnable task, T result) {
            this.task = task;
            this.result = result;
        }
        public T call() {
            task.run();
            return result;
        }
    }
   //==========================Executors.callable（）================================//
    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    public boolean isDone() {
        return state != NEW;
    }

    //取消任务
    //任务可能处于两个状态
    public boolean cancel(boolean mayInterruptIfRunning) {
        // 1， state == NEW 表示当前任务处于运行中 or 处于线程池的任务队列中 只有这两种状态下的任务才可以被取消
        // 2， cas把状态改为CANCELED or INTERRUPTING 根据 mayInterruptIfRunning 决定
        
        // 1, 2 都成功 才会执行下面的逻辑
        if (!(state == NEW && 
              UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                                       mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        try {    // in case call to interrupt throws exception
            if (mayInterruptIfRunning) {
                //mayInterruptIfRunning 为true 表示需要给运行任务的线程发送中断信号
                try {
                    Thread t = runner;
                    if (t != null)
                        //什么时候会出现线程为null 的情况？
                        //当任务还处于线程池的工作队列的时候 线程就是null（还没有线程领取任务）
                        //将线程的中断标记设置为true
                        //到底线程中断还是不中断 需要业务逻辑里面判断 线程中断标志位
                        //来自己结束线程
                        t.interrupt();
                } finally { // final state
                    //cas设置状态为中断完成
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            //唤醒所有get（）阻塞线程
            finishCompletion();
        }
        return true;
    }

    //多个线程等待当前任务执行完成后的结果
    public V get() throws InterruptedException, ExecutionException {
        //获取当前任务的状态
        int s = state;
        if (s <= COMPLETING)
            //假如任务还未是完成or出现异常状态
            //将调用get（）方法的外部线程挂起
            //该方法的返回值是当前任务的状态
            //当前调用get（）方法的外部线程已经睡过一段时间了
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


    protected void done() { }


    //将正常执行返回的结果设置到outcome
    protected void set(V v) {
        //cas保证只有一个线程能够设置这个结果
        //状态设置为过度中 .. COMPLETING
        //理论上是不会失败的
        //失败只能是外面的线程取消了该任务 即设置为CANCELED
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            
            outcome = v;
            //设置状态为NORMAL 即正常完成任务的状态
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
            finishCompletion();
        }
    }


    //执行任务出现异常调用
    protected void setException(Throwable t) {
        //cas保证只有一个线程能够设置这个结果
        //状态设置为过度中 .. COMPLETING
        //理论上是不会失败的
        //失败只能是外面的线程取消了该任务 即设置为CANCELED
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            //将outcome设置为出现的异常
            outcome = t;
            //状态修改为EXCEPTIONAL
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state
            finishCompletion();
        }
    }
    
    //线程池里面的线程会调用该run方法
    //submit(runnable / callable ) -> newTask(runnable) -> execute(task) -> poll
    public void run() {
        
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            //state！= NEW 说明该任务已经被别人执行过了 或者 被取消掉了
            // or
            //cas将this的执行线程对象设置为当前线程不成功 说明发生了并发冲突
            //直接返回 不需要执行任务  因为已经有其他线程执行了这个任务
            return;
        
        //执行到这里 当前task一定是NEW状态 当前线程抢占task成功
        try {
            //callable 就是 程序员自己封装的 callable 或者装饰后的 runnable
            Callable<V> c = callable;
            //防止空指针 && 防止外部线程将任务cancel掉
            if (c != null && state == NEW) {
                V result;
                boolean ran;
                try {
                    //执行程序员自己写的业务逻辑 并且得到结果
                    result = c.call();
                    //没有异常 表示运行成功
                    ran = true;
                    
                } catch (Throwable ex) {
                    //出现异常 捕捉异常
                    result = null;
                    //运行状态标志位设置为false 即表示运行失败
                    ran = false;
                    //将异常对象设置到outcome
                    setException(ex);
                }
                //成功执行
                if (ran)
                    //将返回结果设置到outcome里面
                    set(result);
            }
        } finally {
            //任务的执行线程设置为null
            runner = null;
            //记录状态
            int s = state;
            //
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }


    protected boolean runAndReset() {
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

  
    private void handlePossibleCancellationInterrupt(int s) {
  
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt

    }

  
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;
        //构造函数
        //线程即为调用get()方法等待结果的对象
        WaitNode() { thread = Thread.currentThread(); }
    }

   
    //任务执行结束不管出没出现异常 都会调用该方法
    private void finishCompletion() {
        // assert state > COMPLETING;
        //多线程下也不会出现空循环的情况 
        //因为总有一个线程将waiters设置为null 下次再循环到这里就不会进入循环
        for (WaitNode q; (q = waiters) != null;) {
            //q代表着头节点
            //cas将头设置为null
            //外部任务使用cancel 取消当前任务 也会触发finishCompletion()方法
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                //循环
                for (;;) {
                    //获得结点的线程
                    Thread t = q.thread;
                    if (t != null) {
                        //设置为空
                        q.thread = null;
                        //唤醒线程
                        LockSupport.unpark(t);
                    }
                    //遍历到下一个结点
                    WaitNode next = q.next;
                    if (next == null)
                        //如果是空 说明所有的等待线程都唤醒了
                        break;
                    //方便垃圾回收
                    q.next = null; // unlink to help gc
                    //走向下一个结点
                    q = next;
                }
                break;
            }
        }

        //钩子函数 回调
        done();

        callable = null;        // to reduce footprint
    }


    //
    private int awaitDone(boolean timed, long nanos)
        throws InterruptedException {
        //不带超时
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        //当前调用get（）方法的线程封装为WaitNode
        WaitNode q = null;
        //入队成功标识 最开始为false
        boolean queued = false;
        //spin
        for (;;) {
            //如果当前线程被其他线程中断了
            //Thread.interrupted() 方法会返回true
            //同时会将打断标记设置为false
            if (Thread.interrupted()) {
                //当前线程所对应的节点出队列
                removeWaiter(q);
                //抛出异常
                throw new InterruptedException();
            }
            //假设当前线程是被其他线程使用unpark（thread）唤醒的话 会尝试自旋  走下面的逻辑
            //获取当前任务最新的状态
            int s = state;
            //说明任务已经执行完毕了 但是并不知道是失败 or 成功
            if (s > COMPLETING) {
                
                //条件成立 说明当前线程已经创建过node对象
                if (q != null)
                    //线程置空
                    q.thread = null;
                //返回当前线程池的状态
                return s;
            }
            //条件成立 说明当前任务接近完成状态 让当前线程释放cpu 
            else if (s == COMPLETING) // cannot time out yet
                Thread.yield();
            //1 , 第一步走到这里
            else if (q == null)
                //创建 WaitNode对象
                q = new WaitNode();
            //2，创建好WaitNode对象后 来到这里
            else if (!queued)
                //做入队操作
                //当前线程node节点 next指向 原队列的头节点 waiters 一直指向队列的头
                q.next = waiters
                //cas方式设置waiters引用指向 当前线程node 成功 queued == true 
                //否则 可能其他线程的节点在这个线程的节点之前入队列了
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                     waiters, q);
            //3，带超时时间的阻塞等待
            else if (timed) {
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                LockSupport.parkNanos(this, nanos);
            }
            //4，永久等待
            else
                LockSupport.park(this);
        }
    }


    //从队列中移除当前节点
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            //线程设置为Null
            node.thread = null;
            //循环退出标识
            retry:
            for (;;) {          // restart on removeWaiter race
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    if (q.thread != null)
                        //记录前一个结点
                        pred = q;
                    else if (pred != null) {
                        //直接将前一个结点指向当前结点的下一个结点
                        pred.next = s;
                        if (pred.thread == null) // check for race
                            continue retry;
                    }
                    //当前结点是头节点的情况 直接将当前结点的下一个结点设置为头结点
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                          q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    
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

}

```





## 二，AbstractExecutorService.submit()

```java
//可见 submit() 内部都是new一个 FutureTask对象
public Future<?> submit(Runnable task) {
    if (task == null) throw new NullPointerException();
    RunnableFuture<Void> ftask = newTaskFor(task, null);
    execute(ftask);
    return ftask;
}

public <T> Future<T> submit(Runnable task, T result) {
    if (task == null) throw new NullPointerException();
    RunnableFuture<T> ftask = newTaskFor(task, result);
    execute(ftask);
    return ftask;
}


public <T> Future<T> submit(Callable<T> task) {
    if (task == null) throw new NullPointerException();
    RunnableFuture<T> ftask = newTaskFor(task);
    execute(ftask);
    return ftask;
}


protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
    return new FutureTask<T>(callable);
}

```

## 测试代码

```java
@Slf4j
public class TestFuture {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        ExecutorService threadPool = Executors.newFixedThreadPool(5);
        Future<String> future1 = threadPool.submit(new CallableTask());
        String result = future1.get();
        log.debug("主线程拿到了结果 {}", result);
    }

    //测试结果
    //21:45:00.705 [pool-1-thread-1] DEBUG com.qyh.springmvcdemo.CallableTask - 开始 3s 的等待
    //21:45:03.708 [main] DEBUG com.qyh.springmvcdemo.TestFuture - 主线程拿到了结果 有返回值的任务

}

//有返回值的任务
@Slf4j
class CallableTask implements Callable<String> {

    @Override
    public String call() throws Exception {
        log.debug("开始 3s 的等待");
        Thread.sleep(3000);
        return "有返回值的任务";
    }
}
```

