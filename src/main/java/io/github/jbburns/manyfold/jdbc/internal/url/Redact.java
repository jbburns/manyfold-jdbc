package io.github.jbburns.manyfold.jdbc.internal.url;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Removes credentials from JDBC URLs before they appear in names, messages or logs. */
public final class Redact {

  /** {@code jdbc:oracle:thin:scott/tiger@//host} becomes {@code jdbc:oracle:thin:@//host}. */
  private static final Pattern ORACLE_USER_INFO =
      Pattern.compile("(?i)(jdbc:oracle:[a-z]+:)[^@/:\\s|]+/[^\\s|]*@");

  /**
   * {@code scheme://user:secret@host} becomes {@code scheme://host}. User info runs to the last
   * {@code @} before the next {@code /}, so a password may contain {@code @}, {@code ?} and {@code
   * ;}.
   */
  private static final Pattern USER_INFO = Pattern.compile("(//)[^/|]*@");

  /**
   * Any parameter whose name contains a secret-like word, such as {@code password}, {@code
   * sslpassword}, {@code secret}, {@code token}, {@code apikey}, {@code sslkey} or {@code
   * credentials}. The parameter may follow {@code ?}, {@code ;}, {@code &}, {@code ,} or, for DB2,
   * {@code :}. The value is either a brace-delimited group, which may contain {@code ;}, or runs to
   * the next {@code ;}, {@code &}, {@code |} or the end of the string, and so may contain spaces.
   * Whitespace just before the terminator is left alone.
   */
  private static final Pattern SECRET_PARAM =
      Pattern.compile(
          "(?i)([?;&,:][^=?;&,:|\\s]*"
              + "(?:password|pwd|passwd|pass|secret|token|key|credential|auth)"
              + "[^=?;&,:|\\s]*\\s*=)"
              + "(?:\\{[^}]*\\}|\\{[^}]*\\z|[^;&|]*?(?=\\s*(?:[;&|]|\\z)))");

  private Redact() {}

  /**
   * Returns the URL with user info and secret parameters replaced.
   *
   * @param url any JDBC URL, or null
   * @return the URL with secrets removed, or the string {@code "null"} for null
   */
  public static String url(@Nullable String url) {
    if (url == null) {
      return "null";
    }
    String result = ORACLE_USER_INFO.matcher(url).replaceAll("$1@");
    // Parameters first, so a secret containing '@' does not swallow the host in front of it.
    result = SECRET_PARAM.matcher(result).replaceAll("$1***");
    return USER_INFO.matcher(result).replaceAll("$1");
  }
}
