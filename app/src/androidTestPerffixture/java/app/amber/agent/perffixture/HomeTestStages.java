package app.amber.agent.perffixture;

import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Test-only wall-clock bounds, including non-cooperative ActivityScenario/idle calls.
 * A timed-out daemon may still be blocked: fail the test and do not reuse it.
 * The outer host runner bounds the instrumentation process as a second boundary.
 */
final class HomeTestStages implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "home-device-stage");
        thread.setDaemon(true);
        return thread;
    });
    private final long deadlineNs;
    private final BiConsumer<String, String> checkpoint;
    private boolean poisoned;

    HomeTestStages(long totalMs, BiConsumer<String, String> checkpoint) {
        this.deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalMs);
        this.checkpoint = checkpoint;
    }

    <T> T step(String name, long limitMs, Callable<T> action) {
        if (poisoned) throw new AssertionError("stage=" + name + " previous stage timed out");
        checkpoint.accept(name, "begin");
        Future<T> future = worker.submit(action);
        try {
            long remainingNs = Math.max(0L, deadlineNs - System.nanoTime());
            T value = future.get(Math.min(TimeUnit.MILLISECONDS.toNanos(limitMs), remainingNs), TimeUnit.NANOSECONDS);
            checkpoint.accept(name, "ok");
            return value;
        } catch (TimeoutException e) {
            poisoned = true;
            future.cancel(true);
            checkpoint.accept(name, "timeout");
            throw new AssertionError("stage=" + name + " timed out (stage/overall wall-clock bound)", e);
        } catch (ExecutionException e) {
            checkpoint.accept(name, "failed");
            throw new AssertionError("stage=" + name + " failed", e.getCause());
        } catch (InterruptedException e) {
            poisoned = true;
            future.cancel(true);
            checkpoint.accept(name, "interrupted");
            Thread.currentThread().interrupt();
            throw new AssertionError("stage=" + name + " interrupted", e);
        }
    }

    @Override public void close() {
        worker.shutdownNow(); // never wait indefinitely for a non-cooperative UI/idle call
    }
}
