import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

public class test {
    // 伪测试代码，不可运行，包的原因导致代码和源码一样，但是这里unsafe就不能获取
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        MyFutureTask<Integer> task1 = new MyFutureTask<>(new Runnable() {
            @Override
            public void run() {
                System.out.println("task1");
            }
        }, 1);
        MyFutureTask<Integer> task2 = new MyFutureTask<>(new Runnable() {
            @Override
            public void run() {
                System.out.println("task2");
            }
        }, 2);
        new Thread((Runnable) task1).start();
        new Thread((Runnable) task2).start();
        int res = task1.get() + task2.get();
        System.out.println(res);
    }

}
