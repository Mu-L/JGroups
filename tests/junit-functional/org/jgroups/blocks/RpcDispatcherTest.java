package org.jgroups.blocks;


import org.jgroups.*;
import org.jgroups.protocols.FRAG;
import org.jgroups.protocols.FRAG2;
import org.jgroups.protocols.TP;
import org.jgroups.stack.Protocol;
import org.jgroups.util.RpcStats;
import org.jgroups.util.Rsp;
import org.jgroups.util.RspList;
import org.jgroups.util.Util;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.jgroups.Message.Flag.DONT_BUNDLE;
import static org.jgroups.Message.Flag.OOB;
import static org.jgroups.blocks.ResponseMode.GET_ALL;

/**
 * A collection of tests to test the RpcDispatcher.
 * NOTE on processing return values:
 * The method RspDispatcher.callRemoteMethods(...) returns an RspList, containing one Rsp
 * object for each group member receiving the RPC call. Rsp.getValue() returns the 
 * value returned by the RPC call from the corresponding member. Rsp.getValue() may
 * contain several classes of values, depending on what happened during the call:
 * (i) a value of the expected return data type, if the RPC call completed successfully
 * (ii) null, if the RPC call timed out before the value could be returned
 * (iii) an object of type java.lang.Throwable, if an exception (e.g. lava.lang.OutOfMemoryException) 
 * was raised during the processing of the call 
 *
 * It is wise to check for such cases when processing RpcDispatcher calls.
 * 
 * This also applies to the return value of callRemoteMethod(...).
 * 
 * @author Bela Ban
 */
@Test(groups=Global.FUNCTIONAL,singleThreaded=true)
public class RpcDispatcherTest {
    protected RpcDispatcher       da, db, dc;
    protected JChannel            a, b, c;
    protected static final String GROUP="RpcDispatcherTest";

    // specify return values sizes which should work correctly with a 64Mb heap
    final static int[] SIZES={10000, 20000, 40000, 80000, 100000, 200000, 400000, 800000,
        1000000, 2000000, 5000000};
    // timeout (in secs) for large value tests
    final static int LARGE_VALUE_TIMEOUT = 60;

    @BeforeMethod
    protected void setUp() throws Exception {
        a=createChannel("A");
        da=new RpcDispatcher(a, new ServerObject("A", 1));
        a.connect(GROUP);

        b=createChannel("B");
        db=new RpcDispatcher(b, new ServerObject("B", 2));
        b.connect(GROUP);

        c=createChannel("C");
        dc=new RpcDispatcher(c, new ServerObject("C", 3));
        c.connect(GROUP);

        Util.waitUntilAllChannelsHaveSameView(10000, 1000, a, b, c);
        System.out.println("A=" + a.getView() + "\nB=" + b.getView() + "\nC=" + c.getView());
    }

    @AfterMethod
    protected void tearDown() throws Exception {
        Util.close(dc, db, da, c, b, a);
    }

    public void testEmptyConstructor() throws Exception {
        RpcDispatcher d1=new RpcDispatcher(), d2=new RpcDispatcher();
        JChannel      d=null, e=null;

        try {
            d=createChannel("D");
            e=createChannel("E");
            d1.setChannel(d);
            d2.setChannel(e);
            d1.setServerObject(new ServerObject("D", 1));
            d2.setServerObject(new ServerObject("E", 2));
            d1.start();
            d2.start();
            d.connect("RpcDispatcherTest-DifferentGroup");
            e.connect("RpcDispatcherTest-DifferentGroup");

            Util.sleep(500);

            View view=e.getView();
            System.out.println("view channel 2= " + view);

            view=d.getView();
            System.out.println("view channel 1= " + view);

            assert view.size() == 2;
            RspList<Integer> rsps=d1.callRemoteMethods(null, "foo", null, null, new RequestOptions(GET_ALL, 5000));
            System.out.println("rsps:\n" + rsps);
            assert rsps.size() == 2;
            for(Rsp<Integer> rsp: rsps.values()) {
                assert rsp.wasReceived();
                assert !rsp.wasSuspected();
                assert rsp.getValue() != null;
            }


            Object server_object=new Object() {
                @SuppressWarnings("unused")
                public long foobar() {
                    return System.currentTimeMillis();
                }
            };
            d1.setServerObject(server_object);
            d2.setServerObject(server_object);

            rsps=d2.callRemoteMethods(null, "foobar", null, null, new RequestOptions(GET_ALL, 5000));
            System.out.println("rsps:\n" + rsps);
            assert rsps.size() == 2;
            for(Rsp<Integer> rsp: rsps.values()) {
                assert rsp.wasReceived();
                assert !rsp.wasSuspected();
                assert rsp.getValue() != null;
            }
        }
        finally {
            d2.stop();
            d1.stop();
            Util.close(e, d);
        }
    }


