package io.github.jbburns.manyfold.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.jbburns.manyfold.jdbc.support.MockedPair;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Per-backend schema substitution through a leading {@code manyfold} comment, over two H2 backends.
 * Prod keeps its orders in schema ZONE1_PROD; dev keeps them in ZONE1_DEV2 and, for the deletion
 * case, in PUBLIC.
 */
class SchemaDirectiveTest {

  private String prodUrl;
  private String devUrl;

  @BeforeEach
  void setUp() throws SQLException {
    String id = UUID.randomUUID().toString().replace("-", "");
    prodUrl = "jdbc:h2:mem:sprod_" + id + ";DB_CLOSE_DELAY=-1";
    devUrl = "jdbc:h2:mem:sdev_" + id + ";DB_CLOSE_DELAY=-1";
    run(
        prodUrl,
        "CREATE SCHEMA zone1_prod",
        "CREATE TABLE zone1_prod.orders (id INT, customer VARCHAR(32))",
        "INSERT INTO zone1_prod.orders VALUES (1, 'alice'), (2, 'bob')");
    run(
        devUrl,
        "CREATE SCHEMA zone1_dev2",
        "CREATE TABLE zone1_dev2.orders (id INT, customer VARCHAR(32))",
        "INSERT INTO zone1_dev2.orders VALUES (3, 'carol')",
        "CREATE TABLE public.orders (id INT, customer VARCHAR(32))",
        "INSERT INTO public.orders VALUES (4, 'dave')");
  }

  @AfterEach
  void tearDown() throws SQLException {
    run(prodUrl, "SHUTDOWN");
    run(devUrl, "SHUTDOWN");
  }

  private static void run(String url, String... statements) throws SQLException {
    try (Connection c = DriverManager.getConnection(url);
        Statement s = c.createStatement()) {
      for (String sql : statements) {
        s.execute(sql);
      }
    }
  }

