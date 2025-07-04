
package org.jgroups.tests;


import org.jgroups.*;
import org.jgroups.util.Util;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;


/**
 * Tests various methods in JChannel
 * @author Bela Ban
 */
@Test(groups=Global.STACK_DEPENDENT)
public class ChannelTest extends ChannelTestBase {       

    public void testBasicOperations() throws Exception {
        JChannel a = createChannel().name("A");
        makeUnique(a);
        JChannel b=null;

        try {
            a.connect("testBasicOperations");
            assert a.isOpen();
            assert a.isConnected();
       
            assert a.getAddress() != null;
            assert a.getView() != null;
            assert a.getView().getMembers().contains(a.getAddress());
       
            a.connect("testBasicOperations");
            a.disconnect();
            assert a.isConnected() == false;
            assert a.isOpen();
            assert a.getAddress() == null;
            assert a.getView() == null;
            assert a.getClusterName() == null;
       
            a.connect("testBasicOperations");

            a.close();
       
            try {
                a.connect("testBasicOperations");
                assert false : "Should have generated exception, and it has not";
            }
            catch (Exception e) {
                assert e instanceof IllegalStateException;
            }
       
            assert a.isConnected() == false;
            assert a.isOpen() == false;
            assert a.getAddress() == null;
            assert a.getView() == null;
       
            assert a.getClusterName() == null;
       
            a = createChannel().name("A");
            b = createChannel().name("B");
            makeUnique(a,b);

            a.connect("testBasicOperations");
            b.connect("testBasicOperations");
       
            Util.sleep(1000);
       
            assert a.isOpen();
            assert a.isConnected();
       
            assert a.getAddress() != null;
            assert a.getView() != null;
            assert a.getView().getMembers().contains(a.getAddress());
            assert a.getView().getMembers().contains(b.getAddress());
       
            assert b.isOpen();
            assert b.isConnected();
       
            assert b.getAddress() != null;
            assert b.getView() != null;
            assert b.getView().getMembers().contains(b.getAddress());
            assert b.getView().getMembers().contains(a.getAddress());
       
            b.close();
            Util.sleep(1000);
       
            assert b.isOpen() == false;
            assert b.isConnected() == false;
       
            assert b.getAddress() == null;
            assert b.getView() == null;
       
            assert a.isOpen();
            assert a.isConnected();
       
            assert a.getAddress() != null;
            assert a.getView() != null;
            assert a.getView().getMembers().contains(a.getAddress());
            assert a.getView().getMembers().contains(b.getAddress()) == false;
        }
        finally {
            Util.close(a,b);
        }
    }


    public void testSendOnDisconnectedChannel() throws Exception {
        JChannel ch=createChannel();
        makeUnique(ch);
        try {
            ch.send(null, "hello world");
            assert false : "sending on a disconnected channel should have failed";
        }
        catch(IllegalStateException ex) {
            System.out.println("received \"" + ex + "\" as expected: sending on a disconnected channel is not allowed");
        }
    }

    public void testSendOnClosedChannel() throws Exception {
        JChannel ch=createChannel();
        makeUnique(ch);
        try {
            Util.close(ch);
            ch.send(null, "hello world");
            assert false : "sending on a closed channel should have failed";
        }
        catch(IllegalStateException ex) {
            System.out.println("received \"" + ex + "\" as expected: sending on a closed channel is not allowed");
        }
    }


    public void testViewChange() throws Exception {
        JChannel ch1 = createChannel();
        ViewChecker checker=new ViewChecker(ch1);
        ch1.setReceiver(checker);
        JChannel ch2=createChannel();
        makeUnique(ch1,ch2);
        try {
            ch1.connect("testViewChange");
            ch2.connect("testViewChange");
            assert checker.isSuccess();
            ch2.close();
            assert checker.isSuccess();
        }
        finally {
            Util.close(ch1,ch2);
        }
    }

    public void testViewChange2() throws Exception {
        JChannel a=createChannel().name("A");
        JChannel b=createChannel().name("B");
        makeUnique(a,b);
        a.connect("testViewChange2");
        b.connect("testViewChange2");
        Util.waitUntilAllChannelsHaveSameView(10000, 1000, a,b);
        List<Address> expectedMembers = Arrays.asList(a.getAddress(), b.getAddress());
        List<Address> mbrs=a.getView().getMembers();
        assert expectedMembers.equals(mbrs);
        assert mbrs.equals(expectedMembers);
    }


