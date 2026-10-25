package app.amber.agent.perffixture;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Test-only protocol used by the real shell/lifecycle adapter; no Android mocks. */
final class HomeLaunchContract<T> {
    static final String PACKAGE = "app.amber.agent.perffixture";
    static final String COMPONENT = PACKAGE + "/app.amber.agent.RouteActivity";
    static final String PATH = "home-first-first-history";
    private final int expectedPid;
    private final String token;
    private T owner;
    private boolean resumed;
    private boolean destroyed;
    private String mismatch;

    HomeLaunchContract(int expectedPid, String token) {
        if (!token.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("unsafe test token");
        this.expectedPid = expectedPid;
        this.token = token;
    }

    String command() {
        // All arguments are validated single words; no shell interpolation/quoting ambiguity.
        return "am start -W -n " + COMPONENT + " --es perfPathId " + PATH + " --es homeTestToken " + token;
    }

    static void validateShellResult(String stdout, String stderr) {
        if (!stderr.trim().isEmpty() || Pattern.compile("(?im)^\\s*(Error|Exception|Warning|SecurityException)[: ]").matcher(stdout).find()) {
            throw new AssertionError("shell-start reported an error/warning; see instrumentation raw stdout/stderr");
        }
        if (!Pattern.compile("(?m)^Status: ok\\s*$").matcher(stdout).find()
                || !Pattern.compile("(?m)^Activity: " + Pattern.quote(COMPONENT) + "\\s*$").matcher(stdout).find()
                || !Pattern.compile("(?m)^Complete\\s*$").matcher(stdout).find()) {
            throw new AssertionError("shell-start missing Status: ok / exact target Activity / Complete; see raw result");
        }
        // UiAutomation does not expose a process exit code. Never invent one;
        // parsed am result AND independent real lifecycle evidence are mandatory.
    }

    private boolean matches(String component, String eventToken) {
        return COMPONENT.equals(component) && token.equals(eventToken);
    }

    synchronized void created(T activity, int pid, String component, String eventToken) {
        if (!matches(component, eventToken)) return;
        if (pid != expectedPid) mismatch = "lifecycle PID changed; expected=" + expectedPid + " actual=" + pid;
        else if (owner != null && owner != activity) mismatch = "RouteActivity instance changed/recreated";
        else owner = activity;
        notifyAll();
    }

    synchronized void resumed(T activity, int pid, String component, String eventToken) {
        if (!matches(component, eventToken)) return;
        if (pid != expectedPid) mismatch = "resumed PID changed; expected=" + expectedPid + " actual=" + pid;
        else if (owner == null || owner != activity || destroyed) mismatch = "resumed without matching created live owner";
        else resumed = true;
        notifyAll();
    }

    synchronized void paused(T activity) { if (owner == activity) resumed = false; }
    synchronized void destroyed(T activity) {
        if (owner == activity) { destroyed = true; resumed = false; notifyAll(); }
    }

    synchronized T awaitResumed(long timeoutMs) throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!resumed && !destroyed && mismatch == null) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("no matching created/resumed RouteActivity in instrumentation PID=" + expectedPid);
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return requireResumed();
    }

    synchronized T requireResumed() {
        if (mismatch != null) throw new AssertionError(mismatch);
        if (owner == null || !resumed || destroyed) throw new AssertionError("test RouteActivity owner is not live/resumed");
        return owner;
    }

    synchronized T ownerForClose() { return owner; }

    synchronized void awaitDestroyed(long timeoutMs) throws InterruptedException, TimeoutException {
        if (owner == null) return;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!destroyed) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("test RouteActivity finish did not reach destroyed");
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }
}
