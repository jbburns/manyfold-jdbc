package io.github.jbburns.manyfold.jdbc.internal.backend;

import io.github.jbburns.manyfold.jdbc.internal.url.Redact;
import java.sql.Connection;
import java.sql.SQLWarning;
import org.jspecify.annotations.Nullable;

/**
 * One open backend.
 *
 * @param name logical name shown in the source column
 * @param url the real JDBC URL, which may contain credentials and must not be logged
 * @param connection the vendor connection
 * @param warning something the connector noticed while opening the connection, such as a refused
 *     read-only hint, or null
 */
public record Backend(
    String name, String url, Connection connection, @Nullable SQLWarning warning) {

  /**
   * Creates a backend that opened without warnings.
   *
   * @param name logical name shown in the source column
   * @param url the real JDBC URL
   * @param connection the vendor connection
   */
  public Backend(String name, String url, Connection connection) {
    this(name, url, connection, null);
  }

  /** Shows the URL with credentials removed, so the record can be logged safely. */
  @Override
  public String toString() {
    return "Backend[name=" + name + ", url=" + Redact.url(url) + ", connection=" + connection + "]";
  }
}
