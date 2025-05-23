package org.jgroups.protocols;

import org.jgroups.*;
import org.jgroups.stack.Protocol;
import org.jgroups.stack.ProtocolStack;
import org.jgroups.util.MyReceiver;
import org.jgroups.util.Util;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

/**
 * Tests unilateral closings of UNICAST connections. The test scenarios are described in doc/design/UNICAST2.txt.
 * Some of the tests may fail occasionally until https://issues.redhat.com/browse/JGRP-1594 is fixed
 * @author Bela Ban
 */
@Test(groups=Global.FUNCTIONAL,singleThreaded=true,dataProvider="configProvider")
public class UNICAST_ConnectionTests {
    protected JChannel            a, b;
    protected Address             a_addr, b_addr;
    protected MyReceiver<Integer> r1, r2;
    protected Protocol            u1, u2;
    protected static final String CLUSTER="UNICAST_ConnectionTests";


    @DataProvider
    static Object[][] configProvider() {
        return new Object[][]{
          {UNICAST3.class},
          {UNICAST4.class}
        };
    }

    protected void setup(Class<? extends Protocol> unicast_class) throws Exception {
        r1=new MyReceiver<Integer>().name("A");
        r2=new MyReceiver<Integer>().name("B");
        a=createChannel(unicast_class, "A");
        a.connect(CLUSTER);
        a_addr=a.getAddress();
        a.setReceiver(r1);
        u1=a.getProtocolStack().findProtocol(unicast_class);
        b=createChannel(unicast_class, "B");
        b.connect(CLUSTER);
        b_addr=b.getAddress();
        b.setReceiver(r2);
        u2=b.getProtocolStack().findProtocol(unicast_class);
    }


    @AfterMethod void stop() {Util.close(b, a);}


    /**
     * Tests cases #1 and #2 of UNICAST.new.txt
     * @throws Exception
     */
    @Test(dataProvider="configProvider")
    public void testRegularMessageReception(Class<? extends Protocol> unicast) throws Exception {
        setup(unicast);
        sendAndCheck(a, b_addr, 100, r2);
        sendAndCheck(b,a_addr,50,r1);
    }


    /**
     * Tests case #3 of UNICAST.new.txt
     */
    @Test(dataProvider="configProvider")
    public void testBothChannelsClosing(Class<? extends Protocol> unicast) throws Exception {
        setup(unicast);
        sendToEachOtherAndCheck(10);
        
        // now close the connections to each other
        System.out.println("==== Closing the connections on both sides");
        removeConnection(u1, b_addr);
        removeConnection(u2, a_addr);
        r1.reset(); r2.reset();

        // causes new connection establishment
        sendToEachOtherAndCheck(10);
    }


    /**
     * Scenario #4 (A closes the connection unilaterally (B keeps it open), then reopens it and sends messages)
     */
    @Test(dataProvider="configProvider")
    public void testAClosingUnilaterally(Class<? extends Protocol> unicast) throws Exception {
        setup(unicast);
        sendToEachOtherAndCheck(10);

        // now close connection on A unilaterally
        System.out.println("==== Closing the connection on A");
        removeConnection(u1, b_addr);

        // then send messages from A to B
        sendAndCheck(a, b_addr, 10, r2);
    }

    /**
     * Scenario #5 (B closes the connection unilaterally (A keeps it open), then A sends messages to B)
     */
    @Test(dataProvider="configProvider")
    public void testBClosingUnilaterally(Class<? extends Protocol> unicast) throws Exception {
        setup(unicast);
        sendToEachOtherAndCheck(10);

        // now close connection on A unilaterally
        System.out.println("==== Closing the connection on B");
        removeConnection(u2, a_addr);

        // then send messages from A to B
        sendAndCheck(a, b_addr, 10, r2);
    }

    public void testBRemovingUnilaterally(Class<? extends Protocol> unicast) throws Exception {
        setup(unicast);
        sendAndCheck(a, b_addr, 10, r2);

        // now remove connection on A unilaterally
        System.out.println("==== Removing the connection on B");
        removeConnection(u2, a_addr, true);

        // then send messages from A to B
        sendAndCheck(a, b_addr, 10, r2);
    }

    public void testBRemovingUnilaterallyOOB(Class<? extends Protocol> unicast) throws Exception {
        setup(unicast);
        sendAndCheck(a, b_addr, 10, r2);

        // now remove connection on A unilaterally
        System.out.println("==== Removing the connection on B");
        removeConnection(u2, a_addr, true);

        // then send OOB messages from A to B
        sendAndCheck(a, b_addr, true, 10, r2);
    }


    /**
     * Scenario #6 (A closes the connection unilaterally (B keeps it open), then reopens it and sends messages,
     * but loses the first message
     */
    @Test(dataProvider="configProvider")
    public void testAClosingUnilaterallyButLosingFirstMessage(Class<? extends Protocol> unicast) throws Exception {
        setup(unicast);
        sendAndCheck(a, b_addr, 10, r2);

        // now close connection on A unilaterally
        System.out.println("==== Closing the connection on A");
        removeConnection(u1, b_addr);

        // add a Drop protocol to drop the first unicast message
        Drop drop=new Drop(true);
        a.getProtocolStack().insertProtocol(drop, ProtocolStack.Position.BELOW, Util.getUnicastProtocols());

        // then send messages from A to B
        sendAndCheck(a, b_addr, 10, r2);
    }