    public void testException() throws Exception {
        RspList<Object> rsps=da.callRemoteMethods(null, "throwException", null, null, new RequestOptions(GET_ALL, 5000));
        rsps.values().forEach(System.out::println);
        for(Rsp<Object> rsp: rsps.values())
            assert rsp.getException() != null && rsp.getValue() == null;
    }


    public void testExceptionAsReturnValue() throws Exception {
        RspList<Object> rsps=da.callRemoteMethods(null, "returnException", null, null, new RequestOptions(GET_ALL, 5000));
        rsps.values().forEach(System.out::println);
        for(Rsp<Object> rsp: rsps.values())
            assert rsp.getException() == null && rsp.getValue() != null && rsp.getValue() instanceof Throwable;
    }

    public void testUnicastInvocation() throws Exception {
        RequestOptions opts=RequestOptions.SYNC().timeout(2000);
        Void result=da.callRemoteMethod(b.getAddress(), "bar", null, null, opts);
        assert result == null;

        Integer res=da.callRemoteMethod(b.getAddress(), "foo", null, null, opts);
        assert res != null && res == 2;
    }

    public void testUnicastInvocationWithTimeout() throws Exception {
        RequestOptions opts=RequestOptions.SYNC().timeout(1000);
        Method meth=ServerObject.class.getDeclaredMethod("sleep", long.class);
        long start=System.currentTimeMillis();
        try {
            da.callRemoteMethod(b.getAddress(), new MethodCall(meth, 5000), opts);
            assert false: "should have thrown a TimeoutException";
        }
        catch(TimeoutException ex) {
            long time=System.currentTimeMillis()-start;
            System.out.printf("received %s as expected; call took ~%d ms\n", ex, time);
        }
    }

    public void testUnicastInvocationWithFutureAndTimeout() throws Exception {
        RequestOptions opts=RequestOptions.SYNC().timeout(6000);
        Method meth=ServerObject.class.getDeclaredMethod("sleep", long.class);
        CompletableFuture<Long> future;
        long start=System.currentTimeMillis();
        future=da.callRemoteMethodWithFuture(b.getAddress(), new MethodCall(meth, 5000), opts);
        try {
            future.get(1000, TimeUnit.MILLISECONDS);
            assert false : "should have thrown a TimeoutException";
        }
        catch(TimeoutException ex) {
            long time=System.currentTimeMillis()-start;
            System.out.printf("received %s as expected; call took ~%d ms\n", ex, time);
        }
    }

    public void testUnicastInvocationWithFuture() throws Exception {
        RequestOptions opts=RequestOptions.SYNC().timeout(2000).flags(OOB);
        MethodCall call=new MethodCall("bar", null, null);
        CompletableFuture<Void> future=da.callRemoteMethodWithFuture(b.getAddress(), call, opts);
        Void result=future.get(10000, TimeUnit.MILLISECONDS);
        assert result == null;

        call=new MethodCall("foo", null, null);
        CompletableFuture<Integer> fut=da.callRemoteMethodWithFuture(b.getAddress(), call, opts);
        Integer res=fut.get(10000, TimeUnit.MILLISECONDS);
        assert res != null && res == 2;

        Method meth=ServerObject.class.getDeclaredMethod("sleep", long.class);
        try {
            CompletableFuture<Long> f=da.callRemoteMethodWithFuture(b.getAddress(), new MethodCall(meth, 5000), opts);
            f.get(100, TimeUnit.MILLISECONDS);
            assert false: "should have thrown a TimeoutException";
        }
        catch(TimeoutException ex) {
            System.out.printf("received %s as expected\n", ex);
        }

        try {
            meth=ServerObject.class.getDeclaredMethod("throwException");
            call=new MethodCall(meth);
            CompletableFuture<Object> f=da.callRemoteMethodWithFuture(b.getAddress(), call, opts);
            f.get();
            assert false : "should have thrown ExecutionException";
        }
        catch(ExecutionException ex) {
            System.out.printf("received %s as expected\n", ex);
            assert ex.getCause() instanceof Exception;
        }
    }

    public void testUnicastException()  {
        try {
            da.callRemoteMethod(b.getAddress(), "throwException", null, null, new RequestOptions(GET_ALL, 5000));
        }
        catch(Throwable throwable) {
            System.out.println("received exception (as expected)");
        }
    }

    public void testUnicastExceptionNested()  {
        try {
            da.callRemoteMethod(b.getAddress(), "throwExceptionNested", null, null, new RequestOptions(GET_ALL, 5000));
        }
        catch(Throwable throwable) {
            System.out.printf("received exception (as expected): %s\n", throwable);
            assert throwable instanceof IllegalArgumentException;
            assert throwable.getCause() instanceof NullPointerException;
        }
    }

