
package com.safevault.database;

import com.safevault.view.DashboardView.CredentialRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO encargado de gestionar las credenciales almacenadas
 * en MariaDB.
 *
 * Implementa las operaciones CRUD:
 * Create: insertar credenciales.
 * Read: consultar credenciales.
 * Update: modificar credenciales.
 * Delete: eliminar credenciales.
 *
 * Utiliza MariaDBManager para obtener las conexiones.
 */
public class MariaDBCredentialDAO {

    /**
     * Recupera todas las credenciales de MariaDB.
     */
    public List<CredentialRow> findAll() throws SQLException {

        List<CredentialRow> credentials = new ArrayList<>();

        String sql = """
                SELECT id, title, username, password, category
                FROM credentials
                ORDER BY title
                """;

        try (Connection connection =
                     MariaDBManager.getConnection();

             PreparedStatement statement =
                     connection.prepareStatement(sql);

             ResultSet result = statement.executeQuery()) {

            while (result.next()) {

                CredentialRow credential = new CredentialRow(
                        result.getInt("id"),
                        result.getString("title"),
                        result.getString("username"),
                        result.getString("password"),
                        result.getString("category")
                );

                credentials.add(credential);
            }
        }

        return credentials;
    }

    /**
     * Inserta una nueva credencial y devuelve su ID generado.
     */
    public int insert(CredentialRow credential) throws SQLException {

        String sql = """
                INSERT INTO credentials
                (title, username, password, category)
                VALUES (?, ?, ?, ?)
                """;

        try (Connection connection =
                     MariaDBManager.getConnection();

             PreparedStatement statement =
                     connection.prepareStatement(
                             sql,
                             Statement.RETURN_GENERATED_KEYS
                     )) {

            statement.setString(1, credential.getTitle());
            statement.setString(2, credential.getUsername());
            statement.setString(3, credential.getPassword());
            statement.setString(4, credential.getCategory());

            statement.executeUpdate();

            // Recuperamos el identificador generado por MariaDB.
            try (ResultSet keys = statement.getGeneratedKeys()) {

                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }

        throw new SQLException(
                "No se pudo obtener el ID generado en MariaDB."
        );
    }

    /**
     * Actualiza una credencial existente.
     */
    public boolean update(CredentialRow credential)
            throws SQLException {

        String sql = """
                UPDATE credentials
                SET title = ?,
                    username = ?,
                    password = ?,
                    category = ?
                WHERE id = ?
                """;

        try (Connection connection =
                     MariaDBManager.getConnection();

             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, credential.getTitle());
            statement.setString(2, credential.getUsername());
            statement.setString(3, credential.getPassword());
            statement.setString(4, credential.getCategory());
            statement.setInt(5, credential.getId());

            return statement.executeUpdate() > 0;
        }
    }

    /**
     * Elimina una credencial mediante su identificador.
     */
    public boolean delete(int id) throws SQLException {

        String sql = """
                DELETE FROM credentials
                WHERE id = ?
                """;

        try (Connection connection =
                     MariaDBManager.getConnection();

             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setInt(1, id);

            return statement.executeUpdate() > 0;
        }
    }
}

