package com.hazelcast.spi;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.test.HazelcastJUnit4ClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import com.hazelcast.test.annotation.SerialTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

@RunWith(HazelcastJUnit4ClassRunner.class)
@Category(SerialTest.class)
public class RepartitioningStressTest extends HazelcastTestSupport {

    private BlockingQueue<HazelcastInstance> queue = new LinkedBlockingQueue<HazelcastInstance>();
    private HazelcastInstance hz;
    private TestHazelcastInstanceFactory instanceFactory;

    private final static long DURATION_SECONDS = 120;
    private final static int THREAD_COUNT = 10;

    @Before
    public void setUp() {
        instanceFactory = this.createHazelcastInstanceFactory(100000);
        hz = instanceFactory.newHazelcastInstance();

        for (int k = 0; k < 5; k++) {
            queue.add(instanceFactory.newHazelcastInstance());
        }
    }

    @Test
    public void callWithBackups() throws InterruptedException {
        final Map<Integer, Integer> map = hz.getMap("map");
        final int itemCount = 10000;
        for (int k = 0; k < itemCount; k++) {
            map.put(k, k);
        }

        RestartThread restartThread = new RestartThread();
        restartThread.start();

        TestThread[] testThreads = new TestThread[THREAD_COUNT];
        for (int l = 0; l < testThreads.length; l++) {
            testThreads[l] = new TestThread("Thread-" + l) {
                @Override
                void doRun() {
                    long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(DURATION_SECONDS);

                    Random random = new Random();
                    int k = 0;
                    for (; ; ) {
                        int key = random.nextInt(itemCount);
                        assertEquals(new Integer(key), map.put(key, key));

                        if (k % 10000 == 0) {
                            System.out.println(getName() + " at: " + k);
                        }

                        k++;

                        if (System.currentTimeMillis() > endTime) {
                            break;
                        }
                    }
                }
            };
            testThreads[l].start();
        }

        for (TestThread t : testThreads) {
            t.join();
            t.assertNotProblems();
        }

        restartThread.stop = true;
    }

    @Test
    public void callWithoutBackups() throws InterruptedException {
        final Map<Integer, Integer> map = hz.getMap("map");
        final int itemCount = 10000;
        for (int k = 0; k < itemCount; k++) {
            map.put(k, k);
        }

        RestartThread restartThread = new RestartThread();
        restartThread.start();

        TestThread[] testThreads = new TestThread[THREAD_COUNT];
        for (int k = 0; k < testThreads.length; k++) {
            testThreads[k] = new TestThread("GetThread-" + k) {
                @Override
                void doRun() {
                    long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(DURATION_SECONDS);

                    Random random = new Random();
                    int k = 0;
                    for (; ; ) {
                        int key = random.nextInt(itemCount);
                        assertEquals(new Integer(key), map.get(key));

                        if (k % 10000 == 0) {
                            System.out.println(getName() + " at: " + k);
                        }

                        k++;

                        if (System.currentTimeMillis() > endTime) {
                            break;
                        }
                    }
                }
            };
            testThreads[k].start();
        }

        for (TestThread t : testThreads) {
            t.join();
            t.assertNotProblems();
        }

        restartThread.stop = true;
    }

    private abstract class TestThread extends Thread {
        private volatile Throwable t;

        protected TestThread(String name) {
            super(name);
        }

        public final void run() {
            try {
                doRun();
            } catch (Throwable t) {
                this.t = t;
                t.printStackTrace();
            }
        }

        abstract void doRun();

        public void assertNotProblems() {
            if(t!=null){
                t.printStackTrace();
                fail(getName()+" failed with an exception:"+t.getMessage());
            }
        }
    }


    public class RestartThread extends Thread {

        private volatile boolean stop;

        public void run() {
            while (!stop) {
                try {
                    Thread.sleep(10000);
                    HazelcastInstance hz = queue.take();
                    hz.shutdown();
                    queue.add(instanceFactory.newHazelcastInstance());
                } catch (InterruptedException e) {
                }
            }
        }
    }
}
