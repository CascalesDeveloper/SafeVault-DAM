
package com.safevault.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Gestiona exclusivamente la conexión con SQLite.
 *
 * SQLite es un sistema gestor de bases de datos embebido.
 * No necesita un servidor independiente para funcionar.
 */
public class SQLiteManager {

    // Ruta del archivo físico de la base de datos.
    private static final Path DATABASE_PATH =
            Path.of("data", "safevault.db");

    // URL JDBC utilizada por el controlador de SQLite.
    private static final String URL =
            "jdbc:sqlite:" + DATABASE_PATH.toAbsolutePath();

    /**
     * Obtiene una conexión con la base de datos SQLite.
     *
     * Si la carpeta data no existe, la crea automáticamente.
     */
    public static Connection getConnection() throws SQLException {

        try {
            Files.createDirectories(DATABASE_PATH.getParent());

        } catch (Exception e) {
            throw new SQLException(
                    "No se pudo crear la carpeta de SQLite.", e
            );
        }

        return DriverManager.getConnection(URL);
    }

    /**
     * Inicializa la base de datos y crea la tabla credentials
     * si todavía no existe.
     */
    public static void initializeDatabase() throws SQLException {

        String sql = """
                CREATE TABLE IF NOT EXISTS credentials (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    username TEXT NOT NULL,
                    password TEXT NOT NULL,
                    category TEXT NOT NULL
                )
                """;

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute(sql);

            System.out.println(
                    "SafeVault: base de datos SQLite inicializada."
            );

            System.out.println(
                    "Ubicación: " + DATABASE_PATH.toAbsolutePath()
            );
        }
    }

    /**
     * Comprueba que se puede establecer una conexión con SQLite.
     */
    public static boolean testConnection() {

        try (Connection connection = getConnection()) {

            return connection != null && !connection.isClosed();

        } catch (SQLException e) {

            System.err.println(
                    "Error al conectar con SQLite: " + e.getMessage()
            );

            return false;
        }
    }
}

