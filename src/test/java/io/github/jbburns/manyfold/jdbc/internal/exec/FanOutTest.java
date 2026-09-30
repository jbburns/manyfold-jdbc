package io.github.jbburns.manyfold.jdbc.internal.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
        .isInstanceOf(ManyfoldException.class)
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
        .isInstanceOf(ManyfoldException.class)
        .hasMessage("Backend 'c' failed: runtime 3")
        .hasCauseInstanceOf(IllegalStateException.class);
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
}
