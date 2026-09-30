package io.github.jbburns.manyfold.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jbburns.manyfold.jdbc.support.H2Pair;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MergedResultSetTest {

  private H2Pair dbs;
  private Connection conn;

  @BeforeEach
  void connect() throws SQLException {
    dbs = new H2Pair();
    conn = DriverManager.getConnection(dbs.manyfoldUrl());
  }

  @AfterEach
  void disconnect() throws SQLException {
    conn.close();
    dbs.close();
  }

  private static List<List<Object>> rows(ResultSet rs) throws SQLException {
    List<List<Object>> rows = new ArrayList<>();
    int n = rs.getMetaData().getColumnCount();
    while (rs.next()) {
      List<Object> row = new ArrayList<>();
      for (int i = 1; i <= n; i++) {
        row.add(rs.getObject(i));
      }
      rows.add(row);
    }
    return rows;
  }

  @Test
  void sourceColumnComesFirstAndRowsConcatenateInUrlOrder() throws Exception {
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id, customer FROM orders ORDER BY id")) {
      assertThat(rows(rs))
          .containsExactly(
              List.of("prod", 1, "alice"), List.of("prod", 2, "bob"), List.of("dev", 3, "carol"));
    }
  }

  @Test
  void metadataPrependsTheSourceColumn() throws Exception {
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id, customer, amount FROM orders")) {
      ResultSetMetaData m = rs.getMetaData();

      assertThat(m.getColumnCount()).isEqualTo(4);
      assertThat(m.getColumnLabel(1)).isEqualTo("source_database");
      assertThat(m.getColumnName(1)).isEqualTo("source_database");
      assertThat(m.getColumnType(1)).isEqualTo(Types.VARCHAR);
      assertThat(m.getColumnTypeName(1)).isEqualTo("VARCHAR");
      assertThat(m.getColumnClassName(1)).isEqualTo(String.class.getName());
      assertThat(m.isNullable(1)).isEqualTo(ResultSetMetaData.columnNoNulls);
      assertThat(m.isReadOnly(1)).isTrue();
      assertThat(m.getColumnDisplaySize(1)).isGreaterThanOrEqualTo("source_database".length());
      assertThat(m.getColumnLabel(2)).isEqualToIgnoringCase("id");
      assertThat(m.getColumnLabel(4)).isEqualToIgnoringCase("amount");
      assertThat(m.getColumnType(4)).isIn(Types.DECIMAL, Types.NUMERIC);
      assertThat(m.getScale(4)).isEqualTo(2);
      assertThatThrownBy(() -> m.getColumnLabel(5)).isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> m.getColumnLabel(0)).isInstanceOf(SQLException.class);
    }
  }

  @Test
  void gettersWorkByIndexLabelAndFindColumn() throws Exception {
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id, customer, amount FROM orders ORDER BY id")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("prod");
      assertThat(rs.getString("source_database")).isEqualTo("prod");
      assertThat(rs.getString("SOURCE_DATABASE")).isEqualTo("prod");
      assertThat(rs.getInt(2)).isEqualTo(1);
      assertThat(rs.getInt("id")).isEqualTo(1);
      assertThat(rs.getString("customer")).isEqualTo("alice");
      assertThat(rs.getBigDecimal(4)).isEqualByComparingTo(new BigDecimal("10.50"));
      assertThat(rs.findColumn("source_database")).isEqualTo(1);
      assertThat(rs.findColumn("customer")).isEqualTo(3);
      assertThat(rs.getObject(1, String.class)).isEqualTo("prod");
      assertThat(rs.getObject(2, Integer.class)).isEqualTo(1);
      assertThat(new java.io.BufferedReader(rs.getCharacterStream(1)).readLine()).isEqualTo("prod");
      assertThat(rs.getBytes(1)).asString().isEqualTo("prod");
    }
  }

  @Test
  void sourceColumnIsAStringAndRefusesOtherConversions() throws Exception {
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM orders")) {
      rs.next();
      assertThatThrownBy(() -> rs.getInt(1)).isInstanceOf(SQLDataException.class);
      assertThatThrownBy(() -> rs.getObject(1, Integer.class)).isInstanceOf(SQLDataException.class);
      assertThatThrownBy(() -> rs.getDate("source_database")).isInstanceOf(SQLDataException.class);
    }
  }

  @Test
  void wasNullTracksTheColumnLastRead() throws Exception {
    try (Connection prod = dbs.prod();
        Statement seed = prod.createStatement()) {
      seed.execute("INSERT INTO orders VALUES (9, NULL, NULL)");
    }
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id, customer FROM orders WHERE id = 9")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(3)).isNull();
      assertThat(rs.wasNull()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("prod");
      assertThat(rs.wasNull()).isFalse();
      assertThat(rs.getInt(2)).isEqualTo(9);
      assertThat(rs.wasNull()).isFalse();
    }
  }

  @Test
  void sourceColumnCanBeRenamed() throws Exception {
    try (Connection c = DriverManager.getConnection(dbs.manyfoldUrl("sourceColumn=origin"));
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM orders")) {
      assertThat(rs.getMetaData().getColumnLabel(1)).isEqualTo("origin");
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("origin")).isEqualTo("prod");
      assertThat(rs.findColumn("origin")).isEqualTo(1);
    }
  }

  @Test
  void defaultNamesDescribeTheBackend() throws Exception {
    String url = "jdbc:manyfold:" + dbs.prodUrl + " || " + dbs.devUrl;
    try (Connection c = DriverManager.getConnection(url);
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM orders ORDER BY id")) {
      List<String> sources = new ArrayList<>();
      while (rs.next()) {
        sources.add(rs.getString(1));
      }
      assertThat(sources).hasSize(3);
      assertThat(sources.get(0)).startsWith("h2:mem:prod_").doesNotContain("DB_CLOSE_DELAY");
      assertThat(sources.get(2)).startsWith("h2:mem:dev_");
    }
  }

  @Test
  void backendsThatReturnNoRowsAreSkipped() throws Exception {
    try (Statement s = conn.createStatement()) {
      try (ResultSet rs = s.executeQuery("SELECT id FROM orders WHERE id = 3")) {
        assertThat(rows(rs)).containsExactly(List.of("dev", 3));
      }
      try (ResultSet rs = s.executeQuery("SELECT id FROM orders WHERE id = 99")) {
        assertThat(rs.isBeforeFirst()).isTrue();
        assertThat(rs.next()).isFalse();
        assertThat(rs.isAfterLast()).isTrue();
        assertThat(rs.next()).isFalse();
      }
    }
  }

  @Test
  void columnCountMismatchFailsBeforeAnyRowIsRead() throws Exception {
    try (Connection dev = dbs.dev();
        Statement seed = dev.createStatement()) {
      seed.execute("ALTER TABLE orders DROP COLUMN amount");
    }
    try (Statement s = conn.createStatement()) {
      assertThatThrownBy(() -> s.executeQuery("SELECT * FROM orders"))
          .isInstanceOf(ManyfoldException.class)
          .hasMessageContaining("Backend 'dev' returned 2 columns but 'prod' returned 3");
    }
  }

  @Test
  void queryFailureNamesTheBackendAndKeepsTheVendorException() throws Exception {
    try (Connection dev = dbs.dev();
        Statement seed = dev.createStatement()) {
      seed.execute("DROP TABLE only_here");
    }
    try (Statement s = conn.createStatement()) {
      assertThatThrownBy(() -> s.executeQuery("SELECT * FROM only_here"))
          .isInstanceOf(SQLSyntaxErrorException.class)
          .hasMessageStartingWith("Backend 'dev' failed:")
          .hasMessageContaining("ONLY_HERE")
          .satisfies(
              e -> {
                SQLException sql = (SQLException) e;
                assertThat(sql.getCause()).isInstanceOf(SQLException.class);
                assertThat(sql.getSQLState())
                    .isEqualTo(((SQLException) sql.getCause()).getSQLState());
                assertThat(sql.getErrorCode())
                    .isEqualTo(((SQLException) sql.getCause()).getErrorCode());
                assertThat((Object) sql.getNextException()).isNull();
              });
    }
  }

  @Test
  void failuresOnEveryBackendAreChained() throws Exception {
    try (Statement s = conn.createStatement()) {
      assertThatThrownBy(() -> s.executeQuery("SELECT * FROM no_such_table"))
          .isInstanceOf(SQLSyntaxErrorException.class)
          .hasMessageStartingWith("Backend 'prod' failed:")
          .satisfies(
              e -> {
                SQLException next = ((SQLException) e).getNextException();
                assertThat((Throwable) next).isInstanceOf(SQLSyntaxErrorException.class);
                assertThat(next.getMessage()).startsWith("Backend 'dev' failed:");
              });
    }
  }

  @Test
  void positionAndCapabilityMethods() throws Exception {
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM orders ORDER BY id")) {
      assertThat(rs.getType()).isEqualTo(ResultSet.TYPE_FORWARD_ONLY);
      assertThat(rs.getConcurrency()).isEqualTo(ResultSet.CONCUR_READ_ONLY);
      assertThat(rs.getFetchDirection()).isEqualTo(ResultSet.FETCH_FORWARD);
      rs.setFetchDirection(ResultSet.FETCH_FORWARD);
      rs.setFetchSize(10);
      assertThat(rs.isBeforeFirst()).isTrue();
      assertThat(rs.getRow()).isZero();
      assertThat(rs.next()).isTrue();
      assertThat(rs.isFirst()).isTrue();
      assertThat(rs.getRow()).isEqualTo(1);
      assertThat(rs.next()).isTrue();
      assertThat(rs.next()).isTrue();
      assertThat(rs.getRow()).isEqualTo(3);
      assertThat(rs.isFirst()).isFalse();
      assertThat(rs.next()).isFalse();
      assertThat(rs.isAfterLast()).isTrue();
      assertThat(rs.rowUpdated()).isFalse();
      assertThat(rs.getStatement()).isSameAs(s);
      assertThat(rs.toString()).isEqualTo("ManyfoldResultSet[prod, dev]");

      assertThatThrownBy(() -> rs.setFetchDirection(ResultSet.FETCH_REVERSE))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
      assertThatThrownBy(rs::previous).isInstanceOf(SQLFeatureNotSupportedException.class);
      assertThatThrownBy(() -> rs.absolute(1)).isInstanceOf(SQLFeatureNotSupportedException.class);
      assertThatThrownBy(() -> rs.updateInt(2, 5))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
      assertThatThrownBy(rs::deleteRow).isInstanceOf(SQLFeatureNotSupportedException.class);
      assertThatThrownBy(rs::isLast).isInstanceOf(SQLFeatureNotSupportedException.class);
      assertThatThrownBy(() -> rs.getString(1)).isInstanceOf(SQLException.class);
    }
  }

  @Test
  void closeClosesEveryCursor() throws Exception {
    Statement s = conn.createStatement();
    ResultSet rs = s.executeQuery("SELECT id FROM orders");
    assertThat(rs.isClosed()).isFalse();
    rs.close();
    rs.close();
    assertThat(rs.isClosed()).isTrue();
    assertThatThrownBy(rs::next).isInstanceOf(SQLException.class);
    s.close();
    assertThat(s.isClosed()).isTrue();
  }

  @Test
  void preparedStatementParametersReachEveryBackend() throws Exception {
    try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM orders WHERE amount > ?")) {
      ps.setBigDecimal(1, new BigDecimal("15"));
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rows(rs)).containsExactly(List.of("prod", 2), List.of("dev", 3));
      }
      assertThat(ps.getMetaData().getColumnCount()).isEqualTo(2);
      assertThat(ps.getMetaData().getColumnLabel(1)).isEqualTo("source_database");
      assertThat(ps.getConnection()).isSameAs(conn);
    }
  }

  @Test
  void executeThenGetResultSet() throws Exception {
    try (Statement s = conn.createStatement()) {
      assertThat(s.execute("SELECT id FROM orders ORDER BY id")).isTrue();
      try (ResultSet rs = s.getResultSet()) {
        assertThat(rows(rs)).hasSize(3);
      }
      assertThat(s.getUpdateCount()).isEqualTo(-1);
      assertThat(s.getMoreResults()).isFalse();
      assertThat(s.getResultSet()).isNull();
    }
  }

  @Test
  void wrappersNeverLeakBackendObjects() throws Exception {
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT id FROM orders")) {
      assertThat(conn.isWrapperFor(Connection.class)).isTrue();
      assertThat(conn.unwrap(Connection.class)).isSameAs(conn);
      assertThat(conn.isWrapperFor(org.h2.jdbc.JdbcConnection.class)).isFalse();
      assertThatThrownBy(() -> conn.unwrap(org.h2.jdbc.JdbcConnection.class))
          .isInstanceOf(SQLException.class);
      assertThat(rs.isWrapperFor(org.h2.jdbc.JdbcResultSet.class)).isFalse();
      assertThat(rs.unwrap(ResultSet.class)).isSameAs(rs);
      assertThat(s.unwrap(Statement.class)).isSameAs(s);
      assertThat(conn.hashCode()).isEqualTo(System.identityHashCode(conn));
      assertThat(conn.equals(conn)).isTrue();
      assertThat(conn.equals(s)).isFalse();
    }
  }

  @Test
  void backendsRunConcurrently() throws Exception {
    for (Connection direct : new Connection[] {dbs.prod(), dbs.dev()}) {
      try (direct;
          Statement s = direct.createStatement()) {
        s.execute("CREATE ALIAS SLEEP FOR 'java.lang.Thread.sleep(long)'");
      }
    }
    long start = System.nanoTime();
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT SLEEP(400)")) {
      assertThat(rows(rs)).hasSize(2);
    }
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
    assertThat(elapsedMillis).as("two 400ms queries should overlap").isLessThan(750);
  }
}