    public void testAsyncUnicast() throws Exception {
        MethodCall call=new MethodCall(ServerObject.class.getMethod("foo"));
        Integer result=da.callRemoteMethod(b.getAddress(), call, RequestOptions.ASYNC());
        assert result == null;
    }

    public void testAsyncUnicastWithFuture() throws Exception {
        MethodCall call=new MethodCall(ServerObject.class.getMethod("throwException"));
        Future<Object> future=da.callRemoteMethodWithFuture(b.getAddress(), call, RequestOptions.ASYNC());
        assert future == null;
    }


    public void testUnicastExceptionWithFuture()  {
        try {
            MethodCall call=new MethodCall(ServerObject.class.getMethod("throwException"));
            Future<Object> future=da.callRemoteMethodWithFuture(b.getAddress(), call, new RequestOptions(GET_ALL, 5000));
            Object val=future.get();
            assert val == null;
            assert false : " should not get here";
        }
        catch(Throwable throwable) {
            System.out.println("received exception (as expected): " + throwable);
        }
    }


    public void testUnicastExceptionAsReturnValue() throws Exception {
        Object rsp=da.callRemoteMethod(b.getAddress(), "returnException", null, null, new RequestOptions(GET_ALL, 5000));
        System.out.println("rsp = " + rsp);
        assert rsp instanceof Throwable;
    }

    public void testUnicastExceptionAsReturnValueWithFuture() throws Exception {
        MethodCall call=new MethodCall(ServerObject.class.getMethod("returnException"));
        Future<Object> future=da.callRemoteMethodWithFuture(b.getAddress(), call, new RequestOptions(GET_ALL, 5000));
        Object val=future.get();
        assert val instanceof Exception;
    }

    public void testMulticastInvocationWithMethodLookup() throws Exception {
        MethodCall call=new MethodCall((short)6, 3, 4); // ServerObject.add()
        Stream.of(da,db,dc).forEach(d -> d.setMethodLookup(id -> ServerObject.methods[id]));
        RspList<Integer> rsps=da.callRemoteMethods(null, call, RequestOptions.SYNC());
        System.out.printf("rsps:\n%s\n", rsps);
        assert rsps != null;
        assert rsps.size() == 3;
        for(Rsp<Integer> rsp: rsps.values())
            assert rsp.getValue() != null && rsp.getValue().equals(7);
    }

    public void testMulticastInvocationWithTimeout() throws Exception {
        RequestOptions opts=RequestOptions.SYNC().timeout(1000);
        Method meth=ServerObject.class.getDeclaredMethod("sleep", long.class);
        long start=System.currentTimeMillis();
        RspList<Long> rsps=da.callRemoteMethods(null, new MethodCall(meth, 5000), opts);
        long time=System.currentTimeMillis()-start;
        System.out.printf("responses:\n%s\ncall took ~%d ms\n", rsps, time);
        rsps.values().stream().noneMatch(Rsp::wasReceived);
    }

    public void testMulticastInvocationWithFutureAndTimeout() throws Exception {
        RequestOptions opts=RequestOptions.SYNC().timeout(1000);
        Method meth=ServerObject.class.getDeclaredMethod("sleep", long.class);
        CompletableFuture<RspList<Long>> future=da.callRemoteMethodsWithFuture(null, new MethodCall(meth, 5000), opts);
        RspList<Long> rsps=future.get(100, TimeUnit.MILLISECONDS);
        System.out.printf("rsps:\n%s\n", rsps);
        assert rsps != null;
        assert rsps.values().stream().noneMatch(Rsp::wasReceived);
    }

    /**
     * Test the response filter mechanism which can be used to filter responses received with
     * a call to RpcDispatcher.
     * 
     * The test filters requests based on the id of the server object they were received
     * from, and only accept responses from servers with id > 1. 
     * 
     * The expected behaviour is that the response from server 1 is rejected, but the responses 
     * from servers 2 and 3 are accepted.
     *
     */
    public void testResponseFilter() throws Exception {
        RequestOptions options=new RequestOptions(GET_ALL, 10000, false,
                                                  new RspFilter() {
                                                      int num=0;
                                                      public boolean isAcceptable(Object response, Address sender) {
                                                          boolean retval=(Integer)response > 1;
                                                          if(retval)
                                                              num++;
                                                          return retval;
                                                      }

                                                      public boolean needMoreResponses() {
                                                          return num < 2;
                                                      }
                                                  });

        RspList<Integer> rsps=da.callRemoteMethods(null, "foo", null, null, options);
        System.out.println("responses are:\n" + rsps);
        assert rsps.size() == 3 : "there should be three response values";
        assert rsps.numReceived() == 2 : "number of responses received should be 2";
    }


