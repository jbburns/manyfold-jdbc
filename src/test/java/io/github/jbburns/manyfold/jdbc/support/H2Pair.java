package io.github.jbburns.manyfold.jdbc.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Two independent in-memory H2 databases, {@code prod} and {@code dev}, seeded with an {@code
 * orders} table that differs between them so tests can tell rows apart.
 */
public final class H2Pair implements AutoCloseable {

  public final String prodUrl;
  public final String devUrl;

  public H2Pair() throws SQLException {
    String id = UUID.randomUUID().toString().replace("-", "");
    prodUrl = "jdbc:h2:mem:prod_" + id + ";DB_CLOSE_DELAY=-1";
    devUrl = "jdbc:h2:mem:dev_" + id + ";DB_CLOSE_DELAY=-1";
    seed(prodUrl, "(1, 'alice', 10.50), (2, 'bob', 20.00)");
    seed(devUrl, "(3, 'carol', 30.25)");
  }

  private static void seed(String url, String rows) throws SQLException {
    try (Connection c = DriverManager.getConnection(url);
        Statement s = c.createStatement()) {
      s.execute(
          "CREATE TABLE orders (id INT PRIMARY KEY, customer VARCHAR(32), amount DECIMAL(10,2))");
      s.execute("INSERT INTO orders VALUES " + rows);
      s.execute("CREATE TABLE only_here (id INT)");
    }
  }

  /** A manyfold URL naming prod then dev. */
  public String manyfoldUrl() {
    return "jdbc:manyfold:prod=" + prodUrl + " || dev=" + devUrl;
  }

  /** A manyfold URL with leading options, naming prod then dev. */
  public String manyfoldUrl(String options) {
    return "jdbc:manyfold:" + options + ";prod=" + prodUrl + " || dev=" + devUrl;
  }

  public Connection prod() throws SQLException {
    return DriverManager.getConnection(prodUrl);
  }

  public Connection dev() throws SQLException {
    return DriverManager.getConnection(devUrl);
  }

  /** Runs a scalar query directly against one backend, bypassing manyfold. */
  public static long count(Connection direct, String sql) throws SQLException {
    try (Statement s = direct.createStatement();
        var rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Override
  public void close() throws SQLException {
    for (String url : new String[] {prodUrl, devUrl}) {
      try (Connection c = DriverManager.getConnection(url);
          Statement s = c.createStatement()) {
        s.execute("SHUTDOWN");
      }
    }
  }
}
