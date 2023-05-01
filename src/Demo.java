import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;

// Future是一个接口，FutureTask是Future的实现类
public class Demo {
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
        int s3 = s1 + s2;
        long end = System.currentTimeMillis();
        System.out.println("结果：" + s3);
        // 输出总共执行的时间
        System.out.println("花费时间：" + (end - start) + "毫秒");  // 输出2013ms，说明异步
    }
}
