package org.jgroups.protocols;


import org.jgroups.*;
import org.jgroups.stack.Protocol;
import org.jgroups.util.Util;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for contention on UNICAST, measured by the number of retransmissions in UNICAST 
 * @author Bela Ban
 */
@Test(groups=Global.FUNCTIONAL, singleThreaded=true)
public class UNICAST_ContentionTest {
    JChannel         a, b;
    static final int NUM_THREADS =  100;
    static final int NUM_MSGS    =  100;
    static final int SIZE        = 1000; // default size of a message in bytes

    @AfterMethod
    protected void tearDown() throws Exception {
        Util.close(b,a);
    }

    @DataProvider
    static Object[][] provider() {
        return new Object[][] {
          {UNICAST3.class},
          {UNICAST4.class}
        };
    }



    @Test(dataProvider="provider")
    public void testSimpleMessageReception(Class<? extends Protocol> unicast_class) throws Exception {
        a=create(unicast_class, "A");
        b=create(unicast_class, "B");
        MyReceiver r1=new MyReceiver("A"), r2=new MyReceiver("B");
        a.setReceiver(r1);
        b.setReceiver(r2);
        a.connect("testSimpleMessageReception");
        b.connect("testSimpleMessageReception");

        int NUM=100;
        Address c1_addr=a.getAddress(), c2_addr=b.getAddress();
        for(int i=1; i <= NUM; i++) {
            a.send(c1_addr,"bla");
            a.send(c2_addr,"bla");
            b.send(c2_addr,"bla");
            b.send(c1_addr,"bla");
        }

        for(int i=0; i < 10; i++) {
            if(r1.num() == NUM * 2 && r2.num() == NUM * 2)
                break;
            Util.sleep(500);
        }

        System.out.printf("%s: %,d msgs, %s xmits\n", "c1",  r1.num(),
                          Util.invoke(a.stack().findProtocol(Util.getUnicastProtocols()), "getNumXmits"));
        System.out.printf("%s: %,d msgs, %s xmits\n", "c2",  r2.num(),
                          Util.invoke(b.stack().findProtocol(Util.getUnicastProtocols()), "getNumXmits"));

        assert r1.num() == NUM * 2: "expected " + NUM *2 + ", but got " + r1.num();
        assert r2.num() == NUM * 2: "expected " + NUM *2 + ", but got " + r2.num();
    }


    /** Multiple threads (NUM_THREADS) send messages (NUM_MSGS) */
    @Test(dataProvider="provider")
    public void testMessageReceptionUnderHighLoad(Class<? extends Protocol> unicast_class) throws Exception {
        CountDownLatch latch=new CountDownLatch(1);
        a=create(unicast_class, "A");
        b=create(unicast_class, "B");
        MyReceiver r1=new MyReceiver("A"), r2=new MyReceiver("B");
        a.setReceiver(r1);
        b.setReceiver(r2);
        a.connect("testSimpleMessageReception");
        b.connect("testSimpleMessageReception");

        Address c1_addr=a.getAddress(), c2_addr=b.getAddress();
        MySender[] c1_senders=new MySender[NUM_THREADS];
        for(int i=0; i < c1_senders.length; i++) {
            c1_senders[i]=new MySender(a, c2_addr, latch);
            c1_senders[i].start();
        }
        MySender[] c2_senders=new MySender[NUM_THREADS];
        for(int i=0; i < c2_senders.length; i++) {
            c2_senders[i]=new MySender(b, c1_addr, latch);
            c2_senders[i].start();
        }

        latch.countDown(); // starts all threads

        for(MySender sender: c1_senders)
            sender.join();
        for(MySender sender: c2_senders)
            sender.join();
        System.out.println("Senders are done, waiting for all messages to be received");

        long NUM_EXPECTED_MSGS=NUM_THREADS * NUM_MSGS;

        for(int i=0; i < 20; i++) {
            if(r1.num() == NUM_EXPECTED_MSGS && r2.num() == NUM_EXPECTED_MSGS)
                break;
            Util.sleep(2000);
        }

        System.out.printf("%s: %,d msgs, %s xmits\n", "c1",  r1.num(),
                          Util.invoke(a.stack().findProtocol(Util.getUnicastProtocols()), "getNumXmits"));
        System.out.printf("%s: %,d msgs, %s xmits\n", "c2",  r2.num(),
                          Util.invoke(b.stack().findProtocol(Util.getUnicastProtocols()), "getNumXmits"));

        assert r1.num() == NUM_EXPECTED_MSGS : "expected " + NUM_EXPECTED_MSGS + ", but got " + r1.num();
        assert r2.num() == NUM_EXPECTED_MSGS : "expected " + NUM_EXPECTED_MSGS + ", but got " + r2.num();
    }

    protected static JChannel create(Class<? extends Protocol> unicast_class, String name) throws Exception {
        Protocol p=unicast_class.getDeclaredConstructor().newInstance();
        Util.invoke(p, "setXmitInterval", 500L);
        return new JChannel(new SHARED_LOOPBACK(), p).name(name);
    }


    private static class MySender extends Thread {
        private final JChannel ch;
        private final Address dest;
        private final CountDownLatch latch;
        private final byte[] buf=new byte[SIZE];

        public MySender(JChannel ch, Address dest, CountDownLatch latch) {
            this.ch=ch;
            this.dest=dest;
            this.latch=latch;
        }


        public void run() {
            try {
                latch.await();
            }
            catch(InterruptedException e) {
                e.printStackTrace();
            }
            for(int i=0; i < NUM_MSGS; i++) {
                try {
                    Message msg=new BytesMessage(dest, buf);
                    ch.send(msg);
                }
                catch(Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }



    private static class MyReceiver implements Receiver {
        final String name;
        final AtomicInteger num=new AtomicInteger(0);
        static final long MOD=NUM_MSGS * NUM_THREADS / 10;

        public MyReceiver(String name) {
            this.name=name;
        }

        public void receive(Message msg) {
            if(num.incrementAndGet() % MOD == 0) {
                System.out.println("[" + name + "] received " + num() + " msgs");
            }
        }

        public int num() {
            return num.get();
        }
    }




}