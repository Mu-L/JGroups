package org.jgroups.tests.helpers;

import org.jboss.byteman.rule.Rule;
import org.jboss.byteman.rule.helper.Helper;
import org.jgroups.Message;
import org.jgroups.protocols.ProtPerfHeader;
import org.jgroups.stack.DiagnosticsHandler;
import org.jgroups.stack.Protocol;
import org.jgroups.util.AverageMinMax;
import org.jgroups.util.MessageBatch;
import org.jgroups.util.Util;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.jgroups.protocols.ProtPerfHeader.ID;

/**
 * @author Bela Ban
 * @since  5.2.7
 */
public class ProtPerfHelper extends Helper {
    protected ProtPerfHelper(Rule rule) {
        super(rule);
    }

    protected static final ProtPerfProbeHandler ph=new ProtPerfProbeHandler();


    @SuppressWarnings("MethodMayBeStatic")
    public void diagCreated(DiagnosticsHandler diag) {
        if(diag.isEnabled()) {
            boolean already_present=diag.getProbeHandlers().contains(ph);
            if(!already_present)
                diag.registerProbeHandler(ph);
        }
    }


    @SuppressWarnings("MethodMayBeStatic")
    public void downTime(Message msg, Protocol p) {
        ProtPerfHeader hdr=getOrAddHeader(msg);
        Protocol up_prot=p.getUpProtocol();
        if(up_prot != null && hdr.startDown() > 0) {
            long time=(System.nanoTime() - hdr.startDown()) / 1000; // us
            ph.add(up_prot.getClass(), time, true);
        }
        hdr.startDown(System.nanoTime());
    }



    @SuppressWarnings("MethodMayBeStatic")
    public void computeDownStartTime(Message msg, Class<? extends Protocol> cl) {
        ProtPerfHeader hdr=getOrAddHeader(msg);
        if(hdr.startDown() > 0) {
            long time=(System.nanoTime() - hdr.startDown()) / 1000;
            hdr.startDown(0);
            ph.add(cl, time, true);
        }
    }

    @SuppressWarnings("MethodMayBeStatic")
    public void upTime(Message msg, Protocol p) {
        ProtPerfHeader hdr=getOrAddHeader(msg);
        Protocol down_prot=p.getDownProtocol();
        if(down_prot != null && hdr.startUp() > 0) {
            long time=(System.nanoTime() - hdr.startUp()) / 1000; // us
            ph.add(down_prot.getClass(), time, false);
        }
        hdr.startUp(System.nanoTime());
    }


    @SuppressWarnings("MethodMayBeStatic")
    public void upTime(MessageBatch batch, Protocol p) {
        Protocol down_prot=p.getDownProtocol();
        for(Message msg: batch) {
            ProtPerfHeader hdr=getOrAddHeader(msg);
            if(down_prot != null && hdr.startUp() > 0) {
                long time=(System.nanoTime() - hdr.startUp()) / 1000; // us
                ph.add(down_prot.getClass(), time, false);
            }
            hdr.startUp(System.nanoTime());
        }
    }


    protected static ProtPerfHeader getOrAddHeader(Message msg) {
        ProtPerfHeader hdr=msg.getHeader(ID);
        if(hdr != null)
            return hdr;
        msg.putHeader(ID, hdr=new ProtPerfHeader());
        return hdr;
    }


    protected static class ProtPerfProbeHandler implements DiagnosticsHandler.ProbeHandler {
        protected final Map<Class<? extends Protocol>,Entry> map;

        public ProtPerfProbeHandler() {
            map=Util.createConcurrentMap(128);
        }

        public Map<String,String> handleProbe(String... keys) {
            Map<String,String> m=null;
            for(String key: keys) {
                String value=null;
                switch(key) {
                    case "perf":
                        value=dumpStats(true, true, true);
                        break;
                    case "perf-down":
                        value=dumpStats(true, false, false);
                        break;
                    case "perf-down-detailed":
                        value=dumpStats(true, false, true);
                        break;
                    case "perf-up":
                        value=dumpStats(false, true, false);
                        break;
                    case "perf-up-detailed":
                        value=dumpStats(false, true, true);
                        break;
                    case "perf-reset":
                        clearStats();
                        break;
                }
                if(value != null) {
                    if(m == null)
                        m=new HashMap<>();
                    m.put(key, value);
                }
            }
            return m;
        }

        public String[] supportedKeys() {
            return new String[]{"perf", "perf-down", "perf-up", "perf-down-detailed", "perf-up-detailed", "perf-reset"};
        }

        protected void add(Class<? extends Protocol> clazz, long value, boolean down) {
            Entry e=map.computeIfAbsent(clazz, cl -> new Entry());
            e.add(value, down);
        }

        protected String dumpStats(boolean down, boolean up, boolean detailed) {
            return map.entrySet().stream()
              .map(e -> String.format("%s: %s", e.getKey().getSimpleName(), e.getValue().toString(down, up, detailed)))
              .collect(Collectors.joining("\n"));
        }

        protected void clearStats() {
            map.values().forEach(Entry::clear);
        }
    }


    protected static class Entry {
        protected final AverageMinMax avg_down=new AverageMinMax();
        protected final AverageMinMax avg_up=new AverageMinMax();

        protected void add(long value, boolean down) {
            if(down) {
                synchronized(avg_down) {
                    avg_down.add(value);
                }
            }
            else {
                synchronized(avg_up) {
                    avg_up.add(value);
                }
            }
        }

        protected void clear() {
            synchronized(avg_down) {
                avg_down.clear();
            }
            synchronized(avg_up) {
                avg_up.clear();
            }
        }

        public String toString() {
            return String.format("down: %s up: %s", avg_down, avg_up);
        }

        public String toString(boolean down, boolean up, boolean detailed) {
            return String.format("%s %s", down? print(avg_down, detailed) : "", up? print(avg_up, detailed) : "");
        }

        public static String print(AverageMinMax avg, boolean detailed) {
            return detailed? avg.toString() : String.format("%,.2f %s", avg.getAverage(), avg.unit() == null? "" : Util.suffix(avg.unit()));
        }
    }
}
