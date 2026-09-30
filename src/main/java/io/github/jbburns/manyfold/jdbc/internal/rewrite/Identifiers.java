package io.github.jbburns.manyfold.jdbc.internal.rewrite;

import java.util.Locale;

/** What counts as an identifier in a directive, and how two identifiers are compared. */
final class Identifiers {

  private Identifiers() {}

  /** A letter, digit, underscore, {@code $} or {@code #}: what may continue a plain identifier. */
  static boolean isIdentChar(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || c == '_'
        || c == '$'
        || c == '#';
  }

  /** {@code [A-Za-z_][A-Za-z0-9_$#]*}. */
  static boolean isPlain(String text) {
    if (text.isEmpty()) {
      return false;
    }
    char first = text.charAt(0);
    if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z') || first == '_')) {
      return false;
    }
    for (int i = 1; i < text.length(); i++) {
      if (!isIdentChar(text.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /** A non-empty double-quoted identifier in which every inner quote is doubled. */
  static boolean isQuoted(String text) {
    int n = text.length();
    if (n < 3 || text.charAt(0) != '"' || text.charAt(n - 1) != '"') {
      return false;
    }
    int i = 1;
    while (i < n - 1) {
      if (text.charAt(i) == '"') {
        if (i + 1 >= n - 1 || text.charAt(i + 1) != '"') {
          return false;
        }
        i += 2;
      } else {
        i++;
      }
    }
    return true;
  }

  static boolean isValid(String text) {
    return isPlain(text) || isQuoted(text);
  }

  /**
   * The identity of an identifier: unquoted names are equal ignoring case, quoted names only when
   * their text is the same, and a quoted name never equals an unquoted one.
   */
  static String key(String identifier) {
    if (isPlain(identifier)) {
      return "U:" + identifier.toUpperCase(Locale.ROOT);
    }
    if (isQuoted(identifier)) {
      return "Q:" + identifier;
    }
    throw new IllegalArgumentException("not an identifier: " + identifier);
  }

  /** The key of a plain identifier read from a statement. */
  static String plainKey(String identifier) {
    return "U:" + identifier.toUpperCase(Locale.ROOT);
  }

  /** The key of a double-quoted identifier read from a statement. */
  static String quotedKey(String identifier) {
    return "Q:" + identifier;
  }
}
