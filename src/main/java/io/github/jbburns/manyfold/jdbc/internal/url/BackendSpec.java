package io.github.jbburns.manyfold.jdbc.internal.url;

import java.util.Locale;

/**
 * One backend named in a manyfold URL.
 *
 * @param name logical name shown in the source column
 * @param url the real JDBC URL handed to the vendor driver
 */
public record BackendSpec(String name, String url) {

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
    return name.isEmpty() ? url.toLowerCase(Locale.ROOT) : name;
  }
}
