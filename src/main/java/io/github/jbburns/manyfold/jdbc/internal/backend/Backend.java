package io.github.jbburns.manyfold.jdbc.internal.backend;

import java.sql.Connection;

/**
 * One open backend.
 *
 * @param name logical name shown in the source column
 * @param url the real JDBC URL, which may contain credentials and must not be logged
 * @param connection the vendor connection
 */
public record Backend(String name, String url, Connection connection) {}
