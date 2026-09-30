package io.github.jbburns.manyfold.jdbc.internal.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jbburns.manyfold.jdbc.support.H2Pair;
import io.github.jbburns.manyfold.jdbc.support.MockedPair;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResultSetHandlerTest {

  private H2Pair dbs;
  private MockedPair mocks;

  @BeforeEach
  void setUp() throws SQLException {
    dbs = new H2Pair();
    mocks = new MockedPair();
  }

  @AfterEach
  void tearDown() throws SQLException {
    mocks.close();
    dbs.close();
  }

  /** A one-column cursor that the mock statement of a backend will return. */
  private static ResultSet cursor() throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    ResultSetMetaData meta = mock(ResultSetMetaData.class);
    when(meta.getColumnCount()).thenReturn(1);
    when(rs.getMetaData()).thenReturn(meta);
    return rs;
  }

  private ResultSet mergedMocks(ResultSet first, ResultSet second, Connection conn)
      throws SQLException {
    when(mocks.statementA.executeQuery("SELECT 1")).thenReturn(first);
    when(mocks.statementB.executeQuery("SELECT 1")).thenReturn(second);
    return conn.createStatement().executeQuery("SELECT 1");
  }

  // ---- source column reads on real rows --------------------------------------------------

  @Test
  void sourceColumnCanBeReadInEveryFlavourTheJdbcApiOffers() throws Exception {
    try (Connection conn = DriverManager.getConnection(dbs.manyfoldUrl());
        Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM orders ORDER BY id")) {
      assertThat(rs.next()).isTrue();

      assertThat(rs.getObject(1, Map.of())).isEqualTo("prod");
      assertThat(rs.getObject("source_database", Map.of())).isEqualTo("prod");
      assertThat(rs.getObject(1, Object.class)).isEqualTo("prod");
      assertThat(rs.getObject(1, String.class)).isEqualTo("prod");
      assertThat(rs.getNString(1)).isEqualTo("prod");
      assertThat(rs.getNString("source_database")).isEqualTo("prod");
      try (InputStream ascii = rs.getAsciiStream(1);
          InputStream binary = rs.getBinaryStream(1)) {
        assertThat(ascii.readAllBytes()).isEqualTo("prod".getBytes(StandardCharsets.UTF_8));
        assertThat(binary.readAllBytes()).isEqualTo("prod".getBytes(StandardCharsets.UTF_8));
      }
      try (Reader reader = rs.getNCharacterStream(1)) {
        assertThat(reader.read()).isEqualTo('p');
      }
      assertThat(rs.wasNull()).isFalse();

      // The next backend's rows carry the next backend's name.
      rs.next();
      rs.next();
      assertThat(rs.getNString(1)).isEqualTo("dev");
    }
  }

  @Test
  void sourceColumnHasNoValueOnceTheCursorIsExhausted() throws Exception {
    try (Connection conn = DriverManager.getConnection(dbs.manyfoldUrl());
        Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM orders")) {
      while (rs.next()) {
        // drain
      }

      assertThatThrownBy(() -> rs.getString(1))
          .isInstanceOf(SQLException.class)
          .hasMessage("No current row")
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("24000"));
      assertThatThrownBy(() -> rs.getString("source_database")).hasMessage("No current row");
    }
  }

  @Test
  void sourceColumnHasNoValueBeforeTheFirstRow() throws Exception {
    try (Connection conn = DriverManager.getConnection(dbs.manyfoldUrl());
        Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM orders")) {
      assertThatThrownBy(() -> rs.getString(1)).hasMessage("No current row");
    }
  }

  @Test
  void warningsAndFetchSettingsAreAnsweredByTheBackendCursor() throws Exception {
    try (Connection conn = DriverManager.getConnection(dbs.manyfoldUrl());
        Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM orders")) {
      rs.next();

      assertThat((Throwable) rs.getWarnings()).isNull();
      rs.clearWarnings();
      assertThat((Throwable) rs.getWarnings()).isNull();
      assertThat(rs.getFetchSize()).isGreaterThanOrEqualTo(0);
      assertThat(rs.getHoldability())
          .isIn(ResultSet.HOLD_CURSORS_OVER_COMMIT, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }
  }

  // ---- mock cursors ----------------------------------------------------------------------

  @Test
  void warningsFetchSizeAndHoldabilityAreReadFromTheCurrentCursor() throws Exception {
    ResultSet first = cursor();
    ResultSet second = cursor();
    SQLWarning warning = new SQLWarning("first warns");
    when(first.next()).thenReturn(true, false);
    when(second.next()).thenReturn(true, false);
    when(first.getWarnings()).thenReturn(warning);
    when(first.getFetchSize()).thenReturn(50);
    when(second.getFetchSize()).thenReturn(70);
    when(first.getHoldability()).thenReturn(ResultSet.HOLD_CURSORS_OVER_COMMIT);
    when(second.getHoldability()).thenReturn(ResultSet.CLOSE_CURSORS_AT_COMMIT);

    try (Connection conn = mocks.open();
        ResultSet rs = mergedMocks(first, second, conn)) {
      rs.next();
      assertThat((Throwable) rs.getWarnings()).isSameAs(warning);
      assertThat(rs.getFetchSize()).isEqualTo(50);
      assertThat(rs.getHoldability()).isEqualTo(ResultSet.HOLD_CURSORS_OVER_COMMIT);

      rs.next();
      assertThat(rs.getFetchSize()).isEqualTo(70);
      assertThat(rs.getHoldability()).isEqualTo(ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }
  }

  @Test
  void clearWarningsAndSetFetchSizeReachEveryCursor() throws Exception {
    ResultSet first = cursor();
    ResultSet second = cursor();

    try (Connection conn = mocks.open();
        ResultSet rs = mergedMocks(first, second, conn)) {
      rs.clearWarnings();
      rs.setFetchSize(25);

      verify(first).clearWarnings();
      verify(second).clearWarnings();
      verify(first).setFetchSize(25);
      verify(second).setFetchSize(25);
    }
  }

  @Test
  void closeAttemptsEveryCursorAndChainsTheFailures() throws Exception {
    ResultSet first = cursor();
    ResultSet second = cursor();
    SQLException firstFailure = new SQLException("first close failed");
    SQLException secondFailure = new SQLException("second close failed");
    doThrow(firstFailure).when(first).close();
    doThrow(secondFailure).when(second).close();

    try (Connection conn = mocks.open()) {
      ResultSet rs = mergedMocks(first, second, conn);

      assertThatThrownBy(rs::close).isSameAs(firstFailure);
      assertThat((Throwable) firstFailure.getNextException()).isSameAs(secondFailure);
      verify(first).close();
      verify(second).close();
      assertThat(rs.isClosed()).isTrue();
      // A second close is a no-op and does not touch the cursors again.
      rs.close();
      verify(first).close();
    }
  }

  @Test
  void closeStillClosesLaterCursorsWhenOnlyTheFirstFails() throws Exception {
    ResultSet first = cursor();
    ResultSet second = cursor();
    SQLException firstFailure = new SQLException("first close failed");
    doThrow(firstFailure).when(first).close();

    try (Connection conn = mocks.open()) {
      ResultSet rs = mergedMocks(first, second, conn);

      assertThatThrownBy(rs::close).isSameAs(firstFailure);
      assertThat((Throwable) firstFailure.getNextException()).isNull();
      verify(second).close();
    }
  }

  @Test
  void aFailureFromNextNamesTheBackendAndKeepsTheSubtype() throws Exception {
    ResultSet first = cursor();
    ResultSet second = cursor();
    when(first.next()).thenReturn(false);
    when(second.next()).thenThrow(new SQLDataException("bad row", "22018", 3));

    try (Connection conn = mocks.open();
        ResultSet rs = mergedMocks(first, second, conn)) {
      assertThatThrownBy(rs::next)
          .isExactlyInstanceOf(SQLDataException.class)
          .hasMessage("Backend 'b' failed: bad row")
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("22018"));
    }
  }

  @Test
  void aFailureFromAColumnGetterNamesTheBackendThatOwnsTheCurrentRow() throws Exception {
    ResultSet first = cursor();
    ResultSet second = cursor();
    when(first.next()).thenReturn(true, false);
    when(second.next()).thenReturn(false);
    when(first.getString(1)).thenThrow(new SQLException("no such column", "42S22"));
    when(first.getInt("missing")).thenThrow(new SQLException("no label", "S0022"));
    when(first.findColumn("missing")).thenThrow(new SQLException("no label", "S0022"));

    try (Connection conn = mocks.open();
        ResultSet rs = mergedMocks(first, second, conn)) {
      assertThat(rs.next()).isTrue();

      assertThatThrownBy(() -> rs.getString(2))
          .isInstanceOf(SQLException.class)
          .hasMessage("Backend 'a' failed: no such column");
      assertThatThrownBy(() -> rs.getInt("missing")).hasMessage("Backend 'a' failed: no label");
      assertThatThrownBy(() -> rs.findColumn("missing")).hasMessage("Backend 'a' failed: no label");
    }
  }

  @Test
  void aNullColumnLabelIsASqlExceptionWithStateInvalidParameter() throws Exception {
    try (Connection conn = DriverManager.getConnection(dbs.manyfoldUrl());
        Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM orders")) {
      assertThat(rs.next()).isTrue();

      assertThatThrownBy(() -> rs.findColumn(null))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("22023"));
      assertThatThrownBy(() -> rs.getString((String) null))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("22023"));
      assertThatThrownBy(() -> rs.getObject((String) null, String.class))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("22023"));
    }
  }
}
