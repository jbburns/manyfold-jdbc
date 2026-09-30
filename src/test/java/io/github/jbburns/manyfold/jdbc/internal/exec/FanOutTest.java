package io.github.jbburns.manyfold.jdbc.internal.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FanOutTest {

  private final FanOut fanOut = new FanOut(List.of("a", "b", "c"));

  @Test
  void parallelReturnsResultsInBackendOrder() throws Exception {
    List<String> results =
        fanOut.parallel(
            List.of(30, 10, 20),
            delay -> {
              Thread.sleep(delay);
              return Thread.currentThread().getName().startsWith("manyfold-")
                  ? "ok" + delay
                  : "wrong";
            });

    assertThat(results).containsExactly("ok30", "ok10", "ok20");
    fanOut.close();
    fanOut.close();
  }

  @Test
  void singleBackendRunsInline() throws Exception {
    FanOut single = new FanOut(List.of("only"));

    List<String> results = single.parallel(List.of(1), x -> Thread.currentThread().getName());

    assertThat(results).containsExactly(Thread.currentThread().getName());
  }

  @Test
  void everyBackendGetsItsTurnAndFailuresAreChainedByName() {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                fanOut.sequential(
                    List.of(1, 2, 3),
                    i -> {
                      calls.incrementAndGet();
                      if (i != 2) {
                        throw new SQLException("boom " + i, "42000", i);
                      }
                      return i;
                    }))
        .isInstanceOf(SQLException.class)
        .hasMessage("Backend 'a' failed: boom 1")
        .satisfies(
            e -> {
              SQLException first = (SQLException) e;
              assertThat(first.getSQLState()).isEqualTo("42000");
              assertThat(first.getErrorCode()).isEqualTo(1);
              assertThat(first.getCause()).hasMessage("boom 1");
              SQLException next = first.getNextException();
              assertThat((Throwable) next).hasMessage("Backend 'c' failed: boom 3");
              assertThat((Object) next.getNextException()).isNull();
            });
    assertThat(calls).hasValue(3);
  }

  @Test
  void parallelFailuresAreReportedTheSameWay() {
    assertThatThrownBy(
            () ->
                fanOut.parallel(
                    List.of(1, 2, 3),
                    i -> {
                      if (i == 3) {
                        throw new IllegalStateException("runtime " + i);
                      }
                      return i;
                    }))
        .isInstanceOf(SQLException.class)
        .hasMessage("Backend 'c' failed: runtime 3")
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void whenOneBackendFailsTheHealthyBackendsCloseableResultsAreClosed() throws Exception {
    ResultSet healthy = mock(ResultSet.class);
    ResultSet stubborn = mock(ResultSet.class);
    SQLException closeFailure = new SQLException("close failed");
    doThrow(closeFailure).when(stubborn).close();

    assertThatThrownBy(
            () ->
                fanOut.parallel(
                    List.of(0, 1, 2),
                    i -> {
                      if (i == 1) {
                        throw new SQLException("boom", "42000", 1);
                      }
                      return i == 0 ? healthy : stubborn;
                    }))
        .isInstanceOf(SQLException.class)
        .hasMessage("Backend 'b' failed: boom")
        .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(closeFailure));
    verify(healthy).close();
    verify(stubborn).close();
  }

  @Test
  void sequentialFailuresAlsoCloseTheHealthyResults() throws Exception {
    ResultSet healthy = mock(ResultSet.class);

    assertThatThrownBy(
            () ->
                fanOut.sequential(
                    List.of(0, 1),
                    i -> {
                      if (i == 1) {
                        throw new SQLException("boom");
                      }
                      return healthy;
                    }))
        .isInstanceOf(SQLException.class);
    verify(healthy).close();
  }

  @Test
  void resultsAreNotClosedWhenEveryBackendSucceeds() throws Exception {
    ResultSet one = mock(ResultSet.class);
    ResultSet two = mock(ResultSet.class);

    fanOut.parallel(List.of(one, two), rs -> rs);

    verify(one, never()).close();
    verify(two, never()).close();
  }

  @Test
  void interruptingTheWaiterRunsTheInterruptHookBeforeRethrowing() throws Exception {
    FanOut shared = new FanOut(List.of("a", "b"));
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch never = new CountDownLatch(1);
    AtomicInteger hooked = new AtomicInteger();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicReference<Boolean> flag = new AtomicReference<>();
    Thread waiter =
        new Thread(
            () -> {
              try {
                shared.parallel(
                    List.of(1, 2),
                    i -> {
                      started.countDown();
                      never.await();
                      return i;
                    },
                    () -> {
                      hooked.incrementAndGet();
                      throw new IllegalStateException("ignored");
                    });
              } catch (Throwable t) {
                thrown.set(t);
                flag.set(Thread.currentThread().isInterrupted());
              }
            });
    try {
      waiter.start();
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      waiter.interrupt();
      waiter.join(5000);

      assertThat(waiter.isAlive()).isFalse();
      assertThat(hooked).hasValue(1);
      assertThat(thrown.get()).isInstanceOf(SQLException.class).hasMessageContaining("Interrupted");
      assertThat(flag.get()).isTrue();
    } finally {
      shared.close();
    }
  }

  @Test
  void errorsPropagateUnwrapped() {
    assertThatThrownBy(
            () ->
                fanOut.parallel(
                    List.of(1, 2, 3),
                    i -> {
                      throw new OutOfMemoryError("simulated");
                    }))
        .isInstanceOf(OutOfMemoryError.class);
  }

  @Test
  void closeReleasesAThreadWaitingOnAQueuedTask() throws Exception {
    FanOut shared = new FanOut(List.of("a", "b"));
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch never = new CountDownLatch(1);
    ExecutorService callers = Executors.newFixedThreadPool(2);
    try {
      // The first caller occupies both pool threads.
      Future<?> busy =
          callers.submit(
              () ->
                  shared.parallel(
                      List.of(1, 2),
                      i -> {
                        started.countDown();
                        never.await();
                        return i;
                      }));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      // The second caller's tasks are queued behind them.
      Future<?> queued = callers.submit(() -> shared.parallel(List.of(1, 2), i -> i));
      Thread.sleep(300);

      shared.close();

      assertThatThrownBy(() -> queued.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(SQLException.class)
          .satisfies(
              e ->
                  assertThat(((SQLException) e.getCause()).getSQLState())
                      .isEqualTo(ManyfoldException.STATE_CONNECTION_CLOSED));
      // The running tasks are interrupted, so the first caller also finishes.
      assertThatThrownBy(() -> busy.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(SQLException.class);
    } finally {
      callers.shutdownNow();
    }
  }

  @Test
  void operationsAfterCloseFailWithConnectionClosedAndDoNotCreateThreads() {
    FanOut closed = new FanOut(List.of("a", "b"));
    closed.close();
    long before = manyfoldThreads();

    assertThatThrownBy(() -> closed.parallel(List.of(1, 2), i -> i))
        .isInstanceOf(SQLException.class)
        .hasMessage("Connection closed")
        .satisfies(
            e ->
                assertThat(((SQLException) e).getSQLState())
                    .isEqualTo(ManyfoldException.STATE_CONNECTION_CLOSED));
    assertThatThrownBy(() -> closed.sequential(List.of(1, 2), i -> i))
        .isInstanceOf(SQLException.class)
        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08003"));
    assertThat(manyfoldThreads()).isLessThanOrEqualTo(before);
  }

  @Test
  void aSingleBackendFanOutAlsoRefusesAfterClose() {
    FanOut single = new FanOut(List.of("only"));
    single.close();

    assertThatThrownBy(() -> single.parallel(List.of(1), i -> i))
        .isInstanceOf(SQLException.class)
        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("08003"));
  }

  @Test
  void poolThreadsAreNamedByPoolAndIndexAndAreDaemons() throws Exception {
    FanOut named = new FanOut(List.of("a", "b"));
    try {
      List<String> names =
          named.parallel(
              List.of(1, 2),
              i -> {
                Thread t = Thread.currentThread();
                return t.getName() + (t.isDaemon() ? "" : "!");
              });

      assertThat(names).allMatch(n -> n.matches("manyfold-\\d+-\\d+"));
      assertThat(names).doesNotHaveDuplicates();
    } finally {
      named.close();
    }
  }

  private static long manyfoldThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(t -> t.getName().startsWith("manyfold-") && t.isAlive())
        .count();
  }
}
