package app.amber.agent.perffixture;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Test-connection-only window retrieval. No Settings, permissions, reflection, or node roots/titles. */
final class HomeWindowAccess implements AutoCloseable {
    private final Supplier<UiAutomation> connect;
    private final BiConsumer<String,HomeWindowAccess> audit;
    private UiAutomation automation;
    private boolean restoreRequired;
    private boolean restoreStarted;
    volatile Integer flagsBefore, flagsEnabled, flagsRestored, capabilities;
    volatile String restoreError;

    HomeWindowAccess(Supplier<UiAutomation> connect, BiConsumer<String,HomeWindowAccess> audit) {
        this.connect=connect; this.audit=audit;
    }
    void enable() {
        try {
            automation=connect.get();
            AccessibilityServiceInfo info=automation.getServiceInfo();
            if(info==null) throw new UnsupportedOperationException("UiAutomation service info unavailable");
            flagsBefore=info.flags;
            capabilities=info.getCapabilities();
            info.flags=flagsBefore | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            restoreRequired=true; // setServiceInfo may apply before an exception/readback failure
            automation.setServiceInfo(info);
            AccessibilityServiceInfo actual=automation.getServiceInfo();
            flagsEnabled=actual==null ? null : actual.flags;
            if(flagsEnabled==null || (flagsEnabled & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS)==0)
                throw new UnsupportedOperationException("UiAutomation interactive-window flag not effective");
        } finally { audit.accept("initialize",this); }
    }

    static final class Observation {
        final String source;
        final int id,displayId;
        final boolean active,focused;
        Observation(String source,AccessibilityWindowInfo window) {
            this.source=source; id=window.getId(); displayId=window.getDisplayId();
            active=window.isActive(); focused=window.isFocused();
        }
    }
    static final class Snapshot {
        final List<Observation> observations=new ArrayList<>();
        final List<String> errors=new ArrayList<>();
        Observation matched;
        boolean conflicting;
        boolean ready() { return matched!=null && !conflicting; }
    }
    Snapshot query(AccessibilityNodeInfo root,int ownerId,int ownerDisplay) {
        if(flagsEnabled==null || (flagsEnabled & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS)==0)
            throw new IllegalStateException("window scope not initialized");
        Snapshot result=new Snapshot();
        try { add(result,"node.window",root.getWindow(),ownerId,ownerDisplay); }
        catch(RuntimeException error) { result.errors.add("node.window:"+error.getClass().getName()); }
        try {
            for(AccessibilityWindowInfo window:automation.getWindows()) add(result,"getWindows",window,ownerId,ownerDisplay);
        } catch(RuntimeException error) { result.errors.add("getWindows:"+error.getClass().getName()); }
        try {
            SparseArray<List<AccessibilityWindowInfo>> all=automation.getWindowsOnAllDisplays();
            for(int i=0;i<all.size();i++) for(AccessibilityWindowInfo window:all.valueAt(i))
                add(result,"getWindowsOnAllDisplays",window,ownerId,ownerDisplay);
        } catch(RuntimeException error) { result.errors.add("getWindowsOnAllDisplays:"+error.getClass().getName()); }
        return result;
    }
    private void add(Snapshot result,String source,AccessibilityWindowInfo window,int ownerId,int ownerDisplay) {
        if(window==null) return;
        Observation value=new Observation(source,window);
        result.observations.add(value); // IDs/display/flags only; never window titles or other roots
        if(value.id!=ownerId || value.displayId!=ownerDisplay) return;
        if(result.matched!=null && (result.matched.active!=value.active || result.matched.focused!=value.focused))
            result.conflicting=true;
        result.matched=value;
    }

    /** Bounded finally restoration, including initialization or poisoned UI-stage failures. */
    @Override public void close() {
        if(!restoreRequired || restoreStarted) return;
        restoreStarted=true;
        ExecutorService restore=Executors.newSingleThreadExecutor(r -> {
            Thread thread=new Thread(r,"home-window-restore"); thread.setDaemon(true); return thread;
        });
        Future<?> task=restore.submit(() -> {
            AccessibilityServiceInfo info=automation.getServiceInfo();
            if(info==null) throw new IllegalStateException("window flags restore service info unavailable");
            info.flags=flagsBefore;
            automation.setServiceInfo(info);
            AccessibilityServiceInfo actual=automation.getServiceInfo();
            flagsRestored=actual==null ? null : actual.flags;
            if(!flagsBefore.equals(flagsRestored)) throw new IllegalStateException("window flags restore readback mismatch");
        });
        try { task.get(1,TimeUnit.SECONDS); }
        catch(Exception error) {
            restoreError=error.getClass().getName();
            if(error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AssertionError("UiAutomation window flags restoration failed/bounded timeout",error);
        } finally {
            task.cancel(true); restore.shutdownNow(); audit.accept("restore",this);
        }
    }
}
