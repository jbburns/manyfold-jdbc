package io.github.jbburns.manyfold.jdbc.internal.url;

import io.github.jbburns.manyfold.jdbc.Manyfold;
import io.github.jbburns.manyfold.jdbc.ManyfoldException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A parsed manyfold URL.
 *
 * <pre>
 * jdbc:manyfold:[option=value;...][name=]jdbc:vendor:... || [name=]jdbc:vendor:... [|| ...]
 * </pre>
 *
 * <p>Backends are separated by {@value Manyfold#BACKEND_DELIMITER}, which never appears inside a
 * JDBC URL. A backend may be prefixed with {@code name=}. Driver options may precede the first
 * backend, each terminated by {@code ;}. An option is distinguished from a named backend by its
 * value: a value starting with {@code jdbc:} is a backend URL, anything else is an option.
 *
 * <p>Options may also be supplied as connection properties prefixed with {@code manyfold.}; the URL
 * takes precedence. Per-backend properties are {@code manyfold.<name>.user}, {@code
 * manyfold.<name>.password} and {@code manyfold.<name>.driver}.
 */
public final class ManyfoldUrl {

  /** Prefix of every property the driver itself consumes. */
  public static final String PROPERTY_PREFIX = "manyfold.";

  private static final String JDBC = "jdbc:";
  private static final Set<String> KNOWN_OPTIONS =
      Set.of(lower(Options.SOURCE_COLUMN), lower(Options.READ_ONLY));

  private final String url;
  private final Options options;
  private final List<BackendSpec> backends;

  private ManyfoldUrl(String url, Options options, List<BackendSpec> backends) {
    this.url = url;
    this.options = options;
    this.backends = List.copyOf(backends);
  }

  /**
   * Parses a manyfold URL.
   *
   * @param url the full URL including the {@code jdbc:manyfold:} prefix
   * @param properties connection properties, which may carry {@code manyfold.*} options
   * @return the parsed URL
   * @throws ManyfoldException if the URL is null or malformed
   */
  public static ManyfoldUrl parse(@Nullable String url, Properties properties)
      throws ManyfoldException {
    if (url == null || !accepts(url)) {
      throw malformed(url, "it does not start with " + Manyfold.URL_PREFIX);
    }
    String body = url.substring(Manyfold.URL_PREFIX.length());
    if (body.isBlank()) {
      throw malformed(url, "no backend URLs follow the prefix");
    }

    Map<String, String> optionValues = new LinkedHashMap<>();
    for (String key : properties.stringPropertyNames()) {
      if (key.regionMatches(true, 0, PROPERTY_PREFIX, 0, PROPERTY_PREFIX.length())) {
        String option = key.substring(PROPERTY_PREFIX.length());
        if (KNOWN_OPTIONS.contains(lower(option))) {
          optionValues.put(lower(option), properties.getProperty(key));
        }
      }
    }

    List<BackendSpec> backends = new ArrayList<>();
    String[] segments = body.split(java.util.regex.Pattern.quote(Manyfold.BACKEND_DELIMITER), -1);
    for (int i = 0; i < segments.length; i++) {
      String segment = segments[i].trim();
      if (segment.isEmpty()) {
        throw malformed(url, "backend " + (i + 1) + " is empty");
      }
      backends.add(parseSegment(url, segment, i == 0 ? optionValues : null));
    }

    Set<String> seen = new java.util.HashSet<>();
    for (BackendSpec backend : backends) {
      if (!seen.add(lower(backend.name()))) {
        throw malformed(url, "the name '" + backend.name() + "' is used more than once");
      }
    }
    return new ManyfoldUrl(url, toOptions(url, optionValues), backends);
  }

  /**
   * Whether a URL is a manyfold URL at all, judged by its prefix.
   *
   * @param url any URL, or null
   * @return true if the prefix matches, ignoring case
   */
  public static boolean accepts(@Nullable String url) {
    return url != null
        && url.regionMatches(true, 0, Manyfold.URL_PREFIX, 0, Manyfold.URL_PREFIX.length());
  }

  private static BackendSpec parseSegment(
      String url, String segment, @Nullable Map<String, String> optionSink)
      throws ManyfoldException {
    String rest = segment;
    while (true) {
      if (rest.regionMatches(true, 0, JDBC, 0, JDBC.length())) {
        return new BackendSpec(BackendSpec.defaultName(rest), rest);
      }
      int eq = rest.indexOf('=');
      if (eq <= 0) {
        throw malformed(url, "'" + Redact.url(rest) + "' is neither a backend URL nor an option");
      }
      String key = rest.substring(0, eq).trim();
      String value = rest.substring(eq + 1).trim();
      if (value.regionMatches(true, 0, JDBC, 0, JDBC.length())) {
        if (key.isEmpty()) {
          throw malformed(url, "a backend name before '=' is empty");
        }
        return new BackendSpec(key, value);
      }
      if (optionSink == null) {
        throw malformed(
            url, "option '" + key + "' must come before the first backend, not after a delimiter");
      }
      if (!KNOWN_OPTIONS.contains(lower(key))) {
        throw malformed(url, "unknown option '" + key + "'");
      }
      int semi = value.indexOf(';');
      if (semi < 0) {
        throw malformed(url, "option '" + key + "' must be followed by ';' and a backend URL");
      }
      optionSink.put(lower(key), value.substring(0, semi).trim());
      rest = value.substring(semi + 1).trim();
    }
  }

  private static Options toOptions(String url, Map<String, String> values)
      throws ManyfoldException {
    Options result = Options.DEFAULTS;
    String column = values.get(lower(Options.SOURCE_COLUMN));
    if (column != null) {
      if (column.isBlank()) {
        throw malformed(url, "option '" + Options.SOURCE_COLUMN + "' is empty");
      }
      result = new Options(column, result.readOnly());
    }
    String readOnly = values.get(lower(Options.READ_ONLY));
    if (readOnly != null) {
      switch (lower(readOnly)) {
        case "true":
          result = new Options(result.sourceColumn(), true);
          break;
        case "false":
          result = new Options(result.sourceColumn(), false);
          break;
        default:
          throw malformed(
              url,
              "option '" + Options.READ_ONLY + "' must be true or false, not '" + readOnly + "'");
      }
    }
    return result;
  }

  private static ManyfoldException malformed(@Nullable String url, String reason) {
    return new ManyfoldException(
        "Invalid manyfold URL '" + Redact.url(url) + "': " + reason,
        ManyfoldException.STATE_CONNECTION_FAILURE);
  }

  private static String lower(String s) {
    return s.toLowerCase(Locale.ROOT);
  }

  /** The original URL with credentials removed. */
  public String redacted() {
    return Redact.url(url);
  }

  /** The driver options in effect. */
  public Options options() {
    return options;
  }

  /** The backends in URL order. The first is the primary. */
  public List<BackendSpec> backends() {
    return backends;
  }

  /**
   * Builds the properties handed to one backend's driver.
   *
   * <p>Every property not prefixed with {@code manyfold.} is passed through. The backend's own
   * {@code manyfold.<name>.user} and {@code manyfold.<name>.password} replace {@code user} and
   * {@code password} when present.
   *
   * @param backend the backend
   * @param properties the properties given to the manyfold connection
   * @return a new properties object for the vendor driver
   */
  public Properties backendProperties(BackendSpec backend, Properties properties) {
    Properties result = new Properties();
    for (String key : properties.stringPropertyNames()) {
      if (!key.regionMatches(true, 0, PROPERTY_PREFIX, 0, PROPERTY_PREFIX.length())) {
        result.setProperty(key, properties.getProperty(key));
      }
    }
    for (String field : new String[] {"user", "password"}) {
      String override = backendProperty(backend, properties, field);
      if (override != null) {
        result.setProperty(field, override);
      }
    }
    return result;
  }

  /**
   * Reads {@code manyfold.<name>.<field>} case-insensitively on the name.
   *
   * @param backend the backend
   * @param properties the connection properties
   * @param field the field, such as {@code user} or {@code driver}
   * @return the value or null
   */
  public static @Nullable String backendProperty(
      BackendSpec backend, Properties properties, String field) {
    String wanted = lower(PROPERTY_PREFIX + backend.name() + "." + field);
    for (String key : properties.stringPropertyNames()) {
      if (lower(key).equals(wanted)) {
        return properties.getProperty(key);
      }
    }
    return null;
  }

  @Override
  public String toString() {
    return redacted();
  }
}
