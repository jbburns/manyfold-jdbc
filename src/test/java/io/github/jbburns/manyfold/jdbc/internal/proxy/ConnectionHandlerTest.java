package io.github.jbburns.manyfold.jdbc.internal.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import io.github.jbburns.manyfold.jdbc.support.MockedPair;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConnectionHandlerTest {

  private MockedPair mocks;

  @BeforeEach
  void setUp() throws SQLException {
    mocks = new MockedPair();
  }

  @AfterEach
  void tearDown() throws SQLException {
    mocks.close();
  }

  @Test
  void isValidIsTrueOnlyWhenEveryBackendIsValid() throws Exception {
    when(mocks.a.isValid(5)).thenReturn(true);
    when(mocks.b.isValid(5)).thenReturn(true);
    try (Connection conn = mocks.open()) {
      assertThat(conn.isValid(5)).isTrue();
    }
  }

  @Test
  void isValidIsFalseWhenTheSecondBackendIsInvalid() throws Exception {
    when(mocks.a.isValid(5)).thenReturn(true);
    when(mocks.b.isValid(5)).thenReturn(false);
    try (Connection conn = mocks.open()) {
      assertThat(conn.isValid(5)).isFalse();
    }
  }

  @Test
  void isValidIsFalseWhenThePrimaryIsInvalid() throws Exception {
    when(mocks.a.isValid(5)).thenReturn(false);
    when(mocks.b.isValid(5)).thenReturn(true);
    try (Connection conn = mocks.open()) {
      assertThat(conn.isValid(5)).isFalse();
    }
  }

  @Test
  void createStatementFailingOnTheSecondBackendClosesTheFirstBackendsStatement() throws Exception {
    SQLException vendor = new SQLException("no more cursors", "53200");
    when(mocks.b.createStatement()).thenThrow(vendor);
    try (Connection conn = mocks.open()) {
      assertThatThrownBy(conn::createStatement)
          .isInstanceOf(ManyfoldException.class)
          .hasMessageStartingWith("Backend 'b' failed:")
          .hasCause(vendor)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("53200"));

      verify(mocks.statementA).close();
    }
  }

  @Test
  void aFailureWhileClosingTheFirstStatementIsSuppressedNotLost() throws Exception {
    SQLException closeFailure = new SQLException("close failed");
    doThrow(closeFailure).when(mocks.statementA).close();
    when(mocks.b.createStatement()).thenThrow(new SQLException("no more cursors"));
    try (Connection conn = mocks.open()) {
      assertThatThrownBy(conn::createStatement)
          .isInstanceOf(ManyfoldException.class)
          .hasMessageStartingWith("Backend 'b' failed:")
          .satisfies(e -> assertThat(e.getCause().getSuppressed()).containsExactly(closeFailure));
    }
  }

  @Test
  void createStatementFailingOnThePrimaryHasNothingToClose() throws Exception {
    when(mocks.a.createStatement()).thenThrow(new SQLException("primary down"));
    try (Connection conn = mocks.open()) {
      assertThatThrownBy(conn::createStatement)
          .isInstanceOf(ManyfoldException.class)
          .hasMessageStartingWith("Backend 'a' failed:");

      verify(mocks.b, never()).createStatement();
    }
  }

  @Test
  void eachBackendReceivesItsOwnSavepointOnRollbackAndRelease() throws Exception {
    Savepoint onA = mock(Savepoint.class);
    Savepoint onB = mock(Savepoint.class);
    when(onA.getSavepointName()).thenReturn("sp-a");
    when(onA.getSavepointId()).thenReturn(11);
    when(mocks.a.setSavepoint("sp")).thenReturn(onA);
    when(mocks.b.setSavepoint("sp")).thenReturn(onB);
    try (Connection conn = mocks.open()) {
      Savepoint savepoint = conn.setSavepoint("sp");

      // Identity and name come from the primary.
      assertThat(savepoint.getSavepointName()).isEqualTo("sp-a");
      assertThat(savepoint.getSavepointId()).isEqualTo(11);

      conn.rollback(savepoint);
      conn.releaseSavepoint(savepoint);

      verify(mocks.a).rollback(onA);
      verify(mocks.b).rollback(onB);
      verify(mocks.a).releaseSavepoint(onA);
      verify(mocks.b).releaseSavepoint(onB);
      verify(mocks.a, never()).rollback(onB);
      verify(mocks.b, never()).rollback(onA);
      verify(mocks.a, never()).releaseSavepoint(onB);
      verify(mocks.b, never()).releaseSavepoint(onA);
    }
  }

  @Test
  void unnamedSavepointsAndPlainRollbackFanOut() throws Exception {
    Savepoint onA = mock(Savepoint.class);
    Savepoint onB = mock(Savepoint.class);
    when(mocks.a.setSavepoint()).thenReturn(onA);
    when(mocks.b.setSavepoint()).thenReturn(onB);
    try (Connection conn = mocks.open()) {
      Savepoint savepoint = conn.setSavepoint();
      conn.rollback();

      assertThat(savepoint).isNotNull();
      verify(mocks.a).rollback();
      verify(mocks.b).rollback();
    }
  }

  @Test
  void aSavepointFromAnotherConnectionIsRefused() throws Exception {
    Savepoint foreign = mock(Savepoint.class);
    try (Connection conn = mocks.open()) {
      assertThatThrownBy(() -> conn.rollback(foreign))
          .isInstanceOf(SQLException.class)
          .hasMessage("Savepoint was not created by this connection");
      assertThatThrownBy(() -> conn.releaseSavepoint(foreign))
          .isInstanceOf(SQLException.class)
          .hasMessage("Savepoint was not created by this connection");

      verify(mocks.a, never()).rollback(foreign);
      verify(mocks.b, never()).rollback(foreign);
    }
  }

  @Test
  void abortIsForwardedToEveryBackendWithTheExecutor() throws Exception {
    Executor executor = Runnable::run;
    try (Connection conn = mocks.open()) {
      conn.abort(executor);

      verify(mocks.a).abort(executor);
      verify(mocks.b).abort(executor);
    }
  }

  @Test
  void setClientInfoReachesEveryBackend() throws Exception {
    Properties info = new Properties();
    info.setProperty("ApplicationName", "reports");
    try (Connection conn = mocks.open()) {
      conn.setClientInfo("ApplicationName", "reports");
      conn.setClientInfo(info);

      verify(mocks.a).setClientInfo("ApplicationName", "reports");
      verify(mocks.b).setClientInfo("ApplicationName", "reports");
      verify(mocks.a).setClientInfo(info);
      verify(mocks.b).setClientInfo(info);
    }
  }

  @Test
  void getClientInfoComesFromThePrimaryOnly() throws Exception {
    Properties fromA = new Properties();
    fromA.setProperty("ApplicationName", "from-a");
    Properties fromB = new Properties();
    fromB.setProperty("ApplicationName", "from-b");
    when(mocks.a.getClientInfo()).thenReturn(fromA);
    when(mocks.b.getClientInfo()).thenReturn(fromB);
    when(mocks.a.getClientInfo("ApplicationName")).thenReturn("from-a");
    when(mocks.b.getClientInfo("ApplicationName")).thenReturn("from-b");
    try (Connection conn = mocks.open()) {
      assertThat(conn.getClientInfo()).isSameAs(fromA);
      assertThat(conn.getClientInfo("ApplicationName")).isEqualTo("from-a");

      verify(mocks.b, never()).getClientInfo();
      verify(mocks.b, never()).getClientInfo("ApplicationName");
    }
  }

  @Test
  void setTypeMapReachesEveryBackendAndGetTypeMapComesFromThePrimary() throws Exception {
    Map<String, Class<?>> map = Map.of("point", String.class);
    Map<String, Class<?>> primaryMap = Map.of("primary", Integer.class);
    when(mocks.a.getTypeMap()).thenReturn(primaryMap);
    when(mocks.b.getTypeMap()).thenReturn(Map.of("secondary", Long.class));
    try (Connection conn = mocks.open()) {
      conn.setTypeMap(map);

      verify(mocks.a).setTypeMap(map);
      verify(mocks.b).setTypeMap(map);
      assertThat(conn.getTypeMap()).isSameAs(primaryMap);
      verify(mocks.b, never()).getTypeMap();
    }
  }

  @Test
  void createBlobComesFromThePrimaryOnly() throws Exception {
    Blob blob = mock(Blob.class);
    when(mocks.a.createBlob()).thenReturn(blob);
    try (Connection conn = mocks.open()) {
      assertThat(conn.createBlob()).isSameAs(blob);

      verify(mocks.b, never()).createBlob();
    }
  }
}