    public void testIsConnectedOnFirstViewChange() throws Exception {
        JChannel ch1 = createChannel().name("A");
        JChannel ch2=createChannel().name("B");
        makeUnique(ch1,ch2);
        ConnectedChecker tmp=new ConnectedChecker(ch2);
        ch2.setReceiver(tmp);
        try {
            ch1.connect("testIsConnectedOnFirstViewChange");
            ch2.connect("testIsConnectedOnFirstViewChange");
            assert tmp.isConnected();
        }
        finally {
            Util.close(ch1,ch2);
        }
    }



    public void testNoViewIsReceivedAfterDisconnect() throws Exception {
        JChannel ch1 = createChannel();
        JChannel ch2=createChannel();
        makeUnique(ch1,ch2);
        MyViewChecker ra = new MyViewChecker(ch2);
        ch2.setReceiver(ra);

        try {
            ch1.connect("testNoViewIsReceivedAfterDisconnect");
            ch2.connect("testNoViewIsReceivedAfterDisconnect");
            Util.sleep(500);
            ch2.disconnect();
            Util.sleep(1000);
            assert !ra.receivedViewWhenDisconnected : "Received view where not member";
        }
        finally {
            Util.close(ch1,ch2);
        }
    }


    public void testNoViewIsReceivedAfterClose() throws Exception {
        JChannel ch1 = createChannel();
        JChannel ch2=createChannel();
        makeUnique(ch1,ch2);
        MyViewChecker ra = new MyViewChecker(ch2);
        ch2.setReceiver(ra);

        try {
            ch1.connect("testNoViewIsReceivedAfterClose");
            ch2.connect("testNoViewIsReceivedAfterClose");
            Util.sleep(200);
            ch2.close();
            Util.sleep(1000);
            assert !ra.receivedViewWhenDisconnected : "Received view where not member";
        }
        finally {
            Util.close(ch1,ch2);
        }
    }


    @Test(expectedExceptions={NullPointerException.class})
    public void testNullMessage() throws Exception {
        JChannel ch1 = createChannel();
        makeUnique(ch1);
        try{
            ch1.connect("testNullMessage");
            ch1.send(null);
        }
        finally{
            Util.close(ch1);
        }             
    }


    public void testOrdering() throws Exception {
        final int NUM=100;
        JChannel ch=createChannel();
        makeUnique(ch);
        MyReceiver receiver=new MyReceiver(NUM);
        ch.setReceiver(receiver);
        try {
            ch.connect("testOrdering");
            for(int i=1;i <= NUM;i++) {
                ch.send(new BytesMessage(null, i));
            }
            receiver.waitForCompletion();
            List<Integer> nums=receiver.getNums();
            checkMonotonicallyIncreasingNumbers(nums);
        }
        finally {
            Util.close(ch);
        }
    }

    private static void checkMonotonicallyIncreasingNumbers(List<Integer> nums) {
        int current=-1;
        for(int num: nums) {
            if(current < 0) {
                current=num;
            }
            else {
                assert ++current == num : "list is " + nums;
            }
        }
    }


    private static class MyReceiver implements Receiver {
        final List<Integer> nums=new LinkedList<>();
        final int expected;

        public MyReceiver(int expected) {
            this.expected=expected;
        }

        public List<Integer> getNums() {
            return nums;
        }

        public void waitForCompletion() throws InterruptedException {
            synchronized(nums) {
                while(nums.size() < expected) {
                    nums.wait();
                }
            }
        }

        public void receive(Message msg) {
            Integer num=msg.getObject();
            synchronized(nums) {
                nums.add(num);
                if(nums.size() >= expected) {
                    nums.notifyAll();
                }
            }
        }
    }


    private static class ConnectedChecker implements Receiver {
        boolean connected=false;

        public ConnectedChecker(JChannel channel) {
            this.channel=channel;
        }

        final JChannel channel;

        public boolean isConnected() {
            return connected;
        }

        public void viewAccepted(View new_view) {
            connected=channel.isConnected();
        }
    }

    private static class ViewChecker implements Receiver {
        final JChannel channel;
        boolean success=true;

        public ViewChecker(JChannel channel) {
            this.channel=channel;
        }

        public boolean isSuccess() {
            return success;
        }

        public void viewAccepted(View new_view) {
            View view=channel.getView();
            // String str="viewAccepted(): channel's view=" + view + "\nreceived view=" + new_view;
            // System.out.println(str);           
            if(view!= null && !view.equals(new_view)) {
                success=false;
            }
        }
    }


    private static class MyViewChecker implements Receiver {
        private boolean receivedViewWhenDisconnected;
        private final JChannel ch;

        public MyViewChecker(JChannel ch) {
            this.ch=ch;
        }

        public void viewAccepted(View new_view) {
            receivedViewWhenDisconnected = !new_view.containsMember(ch.getAddress());
        }

    }



}