  private Connection open(String options) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:manyfold:" + options + "prod=" + prodUrl + " || dev=" + devUrl);
  }

  private static String lines(String... lines) {
    return String.join("\n", lines);
  }

  private static List<String> rows(ResultSet rs) throws SQLException {
    List<String> rows = new ArrayList<>();
    while (rs.next()) {
      rows.add(rs.getString(1) + ":" + rs.getInt(2) + ":" + rs.getString(3));
    }
    return rows;
  }

  @Test
  void eachBackendReceivesItsOwnSchemaAndSourceDatabaseComesFirst() throws Exception {
    try (Connection conn = open("");
        Statement s = conn.createStatement();
        ResultSet rs =
            s.executeQuery(
                "-- manyfold dev: zone1_prod=zone1_dev2\n"
                    + "SELECT * FROM zone1_prod.orders ORDER BY id")) {
      assertThat(rs.getMetaData().getColumnName(1)).isEqualTo("source_database");
      assertThat(rows(rs)).containsExactly("prod:1:alice", "prod:2:bob", "dev:3:carol");
    }
  }

  @Test
  void theBlockFormAndSeveralDirectivesWork() throws Exception {
    try (Connection conn = open("");
        Statement s = conn.createStatement();
        ResultSet rs =
            s.executeQuery(
                lines(
                    "/* manyfold dev: zone1_prod=zone1_dev2 */",
                    "-- manyfold prod: nothing_here=zone1_prod",
                    "SELECT * FROM zone1_prod.orders ORDER BY id"))) {
      assertThat(rows(rs)).containsExactly("prod:1:alice", "prod:2:bob", "dev:3:carol");
    }
  }

  @Test
  void aBackendWithoutADirectiveReceivesTheStatementUnchanged() throws Exception {
    // dev has no ZONE1_PROD schema, so an unchanged statement must fail there and only there.
    try (Connection conn = open("");
        Statement s = conn.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.executeQuery(
                      "-- manyfold prod: zone9=zone1_prod\nSELECT * FROM zone1_prod.orders"))
          .isInstanceOf(SQLException.class)
          .hasMessageStartingWith("Backend 'dev' failed: ")
          .hasMessageContaining("ZONE1_PROD");
    }
  }

  @Test
  void aPreparedStatementIsRewrittenOncePerBackendWhenItIsPrepared() throws Exception {
    try (Connection conn = open("");
        PreparedStatement ps =
            conn.prepareStatement(
                "-- manyfold dev: zone1_prod=zone1_dev2\n"
                    + "SELECT * FROM zone1_prod.orders WHERE id >= ? ORDER BY id")) {
      ps.setInt(1, 2);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rows(rs)).containsExactly("prod:2:bob", "dev:3:carol");
      }
      ps.setInt(1, 3);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rows(rs)).containsExactly("dev:3:carol");
      }
    }
  }

  @Test
  void aDirectiveWithAnEmptyReplacementDeletesTheQualifier() throws Exception {
    try (Connection conn = open("");
        Statement s = conn.createStatement();
        ResultSet rs =
            s.executeQuery(
                "-- manyfold dev: zone1_prod=\nSELECT * FROM zone1_prod.orders ORDER BY id")) {
      assertThat(rows(rs)).containsExactly("prod:1:alice", "prod:2:bob", "dev:4:dave");
    }
  }

  @Test
  void executeAndPreparedExecuteAlsoRewrite() throws Exception {
    try (Connection conn = open("");
        Statement s = conn.createStatement()) {
      assertThat(
              s.execute("-- manyfold dev: zone1_prod=zone1_dev2\nSELECT * FROM zone1_prod.orders"))
          .isTrue();
      assertThat(rows(s.getResultSet())).hasSize(3);
    }
    try (Connection conn = open("");
        PreparedStatement ps =
            conn.prepareStatement(
                "-- manyfold dev: zone1_prod=\nSELECT * FROM zone1_prod.orders")) {
      assertThat(ps.execute()).isTrue();
      assertThat(rows(ps.getResultSet())).hasSize(3);
    }
  }

  @Test
  void writesWithADirectiveAreSubstitutedPerBackend() throws Exception {
    try (Connection conn = open("readOnly=false;");
        Statement s = conn.createStatement()) {
      assertThat(
              s.executeUpdate(
                  "-- manyfold dev: zone1_prod=zone1_dev2\n"
                      + "INSERT INTO zone1_prod.orders VALUES (9, 'zed')"))
          .isEqualTo(2);
      s.addBatch(
          "-- manyfold dev: zone1_prod=zone1_dev2\nDELETE FROM zone1_prod.orders WHERE id = 9");
      assertThat(s.executeBatch()).containsExactly(2);
      assertThat(
              s.executeLargeUpdate(
                  "-- manyfold dev: zone1_prod=zone1_dev2\n"
                      + "UPDATE zone1_prod.orders SET customer = 'x' WHERE id = 1"))
          .isEqualTo(1L);
    }
  }

  @Test
  void aDirectiveNamingAnUnknownBackendFailsBeforeAnyBackendIsCalled() throws Exception {
    try (MockedPair pair = new MockedPair();
        Connection conn = pair.open();
        Statement s = conn.createStatement()) {
      assertThatThrownBy(() -> s.executeQuery("-- manyfold staging: a=b\nSELECT * FROM a.t"))
          .isInstanceOf(ManyfoldException.class)
          .hasMessageContaining("'manyfold staging: a=b'")
          .hasMessageContaining("unknown backend 'staging'")
          .extracting(e -> ((SQLException) e).getSQLState())
          .isEqualTo("42000");
      assertThatThrownBy(() -> s.execute("/* manyfold a: x */ SELECT 1"))
          .isInstanceOf(ManyfoldException.class);
      assertThatThrownBy(() -> conn.prepareStatement("-- manyfold b: x=y, X=z\nSELECT 1"))
          .isInstanceOf(ManyfoldException.class);
      verifyNoInteractions(pair.statementA, pair.statementB);
    }
  }

  @Test
  void theReadOnlyGuardStillRefusesAWriteBehindADirective() throws Exception {
    try (Connection conn = open("");
        Statement s = conn.createStatement()) {
      assertThatThrownBy(() -> s.executeQuery("-- manyfold dev: a=b\nDELETE FROM a.t"))
          .isInstanceOf(ManyfoldException.class)
          .hasMessageStartingWith("Refused in read-only mode")
          .extracting(e -> ((SQLException) e).getSQLState())
          .isEqualTo(ManyfoldException.STATE_READ_ONLY);
      assertThatThrownBy(() -> s.executeUpdate("-- manyfold dev: a=b\nDELETE FROM a.t"))
          .isInstanceOf(ManyfoldException.class)
          .hasMessageStartingWith("Refused in read-only mode");
      assertThatThrownBy(() -> conn.prepareStatement("-- manyfold dev: a=b\nDELETE FROM a.t"))
          .isInstanceOf(ManyfoldException.class)
          .hasMessageStartingWith("Refused in read-only mode");
      assertThatThrownBy(() -> s.addBatch("/* manyfold dev: a=b */ INSERT INTO a.t VALUES (1)"))
          .isInstanceOf(ManyfoldException.class)
          .hasMessageStartingWith("Refused in read-only mode");
    }
    try (Connection p = DriverManager.getConnection(prodUrl);
        Connection d = DriverManager.getConnection(devUrl);
        Statement ps = p.createStatement();
        Statement ds = d.createStatement()) {
      ResultSet prod = ps.executeQuery("SELECT count(*) FROM zone1_prod.orders");
      prod.next();
      ResultSet dev = ds.executeQuery("SELECT count(*) FROM zone1_dev2.orders");
      dev.next();
      assertThat(prod.getInt(1)).isEqualTo(2);
      assertThat(dev.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  void aBackendErrorShowsTheRewrittenStatementThatBackendWasSent() throws Exception {
    try (Connection conn = open("");
        Statement s = conn.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.executeQuery(
                      lines(
                          "-- manyfold prod: zone1_prod=zone1_prod",
                          "-- manyfold dev: zone1_prod=zone1_missing",
                          "SELECT * FROM zone1_prod.orders")))
          .isInstanceOf(SQLException.class)
          .hasMessageStartingWith("Backend 'dev' failed: ")
          .hasMessageContaining("; sent: SELECT * FROM zone1_missing.orders");
    }
  }

  @Test
  void aBackendWithoutASubstitutionGetsNoSentSuffixEvenWhenAnotherBackendHasOne() throws Exception {
    try (Connection conn = open("");
        Statement s = conn.createStatement()) {
      // Only dev has a substitution, so only dev's failure shows what it was sent.
      assertThatThrownBy(
              () ->
                  s.executeQuery(
                      lines(
                          "-- manyfold dev: zone1_prod=zone1_missing",
                          "SELECT * FROM zone1_prod.nosuch")))
          .isInstanceOf(SQLException.class)
          .hasMessageStartingWith("Backend 'prod' failed: ")
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("sent:"))
          .satisfies(
              e ->
                  assertThat(((SQLException) e).getNextException().getMessage())
                      .startsWith("Backend 'dev' failed: ")
                      .contains("; sent: SELECT * FROM zone1_missing.nosuch"));
    }
  }

  @Test
  void aFailureOfAPreparedStatementNamesTheRewrittenText() throws Exception {
    try (Connection conn = open("")) {
      assertThatThrownBy(
              () ->
                  conn.prepareStatement(
                      "-- manyfold dev: zone1_prod=zone1_missing\n"
                          + "SELECT * FROM zone1_prod.orders WHERE id = ?"))
          .isInstanceOf(SQLException.class)
          .hasMessageStartingWith("Backend 'dev' failed: ")
          .hasMessageContaining("; sent: SELECT * FROM zone1_missing.orders WHERE id = ?");
    }
  }

  @Test
  void aStatementWithoutADirectiveHasNoSentSuffixInItsErrors() throws Exception {
    try (Connection conn = open("");
        Statement s = conn.createStatement()) {
      assertThatThrownBy(() -> s.executeQuery("SELECT * FROM nosuch.orders"))
          .isInstanceOf(SQLException.class)
          .hasMessageStartingWith("Backend 'prod' failed: ")
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("sent:"))
          .satisfies(
              e ->
                  assertThat(((SQLException) e).getNextException().getMessage())
                      .startsWith("Backend 'dev' failed: ")
                      .doesNotContain("sent:"));
    }
  }

  @Test
  void aCommentThatMerelyMentionsManyfoldAfterTheFirstTokenIsForwardedAsIs() throws Exception {
    try (Connection conn = open("");
        Statement s = conn.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT id, customer FROM zone1_prod.orders -- manyfold nosuch: a=b\n"
                    + "WHERE 1 = 0")) {
      // prod only: dev has no zone1_prod schema, so this must have failed there.
      assertThat(rs.next()).isFalse();
    } catch (SQLException e) {
      assertThat(e.getMessage()).startsWith("Backend 'dev' failed: ").doesNotContain("sent:");
    }
  }
}
