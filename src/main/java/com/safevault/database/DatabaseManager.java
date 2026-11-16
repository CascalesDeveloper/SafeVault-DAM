
package com.safevault.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Gestor de compatibilidad de conexiones.
 *
 * Mantiene los métodos utilizados actualmente por SafeVault.
 * La conexión real con SQLite se delega en SQLiteManager.
 */
public class DatabaseManager {

    /**
     * Devuelve una conexión con SQLite.
     *
     * Se mantiene este método para que CredentialDAO
     * siga funcionando sin modificaciones.
     */
    public static Connection getConnection() throws SQLException {
        return SQLiteManager.getConnection();
    }

    /**
     * Inicializa la base de datos SQLite.
     */
    public static void initializeDatabase() throws SQLException {
        SQLiteManager.initializeDatabase();
    }

    /**
     * Comprueba la conexión con SQLite.
     */
    public static boolean testConnection() {
        return SQLiteManager.testConnection();
    }
}
