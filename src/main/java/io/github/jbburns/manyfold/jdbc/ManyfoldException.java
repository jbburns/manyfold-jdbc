package io.github.jbburns.manyfold.jdbc;

import io.github.jbburns.manyfold.jdbc.internal.url.Redact;
import java.sql.BatchUpdateException;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The exception type raised by the manyfold layer itself, as opposed to exceptions that a backend
 * driver raised and that are passed through unchanged.
 *
 * <p>When a backend fails, the message names the backend's logical source and the vendor exception
 * is attached as the cause. The SQL state and vendor code of that cause are copied so callers that
 * switch on them keep working, and standard {@code java.sql} exception subtypes are preserved; see
 * {@link #backendFailed}.
 */
public class ManyfoldException extends SQLException {

  private static final long serialVersionUID = 1L;

  /** SQL state for a URL that cannot be parsed or a backend that cannot be reached. */
  public static final String STATE_CONNECTION_FAILURE = "08001";

  /** SQL state for an operation on a connection that has been closed. */
  public static final String STATE_CONNECTION_CLOSED = "08003";

  /** SQL state for a statement refused because the connection is read-only. */
  public static final String STATE_READ_ONLY = "25006";

  /** SQL state for a schema directive or substitution that is refused. */
  public static final String STATE_SYNTAX_ERROR = "42000";

  /** SQL state for a feature the merged result set does not support. */
  public static final String STATE_FEATURE_NOT_SUPPORTED = "0A000";

  /** Builds a new exception of one standard subtype from the vendor's details. */
  @FunctionalInterface
  private interface Factory {
    SQLException create(String message, @Nullable String state, int code, Throwable cause);
  }

  /** Pairs a standard {@code java.sql} exception class with the factory that rebuilds it. */
  private static final class Rebuilder {
    private final Class<? extends SQLException> type;
    private final Factory factory;

    Rebuilder(Class<? extends SQLException> type, Factory factory) {
      this.type = type;
      this.factory = factory;
    }

    Class<? extends SQLException> type() {
      return type;
    }

    Factory factory() {
      return factory;
    }
  }

  /**
   * The standard subtypes that {@link #backendFailed} preserves, in match order. None of these
   * classes extends another one in the list, so the order does not affect the outcome.
   */
  private static final List<Rebuilder> REBUILDERS =
      List.of(
          new Rebuilder(
              SQLFeatureNotSupportedException.class, SQLFeatureNotSupportedException::new),
          new Rebuilder(SQLTimeoutException.class, SQLTimeoutException::new),
          new Rebuilder(
              SQLIntegrityConstraintViolationException.class,
              SQLIntegrityConstraintViolationException::new),
          new Rebuilder(SQLSyntaxErrorException.class, SQLSyntaxErrorException::new),
          new Rebuilder(SQLDataException.class, SQLDataException::new),
          new Rebuilder(
              SQLTransientConnectionException.class, SQLTransientConnectionException::new),
          new Rebuilder(
              SQLNonTransientConnectionException.class, SQLNonTransientConnectionException::new),
          new Rebuilder(
              SQLInvalidAuthorizationSpecException.class,
              SQLInvalidAuthorizationSpecException::new),
          new Rebuilder(
              SQLTransactionRollbackException.class, SQLTransactionRollbackException::new),
          new Rebuilder(SQLRecoverableException.class, SQLRecoverableException::new));

  /**
   * Creates an exception.
   *
   * @param message the message
   * @param sqlState the SQL state, or null
   * @param cause the cause, or null
   */
  public ManyfoldException(String message, @Nullable String sqlState, @Nullable Throwable cause) {
    super(
        message,
        sqlState,
        cause instanceof SQLException ? ((SQLException) cause).getErrorCode() : 0,
        cause);
  }

  /**
   * Creates an exception with no cause.
   *
   * @param message the message
   * @param sqlState the SQL state
   */
  public ManyfoldException(String message, String sqlState) {
    this(message, sqlState, null);
  }

  /**
   * Wraps a failure from one backend so the message names the source.
   *
   * <p>When the cause is one of the standard {@code java.sql} exception subclasses, the result is a
   * new instance of the same subclass, so callers and connection pools that catch {@link
   * java.sql.SQLFeatureNotSupportedException}, {@link java.sql.BatchUpdateException} and friends
   * keep working. The SQL state, vendor code, cause and the vendor's own {@code getNextException}
   * chain are carried over. Any other cause yields a {@link ManyfoldException}.
   *
   * @param sourceName logical name of the backend
   * @param cause what the backend threw
   * @return the wrapped exception
   */
  public static SQLException backendFailed(String sourceName, Throwable cause) {
    return backendFailed(sourceName, cause, null);
  }

  /**
   * Wraps a failure from one backend, and shows the statement that backend was sent when it is not
   * the text the caller supplied. See {@link #backendFailed(String, Throwable)}.
   *
   * @param sourceName logical name of the backend
   * @param cause what the backend threw
   * @param sentSql the statement text that was sent to this backend if it differs from the
   *     caller's, appended to the message as {@code "; sent: <text>"}, or null
   * @return the wrapped exception
   */
  public static SQLException backendFailed(
      String sourceName, Throwable cause, @Nullable String sentSql) {
    // Some drivers echo the connection string, password included, in a connect error.
    String detail =
        cause.getMessage() != null ? Redact.url(cause.getMessage()) : cause.getClass().getName();
    String message = "Backend '" + sourceName + "' failed: " + detail;
    if (sentSql != null) {
      message += "; sent: " + Redact.url(sentSql);
    }
    if (!(cause instanceof SQLException)) {
      return new ManyfoldException(message, null, cause);
    }
    SQLException vendor = (SQLException) cause;
    String state = vendor.getSQLState();
    int code = vendor.getErrorCode();
    SQLException wrapped = null;
    if (vendor instanceof BatchUpdateException) {
      BatchUpdateException batch = (BatchUpdateException) vendor;
      wrapped = new BatchUpdateException(message, state, code, batch.getLargeUpdateCounts(), cause);
    } else {
      for (Rebuilder entry : REBUILDERS) {
        if (entry.type().isInstance(vendor)) {
          wrapped = entry.factory().create(message, state, code, cause);
          break;
        }
      }
    }
    if (wrapped == null) {
      wrapped = new ManyfoldException(message, state, cause);
    }
    copyChain(vendor, wrapped);
    return wrapped;
  }

  /**
   * Appends copies of the vendor's {@code getNextException} chain, so the vendor's own exceptions
   * are never mutated when further failures are chained after the wrapper.
   */
  private static void copyChain(SQLException vendor, SQLException wrapped) {
    Set<SQLException> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    seen.add(vendor);
    SQLException tail = wrapped;
    for (SQLException next = vendor.getNextException();
        next != null && seen.add(next);
        next = next.getNextException()) {
      SQLException copy =
          new SQLException(next.getMessage(), next.getSQLState(), next.getErrorCode(), next);
      tail.setNextException(copy);
      tail = copy;
    }
  }
}
