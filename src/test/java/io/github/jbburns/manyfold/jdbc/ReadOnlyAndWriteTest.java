package io.github.jbburns.manyfold.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jbburns.manyfold.jdbc.support.H2Pair;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Savepoint;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReadOnlyAndWriteTest {

  private static final String COUNT = "SELECT count(*) FROM orders";

  private H2Pair dbs;

  @BeforeEach
  void setUp() throws SQLException {
    dbs = new H2Pair();
  }

  @AfterEach
  void tearDown() throws SQLException {
    dbs.close();
  }

  private void assertCounts(long prod, long dev) throws SQLException {
    try (Connection p = dbs.prod();
        Connection d = dbs.dev()) {
      assertThat(H2Pair.count(p, COUNT)).as("prod rows").isEqualTo(prod);
      assertThat(H2Pair.count(d, COUNT)).as("dev rows").isEqualTo(dev);
    }
  }

  private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(ManyfoldException.class)
        .hasMessageStartingWith("Refused in read-only mode")
        .extracting(e -> ((SQLException) e).getSQLState())
        .isEqualTo(ManyfoldException.STATE_READ_ONLY);
  }

  @Test
  void readOnlyIsTheDefaultAndRefusesEveryWritePath() throws Exception {
    try (Connection conn = DriverManager.getConnection(dbs.manyfoldUrl());
        Statement s = conn.createStatement()) {
      assertThat(conn.isReadOnly()).isTrue();

      assertRefused(() -> s.executeUpdate("INSERT INTO orders VALUES (7, 'x', 1)"));
      assertRefused(() -> s.executeLargeUpdate("INSERT INTO orders VALUES (7, 'x', 1)"));
      assertRefused(() -> s.execute("DELETE FROM orders"));
      assertRefused(() -> s.executeQuery("DELETE FROM orders"));
      assertRefused(() -> s.executeQuery("SELECT * FROM orders FOR UPDATE"));
      assertRefused(() -> s.addBatch("INSERT INTO orders VALUES (7, 'x', 1)"));
      assertRefused(s::executeBatch);
      assertRefused(() -> conn.prepareStatement("UPDATE orders SET amount = 0"));
      assertRefused(() -> conn.prepareCall("CALL do_things()"));
      assertRefused(() -> s.executeUpdate("SELECT 1"));

      try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM orders WHERE id = ?")) {
        ps.setInt(1, 1);
        assertRefused(ps::executeUpdate);
        assertRefused(ps::addBatch);
      }
      assertCounts(2, 1);
    }
  }

  @Test
  void readOnlyModeCannotBeSwitchedOffThroughTheConnection() throws Exception {
    try (Connection conn = DriverManager.getConnection(dbs.manyfoldUrl())) {
      conn.setReadOnly(false);

      assertThat(conn.isReadOnly()).isTrue();
      try (Statement s = conn.createStatement()) {
        assertRefused(() -> s.executeUpdate("DELETE FROM orders"));
      }
    }
  }

  @Test
  void readOnlyFalseFansWritesOutAndSumsUpdateCounts() throws Exception {
    try (Connection conn = DriverManager.getConnection(dbs.manyfoldUrl("readOnly=false"));
        Statement s = conn.createStatement()) {
      assertThat(conn.isReadOnly()).isFalse();

      assertThat(s.executeUpdate("INSERT INTO orders VALUES (7, 'x', 1)")).isEqualTo(2);
      assertThat(s.executeLargeUpdate("UPDATE orders SET amount = amount + 1")).isEqualTo(5L);
      assertThat(s.execute("DELETE FROM orders WHERE id = 7")).isFalse();
      assertThat(s.getUpdateCount()).isEqualTo(2);
      assertThat(s.getResultSet()).isNull();
      assertCounts(2, 1);
    }
  }

  @Test
  void readOnlyFalseFromPropertiesWorksToo() throws Exception {
    java.util.Properties props = new java.util.Properties();
    props.setProperty("manyfold.readOnly", "false");
    try (Connection conn = DriverManager.getConnection(dbs.manyfoldUrl(), props);
        Statement s = conn.createStatement()) {
      assertThat(s.executeUpdate("INSERT INTO orders VALUES (7, 'x', 1)")).isEqualTo(2);
      assertCounts(3, 2);
    }
  }

  @Test
  void preparedWritesAndBatchesFanOut() throws Exception {
    try (Connection conn = DriverManager.getConnection(dbs.manyfoldUrl("readOnly=false"));
        PreparedStatement ps = conn.prepareStatement("INSERT INTO orders VALUES (?, ?, ?)")) {
      ps.setInt(1, 10);
      ps.setString(2, "ten");
      ps.setBigDecimal(3, java.math.BigDecimal.ONE);
      assertThat(ps.executeUpdate()).isEqualTo(2);

      ps.setInt(1, 11);
      ps.addBatch();
      ps.setInt(1, 12);
      ps.addBatch();
      assertThat(ps.executeBatch()).containsExactly(2, 2);

      ps.setInt(1, 13);
      ps.addBatch();
      assertThat(ps.executeLargeBatch()).containsExactly(2L);
      assertCounts(6, 5);
    }
  }

  @Test
  void commitAndRollbackReachEveryBackend() throws Exception {
    try (Connection conn = DriverManager.getConnection(dbs.manyfoldUrl("readOnly=false"));
        Statement s = conn.createStatement()) {
      conn.setAutoCommit(false);
      assertThat(conn.getAutoCommit()).isFalse();

      s.executeUpdate("INSERT INTO orders VALUES (20, 'rolled back', 1)");
      conn.rollback();
      assertCounts(2, 1);

      s.executeUpdate("INSERT INTO orders VALUES (21, 'committed', 1)");
      conn.commit();
      assertCounts(3, 2);
    }
  }

  @Test
  void savepointsSpanEveryBackend() throws Exception {
    try (Connection conn = DriverManager.getConnection(dbs.manyfoldUrl("readOnly=false"));
        Statement s = conn.createStatement()) {
      conn.setAutoCommit(false);
      s.executeUpdate("INSERT INTO orders VALUES (30, 'kept', 1)");
      Savepoint sp = conn.setSavepoint("sp1");
      assertThat(sp.getSavepointName()).isEqualTo("sp1");
      s.executeUpdate("INSERT INTO orders VALUES (31, 'undone', 1)");

      conn.rollback(sp);
      conn.commit();

      assertCounts(3, 2);
      assertThatThrownBy(
              () ->
                  conn.rollback(
                      new Savepoint() {
                        @Override
                        public int getSavepointId() {
                          return 1;
                        }

                        @Override
                        public String getSavepointName() {
                          return "foreign";
                        }
                      }))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("not created by this connection");
    }
  }

  @Test
  void writeFailureOnOneBackendIsReportedByName() throws Exception {
    try (Connection dev = dbs.dev();
        Statement seed = dev.createStatement()) {
      seed.execute("INSERT INTO orders VALUES (40, 'already there', 1)");
    }
    try (Connection conn = DriverManager.getConnection(dbs.manyfoldUrl("readOnly=false"));
        Statement s = conn.createStatement()) {
      assertThatThrownBy(() -> s.executeUpdate("INSERT INTO orders VALUES (40, 'dup', 1)"))
          .isInstanceOf(SQLIntegrityConstraintViolationException.class)
          .hasMessageStartingWith("Backend 'dev' failed:");
    }
  }

  @Test
  void sessionSettingsFanOut() throws Exception {
    try (Connection conn = DriverManager.getConnection(dbs.manyfoldUrl());
        Connection prod = dbs.prod()) {
      conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      assertThat(conn.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
      conn.setSchema("PUBLIC");
      assertThat(conn.getSchema()).isEqualTo("PUBLIC");
      conn.clearWarnings();
      assertThat((Object) conn.getWarnings()).isNull();
      assertThat(conn.nativeSQL("SELECT 1")).isEqualTo("SELECT 1");
      assertThat(conn.getHoldability()).isEqualTo(prod.getHoldability());
    }
  }

  @Test
  void closingTheConnectionClosesEveryBackend() throws Exception {
    Connection conn = DriverManager.getConnection(dbs.manyfoldUrl());
    Statement s = conn.createStatement();
    conn.close();
    conn.close();

    assertThat(conn.isClosed()).isTrue();
    assertThat(s.isClosed()).isTrue();
    assertThatThrownBy(conn::createStatement).isInstanceOf(SQLException.class);
  }
}
