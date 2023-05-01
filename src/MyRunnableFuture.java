public interface MyRunnableFuture<V> extends MyRunnable, MyFuture<V> {
    void run();
}
