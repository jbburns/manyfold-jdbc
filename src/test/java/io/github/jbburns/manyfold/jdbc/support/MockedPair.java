package io.github.jbburns.manyfold.jdbc.support;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Two mock backends named {@code a} and {@code b}, reachable through a manyfold URL, for tests that
 * need a backend to misbehave in ways a real database will not. Each mock hands out one mock
 * statement from {@code createStatement()}.
 */
public final class MockedPair implements AutoCloseable {

  public final Connection a = mock(Connection.class);
  public final Connection b = mock(Connection.class);
  public final Statement statementA = mock(Statement.class);
  public final Statement statementB = mock(Statement.class);

  private final StubDriver driver;

  public MockedPair() throws SQLException {
    when(a.createStatement()).thenReturn(statementA);
    when(b.createStatement()).thenReturn(statementB);
    driver = StubDriver.registered().connection("a", a).connection("b", b);
  }

  /** Opens a manyfold connection over the two mocks, with the default options. */
  public Connection open() throws SQLException {
    return DriverManager.getConnection(url(""));
  }

  /** Opens a manyfold connection over the two mocks with the given leading options. */
  public Connection open(String options) throws SQLException {
    return DriverManager.getConnection(url(options));
  }

  private String url(String options) {
    return "jdbc:manyfold:"
        + (options.isEmpty() ? "" : options + ";")
        + "a="
        + driver.url("a")
        + " || b="
        + driver.url("b");
  }

  @Override
  public void close() throws SQLException {
    driver.close();
  }
}
