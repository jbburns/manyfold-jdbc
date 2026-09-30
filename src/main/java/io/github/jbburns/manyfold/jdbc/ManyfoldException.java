package io.github.jbburns.manyfold.jdbc;

import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/**
 * The exception type raised by the manyfold layer itself, as opposed to exceptions that a backend
 * driver raised and that are passed through unchanged.
 *
 * <p>When a backend fails, the message names the backend's logical source and the vendor exception
 * is attached as the cause. The SQL state and vendor code of that cause are copied so callers that
 * switch on them keep working.
 */
public class ManyfoldException extends SQLException {

  private static final long serialVersionUID = 1L;

  /** SQL state for a URL that cannot be parsed or a backend that cannot be reached. */
  public static final String STATE_CONNECTION_FAILURE = "08001";

  /** SQL state for a statement refused because the connection is read-only. */
  public static final String STATE_READ_ONLY = "25006";

  /** SQL state for a feature the merged result set does not support. */
  public static final String STATE_FEATURE_NOT_SUPPORTED = "0A000";

  /**
   * Creates an exception.
   *
   * @param message the message
   * @param sqlState the SQL state, or null
   * @param cause the cause, or null
   */
  public ManyfoldException(String message, @Nullable String sqlState, @Nullable Throwable cause) {
    super(message, sqlState, cause instanceof SQLException e ? e.getErrorCode() : 0, cause);
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
   * @param sourceName logical name of the backend
   * @param cause what the backend threw
   * @return the wrapped exception
   */
  public static ManyfoldException backendFailed(String sourceName, Throwable cause) {
    String state = cause instanceof SQLException e ? e.getSQLState() : null;
    return new ManyfoldException(
        "Backend '" + sourceName + "' failed: " + cause.getMessage(), state, cause);
  }
}