    /** Test a unicast blocking RPC with a stupid response filter which never terminates */
    public void testResponseFilterWithUnicast() throws Exception {
        RequestOptions options=RequestOptions.SYNC().timeout(5000).rspFilter(
          new RspFilter() {
              public boolean isAcceptable(Object response, Address sender) {return false;}
              public boolean needMoreResponses() {return true;}
          });

        Object retval=da.callRemoteMethod(b.getAddress(), "bar", null, null, options);
        System.out.println("retval = " + retval);
        assert retval == null;
    }



    /**
     * Tests an incorrect response filter which always returns false for isAcceptable() and true for needsMoreResponses().
     * The call should return anyway after having received all responses, even if none of them was accepted by the
     * filter.
     */
    public void testNonTerminatingResponseFilter() throws Exception {
        RequestOptions options=new RequestOptions(GET_ALL, 10000, false,
                                                  new RspFilter() {
                                                      public boolean isAcceptable(Object response, Address sender) {
                                                          return false;
                                                      }
                                                      public boolean needMoreResponses() {return true;}
                                                  });

        RspList<Integer> rsps=da.callRemoteMethods(null, "foo", null, null, options);
        System.out.println("responses are:\n" + rsps);
        assert 3 == rsps.size();
        assert 0 == rsps.numReceived();
    }

    /**
     * Runs with response mode of GET_FIRST and the response filter accepts only the last response
     * @throws Exception
     */
    public void testAcceptLastResponseFilter() throws Exception {
        RequestOptions options=new RequestOptions(ResponseMode.GET_FIRST, 10000, false,
                                                  new RspFilter() {
                                                      int count=0;
                                                      public boolean isAcceptable(Object response, Address sender) {
                                                          return ++count >= 3;
                                                      }
                                                      public boolean needMoreResponses() {return count < 3;}
                                                  });

        RspList<Integer> rsps=da.callRemoteMethods(null, "foo", null, null, options);
        System.out.println("responses are:\n" + rsps);
        assert 3 == rsps.size();
        assert 1 == rsps.numReceived();
    }


    public void testFuture() throws Exception {
        MethodCall sleep=new MethodCall("sleep", new Object[]{5000L}, new Class[]{long.class});
        CompletableFuture<RspList<Long>> future=da.callRemoteMethodsWithFuture(null, sleep,
                                                                               RequestOptions.SYNC().timeout(5000));
        assert !future.isDone();
        assert !future.isCancelled();

        RspList<Long> rsps=future.get(300, TimeUnit.MILLISECONDS);
        long num_not_received=rsps.values().stream().filter(rsp -> !rsp.wasReceived()).count();
        System.out.printf("rsps:\n%s\nnot received: %d\n", rsps, num_not_received);
        assert rsps.size() == 3;
        assert num_not_received == 3 : "none of the 3 requests should have received a response, rsps:\n" + rsps;
        assert future.isDone();
    }


    public void testNotifyingFuture() throws Exception {
        MethodCall sleep=new MethodCall("sleep", new Object[]{1000L}, new Class[]{long.class});
        CompletableFuture<RspList<Long>> future=da.callRemoteMethodsWithFuture(null, sleep, new RequestOptions(GET_ALL, 5000L));
        assert !future.isDone();
        assert !future.isCancelled();
        for(int i=0; i < 10; i++) {
            if(future.isDone())
                break;
            Util.sleep(1000);
        }
        assert future.isDone();
        RspList<Long> result=future.get(1L, TimeUnit.MILLISECONDS);
        System.out.println("result:\n" + result);
        assert result != null;
        assert result.size() == 3;
        assert future.isDone();

        RspList<Long> result2=future.get();
        System.out.println("result2:\n" + result2);
        assert result2 != null;
        assert result2.size() == 3;
        assert future.isDone();
    }

    public void testNotifyingFutureWithDelayedListener() throws Exception {
        MethodCall sleep=new MethodCall("sleep", new Object[]{100L}, new Class[]{long.class});
        CompletableFuture<RspList<Long>> future=da.callRemoteMethodsWithFuture(null, sleep, new RequestOptions(GET_ALL, 5000L));
        assert !future.isDone();
        assert !future.isCancelled();

        Util.sleep(2000);
        assert future.isDone();
        RspList<Long> result=future.get(1L, TimeUnit.MILLISECONDS);
        System.out.println("result:\n" + result);
        assert result != null;
        assert result.size() == 3;
    }


