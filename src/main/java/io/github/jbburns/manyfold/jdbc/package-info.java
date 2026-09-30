/**
 * Public API of the manyfold pass-through JDBC driver.
 *
 * <p>The only supported entry point is the driver class, reached through {@code
 * java.sql.DriverManager} or instantiated directly by a SQL client. Everything under {@code
 * io.github.jbburns.manyfold.jdbc.internal} may change without notice.
 */
@NullMarked
package io.github.jbburns.manyfold.jdbc;

import org.jspecify.annotations.NullMarked;
