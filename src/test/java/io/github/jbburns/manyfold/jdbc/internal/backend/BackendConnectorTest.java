package io.github.jbburns.manyfold.jdbc.internal.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import io.github.jbburns.manyfold.jdbc.support.StubDriver;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class BackendConnectorTest {

  private static String url(StubDriver driver, String options, String... keys) {
    StringBuilder url = new StringBuilder("jdbc:manyfold:").append(options);
    for (int i = 0; i < keys.length; i++) {
      url.append(i == 0 ? "" : " || ").append(keys[i]).append('=').append(driver.url(keys[i]));
    }
    return url.toString();
  }

  @Test
  void aDriverReturningNoConnectionFailsNamingTheBackendAndClosesTheOthers() throws Exception {
    Connection opened = mock(Connection.class);
    try (StubDriver driver = StubDriver.registered().connection("first", opened)) {
      // "second" is unknown to the stub, so connect() answers null.
      String url = url(driver, "", "first", "second");

      assertThatThrownBy(() -> DriverManager.getConnection(url))
          .isInstanceOf(ManyfoldException.class)
          .hasMessageStartingWith("Backend 'second' failed:")
          .hasMessageContaining("returned no connection")
          .hasMessageContaining(StubDriver.class.getName())
          .extracting(e -> ((SQLException) e).getSQLState())
          .isEqualTo(ManyfoldException.STATE_CONNECTION_FAILURE);
      verify(opened).close();
    }
  }

  @Test
  void aNullConnectionMessageDoesNotLeakCredentialsInTheUrl() throws Exception {
    try (StubDriver driver = StubDriver.registered()) {
      String url = "jdbc:manyfold:x=" + driver.url("nothing;password=s3cret");

      assertThatThrownBy(() -> DriverManager.getConnection(url))
          .isInstanceOf(SQLException.class)
          .hasMessageStartingWith("Backend 'x' failed:")
          .hasMessageNotContaining("s3cret");
    }
  }

  @Test
  void aConnectThatThrowsSqlExceptionIsWrappedWithTheBackendName() throws Exception {
    Connection opened = mock(Connection.class);
    SQLException vendor = new SQLException("host unreachable", "08004", 1234);
    try (StubDriver driver =
        StubDriver.registered().connection("first", opened).failure("second", vendor)) {
      String url = url(driver, "", "first", "second");

      assertThatThrownBy(() -> DriverManager.getConnection(url))
          .isInstanceOf(SQLException.class)
          .hasMessageStartingWith("Backend 'second' failed:")
          .hasMessageContaining("host unreachable")
          .hasCause(vendor)
          .satisfies(
              e -> {
                assertThat(((SQLException) e).getSQLState()).isEqualTo("08004");
                assertThat(((SQLException) e).getErrorCode()).isEqualTo(1234);
              });
      verify(opened).close();
    }
  }

  @Test
  void aConnectThatThrowsAnUncheckedExceptionIsWrappedToo() throws Exception {
    IllegalStateException vendor = new IllegalStateException("driver bug");
    try (StubDriver driver = StubDriver.registered().failure("only", vendor)) {
      String url = url(driver, "", "only");

      assertThatThrownBy(() -> DriverManager.getConnection(url))
          .isInstanceOf(SQLException.class)
          .hasMessageStartingWith("Backend 'only' failed:")
          .hasCause(vendor);
    }
  }

  @Test
  void aBackendThatRefusesSetReadOnlyIsToleratedAndStaysUsable() throws Exception {
    Connection refusesChecked = mock(Connection.class);
    Connection refusesUnchecked = mock(Connection.class);
    doThrow(new SQLException("cannot change read-only after connect"))
        .when(refusesChecked)
        .setReadOnly(true);
    doThrow(new UnsupportedOperationException("nope")).when(refusesUnchecked).setReadOnly(true);
    Statement s1 = mock(Statement.class);
    Statement s2 = mock(Statement.class);
    when(refusesChecked.createStatement()).thenReturn(s1);
    when(refusesUnchecked.createStatement()).thenReturn(s2);
    when(s1.execute("SELECT 1")).thenReturn(false);
    when(s2.execute("SELECT 1")).thenReturn(false);
    when(refusesChecked.isValid(1)).thenReturn(true);
    when(refusesUnchecked.isValid(1)).thenReturn(true);

    try (StubDriver driver =
            StubDriver.registered()
                .connection("first", refusesChecked)
                .connection("second", refusesUnchecked);
        Connection conn = DriverManager.getConnection(url(driver, "", "first", "second"));
        Statement s = conn.createStatement()) {
      assertThat(conn.isReadOnly()).isTrue();
      assertThat(conn.isValid(1)).isTrue();
      assertThat(s.execute("SELECT 1")).isFalse();
      verify(refusesChecked).setReadOnly(true);
      verify(refusesUnchecked).setReadOnly(true);
      verify(s1).execute("SELECT 1");
      verify(s2).execute("SELECT 1");
    }
  }

  @Test
  void setReadOnlyIsNotRequestedWhenReadOnlyModeIsOff() throws Exception {
    Connection backend = mock(Connection.class);
    try (StubDriver driver = StubDriver.registered().connection("only", backend);
        Connection conn = DriverManager.getConnection(url(driver, "readOnly=false;", "only"))) {
      assertThat(conn.isClosed()).isFalse();
      verify(backend, never()).setReadOnly(true);
    }
  }

  @Test
  void aBackendThatRefusesSetReadOnlyLeavesWarningsAheadOfThePrimarysOwn() throws Exception {
    Connection first = mock(Connection.class);
    Connection second = mock(Connection.class);
    Connection third = mock(Connection.class);
    doThrow(new SQLException("no read-only for jdbc:x://h/db?password=s3cret"))
        .when(first)
        .setReadOnly(true);
    doThrow(new UnsupportedOperationException("nope")).when(third).setReadOnly(true);
    SQLWarning primaryOwn = new SQLWarning("primary's own");
    when(first.getWarnings()).thenReturn(primaryOwn);

    try (StubDriver driver =
            StubDriver.registered()
                .connection("first", first)
                .connection("second", second)
                .connection("third", third);
        Connection conn =
            DriverManager.getConnection(url(driver, "", "first", "second", "third"))) {
      SQLWarning warning = conn.getWarnings();

      assertThat((Object) warning).isNotNull();
      assertThat(warning.getMessage())
          .contains("Backend 'first'")
          .contains("setReadOnly(true)")
          .doesNotContain("s3cret");
      assertThat(warning.getSQLState()).isEqualTo("01000");
      SQLWarning next = warning.getNextWarning();
      assertThat((Object) next).isNotNull();
      assertThat(next.getMessage()).contains("Backend 'third'");
      assertThat(next.getSQLState()).isEqualTo("01000");
      assertThat((Object) next.getNextWarning()).isSameAs(primaryOwn);

      conn.clearWarnings();

      verify(first).clearWarnings();
      verify(second).clearWarnings();
      verify(third).clearWarnings();
      when(first.getWarnings()).thenReturn(null);
      assertThat((Object) conn.getWarnings()).isNull();
    }
  }

  @Test
  void noWarningIsRecordedWhenEveryBackendAcceptsSetReadOnly() throws Exception {
    Connection first = mock(Connection.class);
    SQLWarning primaryOwn = new SQLWarning("primary's own");
    when(first.getWarnings()).thenReturn(primaryOwn);
    try (StubDriver driver = StubDriver.registered().connection("first", first);
        Connection conn = DriverManager.getConnection(url(driver, "", "first"))) {
      assertThat((Object) conn.getWarnings()).isSameAs(primaryOwn);
    }
  }
}