    /**
     * Invoke a call which sleeps for 2s 5 times. Since the sleep should be done in parallel (OOB msgs), all 5 futures
     * should be done in roughly 2s. JIRA: https://issues.redhat.com/browse/JGRP-2039
     */
    public void testMultipleFutures() throws Exception {
        _testMultipleUnicastFuturesToDest(null); // send to all
    }

    public void testMultipleUnicastFuturesToSelf() throws Exception {
        _testMultipleUnicastFuturesToDest(a.getAddress());
    }

    public void testMultipleUnicastFuturesToOther() throws Exception {
        _testMultipleUnicastFuturesToDest(b.getAddress());
    }

    /**
     * Invoke a call which sleeps for 2s 5 times. Since the sleep should be done in parallel (OOB msgs), all 5 futures
     * should be done in roughly 2s. JIRA: https://issues.redhat.com/browse/JGRP-2039
     */
    protected void _testMultipleUnicastFuturesToDest(Address dest) throws Exception {
        final int       NUM_CALLS=5, MAX_SLEEP=4000; // should be done in ~2s, make it 4s to be safe
        List<Future<?>> futures=new ArrayList<>();
        RequestOptions  opts=new RequestOptions(GET_ALL, 30000L).flags(OOB, DONT_BUNDLE);

        long start=System.currentTimeMillis();
        for(int i=0; i < NUM_CALLS; i++) {
            Future<?> future;
            MethodCall sleep=new MethodCall("sleep", new Object[]{i+1,2000L}, new Class[]{int.class, long.class});
            if(dest == null)
                future=da.callRemoteMethodsWithFuture(null, sleep, opts);
            else
                future=da.callRemoteMethodWithFuture(dest, sleep, opts);
            futures.add(future);
        }
        Util.waitUntilTrue(MAX_SLEEP, 50, () -> futures.stream().allMatch(Future::isDone));
        long time=System.currentTimeMillis() - start;
        System.out.printf("\n%d responses (in %d ms):\n", futures.size(), time);
        futures.forEach(f -> {
            Object ret=null;
            try {
                ret=f.get();
            }
            catch(Exception e) {
                ret=e.toString();
            }
            System.out.printf("%s\n", ret);
        });
        assert futures.size() == NUM_CALLS;
        assert time < MAX_SLEEP;
    }


    public void testMultipleNotifyingFutures() throws Exception {
        MethodCall sleep=new MethodCall("sleep", new Object[]{100L}, new Class[]{long.class});
        List<CompletableFuture<RspList<Long>>> listeners=new ArrayList<>();
        RequestOptions options=new RequestOptions(GET_ALL, 30000L);
        for(int i=0; i < 10; i++) {
            CompletableFuture<RspList<Long>> f=da.callRemoteMethodsWithFuture(null, sleep, options);
            listeners.add(f);
        }

        Util.sleep(1000);
        for(int i=0; i < 10; i++) {
            boolean all_done=true;
            for(CompletableFuture<RspList<Long>> listener: listeners) {
                boolean done=listener.isDone();
                System.out.print(done? "+ " : "- ");
                if(!listener.isDone())
                    all_done=false;
            }
            if(all_done)
                break;
            Util.sleep(500);
            System.out.println();
        }
        for(CompletableFuture<RspList<Long>> listener: listeners)
            assert listener.isDone();
    }




    public void testFutureCancel() throws Exception {
        MethodCall sleep=new MethodCall("sleep", new Object[]{1000L}, new Class[]{long.class});
        Future<RspList<Long>> future=da.callRemoteMethodsWithFuture(null, sleep, new RequestOptions(GET_ALL, 5000L));
        assert !future.isDone();
        assert !future.isCancelled();
        future.cancel(true);
        assert future.isDone();
        assert future.isCancelled();

        future=da.callRemoteMethodsWithFuture(null, sleep, new RequestOptions(GET_ALL, 0));
        assert !future.isDone();
        assert !future.isCancelled();
        future.cancel(true);
        assert future.isDone();
        assert future.isCancelled();
    }


    /**
     * Test the ability of RpcDispatcher to handle large argument and return values
     * with multicast RPC calls.
     * 
     * The test sends requests for return values (byte arrays) having increasing sizes,
     * which increase the processing time for requests as well as the amount of memory
     * required to process requests.
     * 
     * The expected behaviour is that all RPC requests complete successfully.
     *
     */
    public void testLargeReturnValue() throws Exception {
        setProps(a,b,c);
        for(int i=0; i < SIZES.length; i++) {
            _testLargeValue(SIZES[i]);
        }
    }
    

    