    /** Tests concurrent reception of multiple messages with a different conn_id (https://issues.redhat.com/browse/JGRP-1347) */
    @Test(dataProvider="configProvider")
    public void testMultipleConcurrentResets(Class<? extends Protocol> unicast) throws Exception {
        setup(unicast);
        sendAndCheck(a, b_addr, 1, r2);

        // now close connection on A unilaterally
        System.out.println("==== Closing the connection on A");
        removeConnection(u1, b_addr);

        r2.reset();
        final Protocol ucast=b.getProtocolStack().findProtocol(Util.getUnicastProtocols());

        int NUM=10;
        final List<Message> msgs=new ArrayList<>(NUM);

        for(int i=1; i <= NUM; i++) {
            Message msg=new BytesMessage(b_addr, i).setSrc(a_addr);
            Header hdr=createDataHeader(ucast, 1, (short)2, true);
            msg.putHeader(ucast.getId(), hdr);
            msgs.add(msg);
        }


        Thread[] threads=new Thread[NUM];
        final CyclicBarrier barrier=new CyclicBarrier(NUM+1);
        for(int i=0; i < NUM; i++) {
            final int index=i;
            threads[i]=new Thread(() -> {
                try {
                    barrier.await();
                    ucast.up(msgs.get(index));
                }
                catch(Exception e) {
                    e.printStackTrace();
                }
            });
            threads[i].start();
        }

        barrier.await();
        for(Thread thread: threads)
            thread.join();

        List<Integer> list=r2.list();
        System.out.println("list = " + print(list));

        assert list.size() == 1 : "list must have 1 element but has " + list.size() + ": " + print(list);
    }

    @Test(dataProvider="configProvider")
    public void testMessageToNonExistingMember(Class<? extends Protocol> unicast) throws Exception {
        setup(unicast);
        for(JChannel ch: List.of(a,b))
            Util.invoke(ch.stack().findProtocol(unicast), "setMaxRetransmitTime", 5000L);
        Address target=Util.createRandomAddress("FakeAddress");
        a.send(target, "hello");
        Protocol prot=a.getProtocolStack().findProtocol(unicast);
        Method hasSendConnectionTo=unicast.getMethod("hasSendConnectionTo", Address.class);
        for(int i=0; i < 10; i++) {
            boolean result=(Boolean)hasSendConnectionTo.invoke(prot, target);
            if(!result)
                break;
            Util.sleep(1000);
        }
        assert !(Boolean)hasSendConnectionTo.invoke(prot, target);
    }

    protected static Header createDataHeader(Protocol unicast, long seqno, short conn_id, boolean first) {
        if(unicast instanceof UNICAST3)
            return UnicastHeader3.createDataHeader(seqno, conn_id, first);
        else if(unicast instanceof UNICAST4)
            return UnicastHeader.createDataHeader(seqno, conn_id, first);
        throw new IllegalArgumentException("protocol " + unicast.getClass().getSimpleName() + " needs to be UNICAST3");
    }


    /**
     * Send num unicasts on both channels and verify the other end received them
     * @param num
     * @throws Exception
     */
    protected void sendToEachOtherAndCheck(int num) throws Exception {
        for(int i=1; i <= num; i++) {
            a.send(b_addr, i);
            b.send(a_addr, i);
        }
        List<Integer> l1=r1.list();
        List<Integer> l2=r2.list();
        for(int i=0; i < 10; i++) {
            if(l1.size()  == num && l2.size() == num)
                break;
            Util.sleep(500);
        }
        System.out.println("l1 = " + print(l1));
        System.out.println("l2 = " + print(l2));
        assert l1.size() == num;
        assert l2.size() == num;
    }

    protected static void sendAndCheck(JChannel channel, Address dest, int num, MyReceiver<Integer> r) throws Exception {
        sendAndCheck(channel, dest, false, num, r);
    }

    protected static void sendAndCheck(JChannel channel, Address dest, boolean oob, int num, MyReceiver<Integer> r) throws Exception {
        r.reset();
        for(int i=1; i <= num; i++) {
            Message msg=new ObjectMessage(dest, i);
            if(oob)
                msg.setFlag(Message.Flag.OOB);
            channel.send(msg);
        }
        List<Integer> list=r.list();
        Util.waitUntilTrue(10000, 500, () -> list.size() == num);
        System.out.println("list = " + list);
        int size=list.size();
        assert size == num : "list has " + size + " elements (expected " + num + "): " + list;
    }

    protected static void removeConnection(Protocol prot, Address target) throws Exception {
        removeConnection(prot, target, false);
    }

    protected static void removeConnection(Protocol prot, Address target, boolean remove) throws Exception {
        if(remove)
            Util.invoke(prot, "removeReceiveConnection", target);
        else
            Util.invoke(prot, "closeConnection", target);
    }

    protected static String print(List<Integer> list) {
        return Util.printListWithDelimiter(list, " ");
    }

    protected static JChannel createChannel(Class<? extends Protocol> unicast_class, String name) throws Exception {
        Protocol unicast=unicast_class.getDeclaredConstructor().newInstance();
        return new JChannel(new SHARED_LOOPBACK(), unicast).name(name);
    }


    protected static class Drop extends Protocol {
        protected volatile boolean drop_next=false;

        protected Drop(boolean drop_next) {
            this.drop_next=drop_next;
        }

        public String getName() {
            return "Drop";
        }

        public void dropNext() {
            drop_next=true;
        }

        public Object down(Message msg) {
            if(drop_next) {
                drop_next=false;
                return null;
            }
            return super.down(msg);
        }
    }
}
