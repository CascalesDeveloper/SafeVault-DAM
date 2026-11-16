
package com.safevault.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Gestiona exclusivamente la conexión con MariaDB.
 *
 * MariaDB es un sistema gestor de bases de datos independiente
 * que funciona como servidor. En este proyecto utilizamos
 * el servidor incluido en XAMPP.
 */
public class MariaDBManager {

    // Dirección del servidor MariaDB de XAMPP.
    private static final String URL =
            "jdbc:mariadb://localhost:3306/safevault_mysql";

    // Credenciales habituales de la instalación local de XAMPP.
    private static final String USER = "root";
    private static final String PASSWORD = "";

    /**
     * Obtiene una conexión con la base de datos MariaDB.
     */
    public static Connection getConnection() throws SQLException {

        return DriverManager.getConnection(
                URL,
                USER,
                PASSWORD
        );
    }

    /**
     * Crea la tabla credentials si todavía no existe.
     *
     * La estructura es equivalente a la utilizada en SQLite.
     */
    public static void initializeDatabase() throws SQLException {

        String sql = """
                CREATE TABLE IF NOT EXISTS credentials (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    title VARCHAR(150) NOT NULL,
                    username VARCHAR(150) NOT NULL,
                    password TEXT NOT NULL,
                    category VARCHAR(100) NOT NULL
                ) ENGINE=InnoDB
                  DEFAULT CHARSET=utf8mb4
                """;

        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute(sql);

            System.out.println(
                    "SafeVault: base de datos MariaDB inicializada."
            );

            System.out.println(
                    "Servidor: localhost:3306"
            );

            System.out.println(
                    "Base de datos: safevault_mysql"
            );
        }
    }

    /**
     * Comprueba si existe conexión con MariaDB.
     */
    public static boolean testConnection() {

        try (Connection connection = getConnection()) {

            return connection != null && !connection.isClosed();

        } catch (SQLException e) {

            System.err.println(
                    "Error al conectar con MariaDB: " + e.getMessage()
            );

            return false;
        }
    }
}