    /**
     * Tests a method call to {A,B,C} where C left *before* the call. https://issues.redhat.com/browse/JGRP-620
     */
    public void testMethodInvocationToNonExistingMembers() throws Exception {
        final int timeout = 5 * 1000 ;

        // get the current membership, as seen by C
        View view=c.getView();
        List<Address> members=view.getMembers();
        System.out.println("list is " + members);

        // cause C to leave the group and close its channel
        System.out.println("closing c3");
        c.close();

        Util.sleep(1000);
        
        // make an RPC call using C's now outdated view of membership
        System.out.println("calling method foo() in " + members + " (view=" + b.getView() + ")");
        RspList<Integer> rsps=da.callRemoteMethods(members, "foo", null, null, new RequestOptions(GET_ALL, timeout));
        
        // all responses 
        System.out.println("responses:\n" + rsps);
        assert rsps.size() == 2;
        for(Map.Entry<Address,Rsp<Integer>> entry: rsps.entrySet()) {
            Rsp<Integer> rsp=entry.getValue();
            assert rsp.wasReceived();
            assert !rsp.wasSuspected();
        }

        List<Address> mbrs=new ArrayList<>(members);
        mbrs.remove(b.getAddress());
        System.out.println("calling method foo() in " + mbrs + " (view=" + b.getView() + ")");
        rsps=da.callRemoteMethods(mbrs, "foo", null, null, new RequestOptions(GET_ALL, timeout));

        // all responses
        System.out.println("responses:\n" + rsps);
        assert rsps.size() == 1;
        for(Map.Entry<Address,Rsp<Integer>> entry: rsps.entrySet()) {
            Rsp<Integer> rsp=entry.getValue();
            assert rsp.wasReceived();
            assert !rsp.wasSuspected();
        }

        rsps=da.callRemoteMethods(mbrs, "foo", null, null,
                                  new RequestOptions(GET_ALL, timeout).transientFlags(Message.TransientFlag.DONT_LOOPBACK));


        System.out.println("responses:\n" + rsps);
        assert rsps.isEmpty();

        mbrs.clear();
        rsps=da.callRemoteMethods(mbrs, "foo", null, null,
                                  new RequestOptions(GET_ALL, timeout).transientFlags(Message.TransientFlag.DONT_LOOPBACK));

        // all responses
        System.out.println("responses:\n" + rsps);
        assert rsps.isEmpty();
    }


    /**
     * Test the ability of RpcDispatcher to handle large argument and return values
     * with unicast RPC calls.
     * 
     * The test sends requests for return values (byte arrays) having increasing sizes,
     * which increase the processing time for requests as well as the amount of memory
     * required to process requests.
     * 
     * The expected behaviour is that all RPC requests complete successfully.
     */
    public void testLargeReturnValueUnicastCall() throws Exception {
        setProps(a,b,c);
        for(int i=0; i < SIZES.length; i++) {
            _testLargeValueUnicastCall(a.getAddress(), SIZES[i]);
        }
    }

    public void testRpcStats() throws Exception {
        Method meth=ServerObject.class.getDeclaredMethod("foo");
        List<Address> targets=Arrays.asList(b.getAddress(), c.getAddress());
        RpcStats stats=da.rpcStats().extendedStats(true);
        da.correlator().rpcStats(true);

        // sync mcast with future
        da.callRemoteMethodsWithFuture(null, new MethodCall(meth), RequestOptions.SYNC());
        System.out.println("stats = " + stats);
        waitUntil(() -> stats.multicasts(true) == 1);
        assert stats.multicasts(true) == 1;

        // async mcast with future
        da.callRemoteMethodsWithFuture(null, new MethodCall(meth), RequestOptions.ASYNC());
        System.out.println("stats = " + stats);
        waitUntil(() -> stats.multicasts(false) == 1);
        assert stats.multicasts(false) == 1;

        // sync anycast with future
        da.callRemoteMethodsWithFuture(targets, new MethodCall(meth), RequestOptions.SYNC().anycasting(true));
        System.out.println("stats = " + stats);
        waitUntil(() -> stats.anycasts(true) == 1);
        assert stats.anycasts(true) == 1;

        // async anycast with future
        da.callRemoteMethodsWithFuture(targets, new MethodCall(meth), RequestOptions.ASYNC().anycasting(true));
        System.out.println("stats = " + stats);
        waitUntil(() -> stats.anycasts(false) == 1);
        assert stats.anycasts(false) == 1;

        // sync unicast with future
        da.callRemoteMethodWithFuture(b.getAddress(), new MethodCall(meth), RequestOptions.SYNC());
        System.out.println("stats = " + stats);
        waitUntil(() -> stats.unicasts(true) == 1);
        assert stats.unicasts(true) == 1;

        // async unicast with future
        da.callRemoteMethodWithFuture(b.getAddress(), new MethodCall(meth), RequestOptions.ASYNC());
        System.out.println("stats = " + stats);
        waitUntil(() -> stats.unicasts(false) == 1);
        assert stats.unicasts(false) == 1;


        // sync mcast
        da.callRemoteMethods(null, new MethodCall(meth), RequestOptions.SYNC());
        System.out.println("stats = " + stats);
        assert stats.multicasts(true) == 2;

        // async mcast
        da.callRemoteMethods(null, new MethodCall(meth), RequestOptions.ASYNC());
        System.out.println("stats = " + stats);
        assert stats.multicasts(false) == 2;

        // sync anycast
        da.callRemoteMethods(targets, new MethodCall(meth), RequestOptions.SYNC().anycasting(true));
        System.out.println("stats = " + stats);
        assert stats.anycasts(true) == 2;

        // async anycast
        da.callRemoteMethods(targets, new MethodCall(meth), RequestOptions.ASYNC().anycasting(true));
        System.out.println("stats = " + stats);
        assert stats.anycasts(false) == 2;

        // sync unicast
        da.callRemoteMethod(b.getAddress(), new MethodCall(meth), RequestOptions.SYNC());
        System.out.println("stats = " + stats);
        assert stats.unicasts(true) == 2;

        // async unicast
        da.callRemoteMethod(b.getAddress(), new MethodCall(meth), RequestOptions.ASYNC());
        System.out.println("stats = " + stats);
        assert stats.unicasts(false) == 2;
    }

