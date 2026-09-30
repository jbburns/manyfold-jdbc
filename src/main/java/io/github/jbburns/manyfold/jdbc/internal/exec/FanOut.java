package io.github.jbburns.manyfold.jdbc.internal.exec;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Applies one operation to every backend and gathers the outcomes.
 *
 * <p>Failures are never swallowed. If any backend fails, a {@link ManyfoldException} naming that
 * backend is thrown once every backend has been given its turn; further failures are chained
 * through {@link SQLException#setNextException}. Reads run concurrently on a small pool owned by
 * the connection; everything else runs sequentially in URL order.
 *
 * <p>After {@link #close} every operation fails with SQL state {@value
 * ManyfoldException#STATE_CONNECTION_CLOSED}, including operations that were queued but had not
 * started when the close happened.
 */
public final class FanOut implements AutoCloseable {

  private static final AtomicInteger POOL_IDS = new AtomicInteger();
  private static final long KEEP_ALIVE_SECONDS = 30;

  /** An operation on one backend's delegate that may return a value. */
  @FunctionalInterface
  public interface Call<D, T extends @Nullable Object> {
    T apply(D delegate) throws Throwable;
  }

  private final List<String> names;
  private final int poolId = POOL_IDS.incrementAndGet();
  private final AtomicInteger threadIndex = new AtomicInteger();
  private @Nullable ThreadPoolExecutor executor;
  private boolean closed;

  /**
   * Creates a fan-out over backends with the given logical names.
   *
   * @param names names in URL order; index {@code i} of every delegate list refers to name {@code
   *     i}
   */
  public FanOut(List<String> names) {
    this.names = List.copyOf(names);
  }

  /** Logical names in URL order. */
  public List<String> names() {
    return names;
  }

  /**
   * Runs the call on every delegate concurrently and waits for all of them.
   *
   * @param delegates one delegate per backend, in URL order
   * @param call the operation
   * @param <D> delegate type
   * @param <T> result type
   * @return results in URL order
   * @throws SQLException if any backend failed
   */
  public <D, T extends @Nullable Object> List<T> parallel(List<D> delegates, Call<D, T> call)
      throws SQLException {
    if (delegates.size() == 1) {
      return sequential(delegates, call);
    }
    ThreadPoolExecutor pool = executor();
    List<Future<T>> futures = new ArrayList<>(delegates.size());
    try {
      for (D delegate : delegates) {
        futures.add(
            pool.submit(
                () -> {
                  try {
                    return call.apply(delegate);
                  } catch (Exception e) {
                    throw e;
                  } catch (Throwable t) {
                    throw new WrappedError(t);
                  }
                }));
      }
    } catch (RejectedExecutionException e) {
      cancelAll(futures);
      throw closedException();
    }
    List<T> results = new ArrayList<>(delegates.size());
    List<@Nullable Throwable> failures = new ArrayList<>();
    for (int i = 0; i < futures.size(); i++) {
      try {
        results.add(futures.get(i).get());
        failures.add(null);
      } catch (ExecutionException e) {
        results.add(null);
        Throwable cause = e.getCause();
        failures.add(cause instanceof WrappedError w ? w.getCause() : cause);
      } catch (CancellationException e) {
        cancelAll(futures);
        throw closedException();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        cancelAll(futures);
        throw new ManyfoldException(
            "Interrupted while waiting for backend '" + names.get(i) + "'", "HY008", e);
      }
    }
    throwIfAnyFailed(failures);
    return results;
  }

  /**
   * Runs the call on every delegate in URL order, giving each its turn even after a failure.
   *
   * @param delegates one delegate per backend, in URL order
   * @param call the operation
   * @param <D> delegate type
   * @param <T> result type
   * @return results in URL order
   * @throws SQLException if any backend failed
   */
  public <D, T extends @Nullable Object> List<T> sequential(List<D> delegates, Call<D, T> call)
      throws SQLException {
    ensureOpen();
    List<T> results = new ArrayList<>(delegates.size());
    List<@Nullable Throwable> failures = new ArrayList<>(delegates.size());
    for (D delegate : delegates) {
      try {
        results.add(call.apply(delegate));
        failures.add(null);
      } catch (Throwable t) {
        results.add(null);
        failures.add(t);
      }
    }
    throwIfAnyFailed(failures);
    return results;
  }

  private void throwIfAnyFailed(List<@Nullable Throwable> failures) throws SQLException {
    SQLException first = null;
    SQLException last = null;
    for (int i = 0; i < failures.size(); i++) {
      Throwable failure = failures.get(i);
      if (failure == null) {
        continue;
      }
      if (failure instanceof Error error) {
        throw error;
      }
      SQLException wrapped = ManyfoldException.backendFailed(names.get(i), failure);
      if (first == null) {
        first = wrapped;
      } else {
        Objects.requireNonNull(last).setNextException(wrapped);
      }
      last = wrapped;
    }
    if (first != null) {
      throw first;
    }
  }

  /** Carries an {@link Error} across a {@code Callable}, which may only throw exceptions. */
  private static final class WrappedError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    WrappedError(Throwable cause) {
      super(cause);
    }
  }

  private static <T> void cancelAll(List<Future<T>> futures) {
    for (Future<T> future : futures) {
      future.cancel(true);
    }
  }

  private static SQLException closedException() {
    return new ManyfoldException("Connection closed", ManyfoldException.STATE_CONNECTION_CLOSED);
  }

  private synchronized void ensureOpen() throws SQLException {
    if (closed) {
      throw closedException();
    }
  }

  private synchronized ThreadPoolExecutor executor() throws SQLException {
    if (closed) {
      throw closedException();
    }
    ThreadPoolExecutor pool = executor;
    if (pool == null) {
      ThreadFactory factory =
          runnable -> {
            Thread thread =
                new Thread(runnable, "manyfold-" + poolId + "-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
          };
      pool =
          new ThreadPoolExecutor(
              names.size(),
              names.size(),
              KEEP_ALIVE_SECONDS,
              TimeUnit.SECONDS,
              new LinkedBlockingQueue<>(),
              factory);
      pool.allowCoreThreadTimeOut(true);
      executor = pool;
    }
    return pool;
  }

  /**
   * Stops the pool and fails every operation that was queued or is submitted later. Safe to call
   * more than once.
   */
  @Override
  public void close() {
    ThreadPoolExecutor pool;
    synchronized (this) {
      closed = true;
      pool = executor;
      executor = null;
    }
    if (pool != null) {
      // shutdownNow drops queued tasks without completing their futures, which would leave a
      // waiting thread blocked forever. Cancel them so every waiter is released.
      for (Runnable queued : pool.shutdownNow()) {
        if (queued instanceof Future<?> future) {
          future.cancel(false);
        }
      }
    }
  }
}
