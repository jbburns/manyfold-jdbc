package io.github.jbburns.manyfold.jdbc.internal.url;

import java.util.Locale;
import java.util.Objects;

/** One backend named in a manyfold URL: its logical name and the real JDBC URL. */
public final class BackendSpec {

  private final String name;
  private final String url;

  /**
   * Creates a backend spec.
   *
   * @param name logical name shown in the source column
   * @param url the real JDBC URL handed to the vendor driver
   */
  public BackendSpec(String name, String url) {
    this.name = name;
    this.url = url;
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
   * @return the URL handed to the vendor driver
   */
  public String url() {
    return url;
  }

  /**
   * Derives a logical name from a JDBC URL when the user gave none.
   *
   * <p>The {@code jdbc:} prefix, any credentials, and everything from the first {@code ?} or {@code
   * ;} onward are removed, so {@code jdbc:postgresql://u:p@host:5432/app?ssl=true} becomes {@code
   * postgresql://host:5432/app}.
   *
   * @param url the real JDBC URL
   * @return a name that identifies the database without exposing secrets
   */
  public static String defaultName(String url) {
    String name = Redact.url(url);
    if (name.regionMatches(true, 0, "jdbc:", 0, 5)) {
      name = name.substring(5);
    }
    int cut = name.length();
    for (char c : new char[] {'?', ';'}) {
      int i = name.indexOf(c);
      if (i >= 0 && i < cut) {
        cut = i;
      }
    }
    name = name.substring(0, cut).trim();
    return name.isEmpty() ? Redact.url(url).toLowerCase(Locale.ROOT) : name;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BackendSpec)) {
      return false;
    }
    BackendSpec other = (BackendSpec) o;
    return name.equals(other.name) && url.equals(other.url);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, url);
  }

  /** Shows the URL with credentials removed, so the spec can be logged safely. */
  @Override
  public String toString() {
    return "BackendSpec[name=" + name + ", url=" + Redact.url(url) + "]";
  }
}