    protected static void waitUntil(BooleanSupplier condition) throws TimeoutException {
        long start=System.nanoTime();
        Util.waitUntilNoX(2000, 100, condition);
        long time=System.nanoTime() - start;
        System.out.printf("-- waited for %s\n", Util.printTime(time, TimeUnit.NANOSECONDS));
    }

    protected static void setProps(JChannel... channels) {
        for(JChannel ch: channels) {
            Protocol prot=ch.getProtocolStack().findProtocol(FRAG2.class);
            if(prot != null) {
                ((FRAG2)prot).setFragSize(12000);
            }
            prot=ch.getProtocolStack().findProtocol(FRAG.class);
            if(prot != null) {
                ((FRAG)prot).setFragSize(12000);
            }

            prot=ch.getProtocolStack().getTransport();
            if(prot != null)
                ((TP)prot).getBundler().setMaxSize(14000);
        }
    }

    protected static JChannel createChannel(String name) throws Exception {
        return new JChannel(Util.getTestStack()).name(name);
    }


    /**
     * Helper method to perform a RPC call on server method "returnValue(int size)" for 
     * all group members.
     * 
     * The method checks that each returned value is non-null and has the correct size. 
     *    
     */
    void _testLargeValue(int size) throws Exception {

        final long timeout = LARGE_VALUE_TIMEOUT * 1000 ;

        System.out.println("\ntesting with " + size + " bytes");
        long startTime = System.currentTimeMillis();
        RspList<Object> rsps=da.callRemoteMethods(null, "largeReturnValue", new Object[]{size}, new Class[]{int.class},
                                                  new RequestOptions(GET_ALL, timeout));
        long stopTime = System.currentTimeMillis();
        System.out.println("test took: " + (stopTime-startTime) + " ms");
        System.out.println("rsps:");
        assert rsps.size() == 3 : "there should be three responses to the RPC call but only " + rsps.size() +
                " were received: " + rsps;
        
        for(Map.Entry<Address,Rsp<Object>> entry: rsps.entrySet()) {

            // its possible that an exception was raised in processing
            Object obj = entry.getValue().getValue() ;

            // this should not happen
            assert !(obj instanceof Throwable) : "exception was raised in processing reasonably sized argument";

            byte[] val=(byte[]) obj;
            assert val != null;
            System.out.println(val.length + " bytes from " + entry.getKey());
            assert val.length == size : "return value does not match required size";
        }
    }
    
