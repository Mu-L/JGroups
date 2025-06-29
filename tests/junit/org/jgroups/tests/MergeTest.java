package org.jgroups.tests;

import org.jgroups.*;
import org.jgroups.protocols.*;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.stack.ProtocolStack;
import org.jgroups.util.Util;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests merging on all stacks
 * 
 * @author vlada
 */
@Test(groups=Global.STACK_DEPENDENT,singleThreaded=true)
public class MergeTest extends ChannelTestBase {
    protected JChannel[] channels=null;

    @AfterMethod protected void destroy() {
        level("warn", channels);
        Util.close(channels);
    }
   
    public void testMerging2Members() throws Exception {
        mergeHelper("MergeTest.testMerging2Members", "A", "B");
    }
    
    public void testMerging4Members() throws Exception {
        mergeHelper("MergeTest.testMerging4Members", "A", "B", "C", "D");
    }


    protected void mergeHelper(String cluster_name, String ... members) throws Exception {
        channels=createChannels(cluster_name, members);
        Util.waitUntilAllChannelsHaveSameView(10000, 500, channels);
        print(channels);

        System.out.println("\ncreating partitions: ");
        createPartitions(channels);

        print(channels);
        for(JChannel ch: channels)
            assert ch.getView().size() == 1 : "view is " + ch.getView();

        Address merge_leader=determineLeader(channels, members);
        System.out.println("\n==== injecting merge event into merge leader : " + merge_leader + " ====");
        injectMergeEvent(channels, merge_leader, members);
        for(int i=0; i < 40; i++) {
            System.out.print(".");
            if(allChannelsHaveViewOf(channels, members.length))
                break;
            Util.sleep(1000);
            if(i > 0 && i % 10 == 0)
                injectMergeEvent(channels, merge_leader, members);
        }
        System.out.println("\n");
        print(channels);
        assertAllChannelsHaveViewOf(channels, members.length);

    }

    protected static void level(String level, JChannel ... channels) {
        for(JChannel ch: channels) {
            GMS gms=ch.getProtocolStack().findProtocol(GMS.class);
            gms.setLevel(level);
        }
    }

    protected JChannel[] createChannels(String cluster_name, String[] members) throws Exception {
        JChannel[] retval=new JChannel[members.length];
        JChannel ch=null;
        for(int i=0; i < retval.length; i++) {
            JChannel tmp;
            if(ch == null) {
                ch=createChannel();
                tmp=ch;
            }
            else {
                tmp=createChannel();
            }
            tmp.setName(members[i]);
            ProtocolStack stack=tmp.getProtocolStack();

            NAKACK2 nakack=stack.findProtocol(NAKACK2.class);
            if(nakack != null)
                nakack.logDiscardMessages(false);

            stack.removeProtocol(MERGE3.class);

            FD_SOCK2 fd_sock=stack.findProtocol(FD_SOCK2.class);
            if(fd_sock != null)
                fd_sock.setPortRange(5);

            // tmp.connect(cluster_name);
            retval[i]=tmp;
        }
        makeUnique(retval);
        for(JChannel c: retval)
            c.connect(cluster_name);
        return retval;
    }


    private static void createPartitions(JChannel[] channels) throws Exception {
        long view_id=1; // find the highest view-id +1
        for(JChannel ch: channels)
            view_id=Math.max(ch.getView().getViewId().getId(), view_id);
        view_id++;

        for(JChannel ch: channels) {
            View view=View.create(ch.getAddress(), view_id, ch.getAddress());
            GMS gms=ch.getProtocolStack().findProtocol(GMS.class);
            gms.installView(view);
        }
    }

    private static void injectMergeEvent(JChannel[] channels, Address leader_addr, String ... coordinators) {
        Map<Address,View> views=new HashMap<>();
        for(String tmp: coordinators) {
            Address coord=findAddress(tmp, channels);
            views.put(coord, findView(tmp, channels));
        }

        JChannel coord=findChannel(leader_addr, channels);
        GMS gms=coord.getProtocolStack().findProtocol(GMS.class);
        gms.setLevel("trace");
        gms.up(new Event(Event.MERGE, views));
    }


    private static JChannel findChannel(Address addr, JChannel[] channels) {
        for(JChannel ch: channels) {
            if(ch.getAddress().equals(addr))
                return ch;
        }
        return null;
    }

    private static View findView(String tmp, JChannel[] channels) {
        for(JChannel ch: channels) {
            if(ch.getName().equals(tmp))
                return ch.getView();
        }
        return null;
    }

    private static boolean allChannelsHaveViewOf(JChannel[] channels, int count) {
        for(JChannel ch: channels) {
            if(ch.getView().size() != count)
                return false;
        }
        return true;
    }

    private static void assertAllChannelsHaveViewOf(JChannel[] channels, int count) {
        for(JChannel ch: channels)
            assert ch.getView().size() == count : ch.getName() + " has view " + ch.getView();
    }


    private static Address determineLeader(JChannel[] channels, String ... coords) {
        Membership membership=new Membership();
        for(String coord: coords)
            membership.add(findAddress(coord, channels));
        return membership.sort().elementAt(0);
    }

     private static Address findAddress(String tmp, JChannel[] channels) {
         for(JChannel ch: channels) {
             if(ch.getName().equals(tmp))
                 return ch.getAddress();
         }
         return null;
     }

    private static void print(JChannel[] channels) {
        for(JChannel ch: channels) {
            System.out.println(ch.getName() + ": " + ch.getView());
        }
    }
    

   
}
