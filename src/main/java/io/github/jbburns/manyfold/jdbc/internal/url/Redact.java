package io.github.jbburns.manyfold.jdbc.internal.url;

import java.util.regex.Pattern;

/** Removes credentials from JDBC URLs before they appear in names, messages or logs. */
public final class Redact {

  /** {@code scheme://user:secret@host} becomes {@code scheme://host}. */
  private static final Pattern USER_INFO = Pattern.compile("(//)[^/@?;]*@");

  /** {@code password=secret} and {@code pwd=secret} in any parameter list. */
  private static final Pattern PASSWORD_PARAM =
      Pattern.compile("(?i)([?;&,](?:password|pwd|passwd|pass)\\s*=)[^;&|\\s]*");

  private Redact() {}

  /**
   * Returns the URL with user info and password parameters replaced.
   *
   * @param url any JDBC URL
   * @return the URL with secrets removed
   */
  public static String url(String url) {
    String result = USER_INFO.matcher(url).replaceAll("$1");
    return PASSWORD_PARAM.matcher(result).replaceAll("$1***");
  }
}