    /**
     * Helper method to perform a RPC call on server method "returnValue(int size)" for 
     * all group members.
     * 
     * This method need to take into account that RPC calls can timeout with huge values,
     * and they can also trigger OOMEs. But if we are lucky, they can also return
     * reasonable values. 
     * 
     */
    void _testHugeValue(int size) throws Exception {

        // 20 second timeout
        final long timeout = 20 * 1000 ;

        System.out.println("\ntesting with " + size + " bytes");
        RspList<Object> rsps=da.callRemoteMethods(null, "largeReturnValue", new Object[]{size}, new Class[]{int.class},
                                                  new RequestOptions(GET_ALL, timeout));
        System.out.println("rsps:");
        assert rsps != null;
        assert rsps.size() == 3 : "there should be three responses to the RPC call but only " + rsps.size() +
                " were received: " + rsps;

        // in checking the return values, we need to take account of timeouts (i.e. when
        // a null value is returned) and exceptions 
        for(Map.Entry<Address,Rsp<Object>> entry: rsps.entrySet()) {

            Object obj = entry.getValue().getValue() ;

            // its possible that an exception was raised
            if (obj instanceof java.lang.Throwable) {
                Throwable t = (Throwable) obj ;

                System.out.println(t + " exception was raised processing argument from " +
                                     entry.getKey() + " -this is expected") ;
                continue ;
            }

            // its possible that the request timed out before the serve could reply
            if (obj == null) {
                System.out.println("request timed out processing argument from " +
                                     entry.getKey() + " - this is expected") ;
                continue ;
            }

            // if we reach here, we sould have a reasonable value
            byte[] val=(byte[]) obj;
            System.out.println(val.length + " bytes from " + entry.getKey());
            assert val.length == size : "return value does not match required size";
        }
    }

    /**
     * Helper method to perform a RPC call on server method "returnValue(int size)" for 
     * an individual group member. 
     * 
     * The method checks that the returned value is non-null and has the correct size. 
     * 
     * @param dst the group member
     * @param size the size of the byte array to be returned
     */
    void _testLargeValueUnicastCall(Address dst, int size) throws Exception {

        final long timeout = LARGE_VALUE_TIMEOUT * 1000 ;

        System.out.println("\ntesting unicast call with " + size + " bytes");
        assert dst != null;

        long startTime = System.currentTimeMillis();
        byte[] val=da.callRemoteMethod(dst, "largeReturnValue", new Object[]{size}, new Class[]{int.class},
                                       new RequestOptions(GET_ALL, timeout));
        long stopTime = System.currentTimeMillis();
        System.out.println("test took: " + (stopTime-startTime) + " ms");

        // check value is not null, otherwise fail the test
        assert val != null;
        System.out.println("rsp: " + val.length + " bytes");
        
        // returned value should have requested size
        assert size == val.length;
    }

    /**
     * This class serves as a server obect to turn requests into replies.
     * It is initialised with an integer id value.
     * 
     * It implements two functions:
     * function foo() returns the id of the server
     * function largeReturnValue(int size) returns a byte array of size 'size'
     */
    protected static class ServerObject {
        protected final String name;
        protected final int    i;

        protected static final Method[] methods;

        static {
            try {
                methods=new Method[] {
                  ServerObject.class.getDeclaredMethod("foo"), // index 0
                  ServerObject.class.getDeclaredMethod("bar"),
                  ServerObject.class.getDeclaredMethod("sleep", long.class),
                  ServerObject.class.getDeclaredMethod("throwException"),
                  ServerObject.class.getDeclaredMethod("returnException"),
                  ServerObject.class.getDeclaredMethod("largeReturnValue", int.class),
                  ServerObject.class.getDeclaredMethod("add", int.class, int.class) // index 6
                };
            }
            catch(NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }


        public ServerObject(String name, int i) {
            this.name=name;
            this.i=i;
        }

        public int foo() {return i;}
        public static void bar() {;}
        
        public long sleep(long timeout) {
            long id=Thread.currentThread().getId();
            System.out.printf("-- [%d] [%s] %s: sleeping for %d ms\n", id, new Date(), name, timeout);
            long start=System.currentTimeMillis();
            Util.sleep(timeout);
            long retval=System.currentTimeMillis() - start;
            System.out.printf("-- [%d] [%s] %s: slept for %d ms\n", id, new Date(), name, retval);
            return retval;
        }

        public long sleep(int invocation_id, long timeout) {
            long id=Thread.currentThread().getId();
            System.out.printf("-- [%d] [%s] [#%d] %s: sleeping for %d ms\n", id, new Date(), invocation_id, name, timeout);
            long start=System.currentTimeMillis();
            Util.sleep(timeout);
            long retval=System.currentTimeMillis() - start;
            System.out.printf("-- [%d] [%s] [#%d] %s: slept for %d ms\n", id, new Date(), invocation_id, name, retval);
            return retval;
        }


        public static void throwException() throws Exception {
            throw new Exception("booom");
        }

        public static Exception returnException() {
            return new Exception("booom");
        }

        public static byte[] largeReturnValue(int size) {
            return new byte[size];
        }

        public static int add(int a, int b) {return a+b;}

        public static void throwExceptionNested() throws Exception {
            Exception ex=new IllegalArgumentException("illegal argument - see cause for details");
            Exception cause=new NullPointerException("the arg was null!");
            ex.initCause(cause);
            throw ex;
        }

    }


}