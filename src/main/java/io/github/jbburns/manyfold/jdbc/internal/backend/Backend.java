package io.github.jbburns.manyfold.jdbc.internal.backend;

import io.github.jbburns.manyfold.jdbc.internal.url.Redact;
import java.sql.Connection;
import java.sql.SQLWarning;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One open backend. */
public final class Backend {

  private final String name;
  private final String url;
  private final Connection connection;
  private final @Nullable SQLWarning warning;

  /**
   * Creates a backend.
   *
   * @param name logical name shown in the source column
   * @param url the real JDBC URL, which may contain credentials and must not be logged
   * @param connection the vendor connection
   * @param warning something the connector noticed while opening the connection, such as a refused
   *     read-only hint, or null
   */
  public Backend(String name, String url, Connection connection, @Nullable SQLWarning warning) {
    this.name = name;
    this.url = url;
    this.connection = connection;
    this.warning = warning;
  }

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

  /**
   * The logical name.
   *
   * @return the name shown in the source column
   */
  public String name() {
    return name;
  }

  /**
   * The real JDBC URL.
   *
   * @return the URL, which may contain credentials and must not be logged
   */
  public String url() {
    return url;
  }

  /**
   * The vendor connection.
   *
   * @return the connection
   */
  public Connection connection() {
    return connection;
  }

  /**
   * A warning noted while opening the connection.
   *
   * @return the warning, or null
   */
  public @Nullable SQLWarning warning() {
    return warning;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Backend)) {
      return false;
    }
    Backend other = (Backend) o;
    return name.equals(other.name)
        && url.equals(other.url)
        && connection.equals(other.connection)
        && Objects.equals(warning, other.warning);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, url, connection, warning);
  }

  /** Shows the URL with credentials removed, so the backend can be logged safely. */
  @Override
  public String toString() {
    return "Backend[name=" + name + ", url=" + Redact.url(url) + ", connection=" + connection + "]";
  }
}
