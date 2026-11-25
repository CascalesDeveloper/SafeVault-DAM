package com.safevault.database;

import com.safevault.security.AuthenticationManager.VaultSession;
import com.safevault.view.DashboardView.CredentialRow;

import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/*
 * NOTA SOBRE LAS IMPORTACIONES:
 * Cada import permite utilizar una clase de Java sin escribir su nombre
 * completamente cualificado en cada aparición.
 *
 * - VaultSession: representa la sesión autenticada y permite cifrar/descifrar.
 * - CredentialRow: modelo que transporta los datos de una credencial.
 * - GeneralSecurityException: errores de las operaciones criptográficas.
 * - Connection, PreparedStatement, ResultSet y Statement: API JDBC para SQLite.
 * - ArrayList y List: colección donde se reúnen las credenciales recuperadas.
 * - Comparator: criterio utilizado para ordenar las credenciales.
 * - Objects: comprobaciones defensivas frente a referencias null.
 */

/**
 * DAO (Data Access Object) de las credenciales de Vaultix.
 *
 * Esta clase es la única responsable de consultar, insertar, actualizar
 * y eliminar registros de la tabla "credentials".
 *
 * CAMBIO DE SEGURIDAD:
 * Antes, el DAO guardaba y recuperaba los campos como texto normal.
 * Ahora, los cuatro campos de cada credencial se cifran con la sesión
 * desbloqueada de Vaultix:
 *
 * - title
 * - username
 * - password
 * - category
 *
 * La clave de cifrado no se almacena aquí ni se obtiene de SQLite.
 * Se recibe mediante VaultSession, que se crea después de autenticar
 * correctamente al usuario principal.
 *
 * IMPORTANTE:
 * El Dashboard deberá construir el DAO pasando la sesión activa:
 *
 *     CredentialDAO credentialDAO = new CredentialDAO(vaultSession);
 *
 * No debe utilizarse el constructor vacío porque ya no existe: permitir
 * operaciones sin una sesión desbloqueada supondría saltarse la protección.
 */
/**
 * Clase de acceso a datos de las credenciales.
 *
 * El patrón DAO (Data Access Object) separa las operaciones de persistencia
 * del resto de la aplicación. Así, DashboardView no necesita conocer los
 * detalles de las sentencias SQL ni cómo se cifran los campos.
 *
 * RESPONSABILIDADES PRINCIPALES:
 * 1. Consultar las credenciales almacenadas.
 * 2. Insertar nuevas credenciales.
 * 3. Actualizar credenciales existentes.
 * 4. Eliminar credenciales.
 * 5. Cifrar antes de guardar y descifrar después de recuperar.
 *
 * Esta clase no dibuja controles JavaFX ni presenta mensajes al usuario.
 * Su cometido es trabajar con los datos y comunicar los errores mediante
 * excepciones para que la capa superior decida cómo mostrarlos.
 */
public class CredentialDAO {

    // -----------------------------------------------------------------
    // 1. SESIÓN DE LA BÓVEDA
    // -----------------------------------------------------------------

    /**
     * Sesión autenticada que contiene temporalmente la clave AES.
     *
     * Se declara final para impedir que el DAO cambie de sesión mientras
     * está siendo utilizado.
     */
    /*
     * Referencia a la sesión que se creó tras autenticar al usuario.
     *
     * "private" limita el acceso directo a este atributo a esta clase.
     * "final" significa que la referencia se asigna en el constructor y
     * después no puede apuntar a otra sesión distinta.
     *
     * La sesión contiene la clave derivada que permite realizar operaciones
     * criptográficas. El DAO no conoce ni almacena la contraseña maestra.
     */
    private final VaultSession vaultSession;

    // -----------------------------------------------------------------
    // 2. CONSTRUCTOR
    // -----------------------------------------------------------------

    /**
     * Crea el DAO asociado a una bóveda ya desbloqueada.
     *
     * @param vaultSession sesión obtenida mediante
     *                     AuthenticationManager.authenticate()
     *                     o AuthenticationManager.createVault().
     */
    public CredentialDAO(VaultSession vaultSession) {

        this.vaultSession = Objects.requireNonNull(
                vaultSession,
                "Es necesaria una sesión desbloqueada para acceder a la bóveda."
        );

        /*
         * Una referencia no nula no garantiza que la sesión siga abierta.
         * Puede haber sido cerrada desde otra parte de la aplicación.
         * Por eso también se comprueba su estado antes de aceptar el DAO.
         */
        if (!vaultSession.isOpen()) {
            throw new IllegalStateException(
                    "No se puede crear el DAO con una sesión bloqueada."
            );
        }
    }

    // -----------------------------------------------------------------
    // 3. CONSULTAR TODAS LAS CREDENCIALES
    // -----------------------------------------------------------------

    /**
     * Recupera todas las credenciales y descifra sus campos.
     *
     * La consulta SQL ya no ordena por título porque el título se almacena
     * cifrado. Ordenar directamente en SQLite ordenaría el texto cifrado,
     * no el título que ve el usuario.
     *
     * Por ese motivo:
     * 1. Se leen los registros cifrados.
     * 2. Se descifran con VaultSession.
     * 3. Se ordenan en memoria por título.
     *
     * @return lista de credenciales descifradas para el Dashboard.
     * @throws SQLException si falla la consulta o el descifrado.
     */
    public List<CredentialRow> findAll() throws SQLException {

        ensureSessionOpen();

        /*
         * ArrayList es una colección dinámica: su tamaño aumenta conforme
         * se van recuperando registros de SQLite.
         *
         * El tipo List permite programar contra la interfaz general de lista,
         * sin depender innecesariamente de una implementación concreta.
         */
        List<CredentialRow> credentials = new ArrayList<>();

        String sql = """
                SELECT id, title, username, password, category
                FROM credentials
                """;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            /*
             * ResultSet.next() avanza a la siguiente fila.
             * Devuelve true mientras existan filas y false al llegar al final.
             * Así se procesan todos los registros sin conocer previamente
             * cuántos hay almacenados.
             */
            while (result.next()) {

                /*
                 * Los valores obtenidos de SQLite están cifrados.
                 * No se deben pasar directamente al modelo visual.
                 */
                /*
                 * getString("columna") obtiene el contenido de una columna.
                 * En este punto ese contenido no es el texto original, sino
                 * la representación cifrada guardada previamente por insert()
                 * o update(). decryptValue() recupera el valor legible.
                 *
                 * Cada campo se descifra por separado para reconstruir el
                 * objeto que necesita la interfaz.
                 */
                String title = decryptValue(result.getString("title"));
                String username = decryptValue(result.getString("username"));
                String password = decryptValue(result.getString("password"));
                String category = decryptValue(result.getString("category"));

                /*
                 * Se reconstruye el modelo de dominio/visual CredentialRow.
                 * El ID identifica el registro; el resto de propiedades ya
                 * están descifradas y pueden mostrarse en el Dashboard.
                 */
                CredentialRow credential = new CredentialRow(
                        result.getInt("id"),
                        title,
                        username,
                        password,
                        category
                );

                credentials.add(credential);
            }

        } catch (GeneralSecurityException exception) {
            throw new SQLException(
                    "No se pudieron descifrar las credenciales. "
                    + "Comprueba que la bóveda esté desbloqueada y que "
                    + "los datos no estén dañados.",
                    exception
            );
        }

        // El título está descifrado; ahora sí podemos ordenar correctamente.
        credentials.sort(
                Comparator.comparing(
                        CredentialRow::getTitle,
                        Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)
                )
        );

        return credentials;
    }

    // -----------------------------------------------------------------
    // 4. INSERTAR UNA CREDENCIAL
    // -----------------------------------------------------------------

    /**
     * Inserta una credencial nueva.
     *
     * Antes de escribir en SQLite, cifra individualmente cada campo.
     * Cada llamada a VaultSession.encrypt() genera un nonce AES-GCM nuevo.
     *
     * @param credential credencial con sus datos en texto normal procedentes
     *                   del formulario del Dashboard.
     * @return identificador generado por SQLite.
     * @throws SQLException si falla el cifrado o la inserción.
     */
    public int insert(CredentialRow credential) throws SQLException {

        ensureSessionOpen();
        Objects.requireNonNull(credential, "La credencial no puede ser null.");

        String sql = """
                INSERT INTO credentials
                (title, username, password, category)
                VALUES (?, ?, ?, ?)
                """;

        try {
            /*
             * PASO DE SEGURIDAD FUNDAMENTAL:
             * Primero se cifra cada campo y solo después se prepara la escritura
             * en SQLite. De este modo, los valores originales no se envían como
             * texto normal a la base de datos.
             *
             * Cada llamada a encryptValue delega el cifrado en VaultSession.
             * En AES-GCM cada cifrado debe utilizar un nonce único; la sesión
             * es responsable de generar y empaquetar ese nonce con el resultado.
             */
            String encryptedTitle = encryptValue(credential.getTitle());
            String encryptedUsername = encryptValue(credential.getUsername());
            String encryptedPassword = encryptValue(credential.getPassword());
            String encryptedCategory = encryptValue(credential.getCategory());

            try (Connection connection = DatabaseManager.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         sql,
                         Statement.RETURN_GENERATED_KEYS
                 )) {

                /*
                 * setString(posición, valor) asigna cada parámetro ? de la SQL.
                 * Las posiciones empiezan en 1, no en 0.
                 * Se envían exclusivamente las cadenas cifradas.
                 */
                statement.setString(1, encryptedTitle);
                statement.setString(2, encryptedUsername);
                statement.setString(3, encryptedPassword);
                statement.setString(4, encryptedCategory);

                /*
                 * executeUpdate() ejecuta INSERT, UPDATE o DELETE.
                 * En una inserción devuelve el número de filas afectadas,
                 * aunque aquí interesa principalmente recuperar el ID creado.
                 */
                statement.executeUpdate();

                /*
                 * getGeneratedKeys() devuelve los valores generados por la BD.
                 * Se utiliza otro try-with-resources para cerrar también
                 * el ResultSet de claves cuando termine su lectura.
                 */
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getInt(1);
                    }
                }
            }

            /*
             * Si el controlador no devuelve una clave, se informa del fallo.
             * No se devuelve un ID inventado ni un valor por defecto.
             */
            throw new SQLException("No se pudo obtener el ID generado.");

        } catch (GeneralSecurityException exception) {
            throw new SQLException(
                    "No se pudo cifrar la nueva credencial.",
                    exception
            );
        }
    }

    // -----------------------------------------------------------------
    // 5. ACTUALIZAR UNA CREDENCIAL
    // -----------------------------------------------------------------

    /**
     * Actualiza una credencial existente.
     *
     * Todos los campos se vuelven a cifrar. No se reutiliza el texto cifrado
     * anterior porque AES-GCM debe utilizar un nonce nuevo en cada operación
     * de cifrado con la misma clave.
     *
     * @param credential credencial con el ID y los nuevos datos.
     * @return true si se actualizó algún registro.
     * @throws SQLException si falla el cifrado o la actualización.
     */
    public boolean update(CredentialRow credential) throws SQLException {

        ensureSessionOpen();
        Objects.requireNonNull(credential, "La credencial no puede ser null.");

        String sql = """
                UPDATE credentials
                SET title = ?, username = ?, password = ?, category = ?
                WHERE id = ?
                """;

        try {
            String encryptedTitle = encryptValue(credential.getTitle());
            String encryptedUsername = encryptValue(credential.getUsername());
            String encryptedPassword = encryptValue(credential.getPassword());
            String encryptedCategory = encryptValue(credential.getCategory());

            try (Connection connection = DatabaseManager.getConnection();
                 PreparedStatement statement =
                         connection.prepareStatement(sql)) {

                statement.setString(1, encryptedTitle);
                statement.setString(2, encryptedUsername);
                statement.setString(3, encryptedPassword);
                statement.setString(4, encryptedCategory);
                statement.setInt(5, credential.getId());

                return statement.executeUpdate() > 0;
            }

        } catch (GeneralSecurityException exception) {
            throw new SQLException(
                    "No se pudo cifrar la credencial que se quería actualizar.",
                    exception
            );
        }
    }

    // -----------------------------------------------------------------
    // 6. ELIMINAR UNA CREDENCIAL
    // -----------------------------------------------------------------

    /**
     * Elimina una credencial por su identificador.
     *
     * No es necesario descifrar los datos para eliminarlos.
     *
     * @param id identificador de la credencial.
     * @return true si se eliminó un registro.
     * @throws SQLException si falla la operación SQLite.
     */
    public boolean delete(int id) throws SQLException {

        ensureSessionOpen();

        /*
         * La eliminación se realiza por ID.
         * No es necesario descifrar título, usuario, contraseña ni categoría:
         * SQLite puede borrar la fila directamente sin conocer su contenido.
         */
        String sql = "DELETE FROM credentials WHERE id = ?";

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setInt(1, id);

            return statement.executeUpdate() > 0;
        }
    }

    // -----------------------------------------------------------------
    // 7. MÉTODOS AUXILIARES DE CIFRADO
    // -----------------------------------------------------------------

    /**
     * Cifra un valor de texto utilizando la sesión activa.
     *
     * Se normaliza null como cadena vacía para evitar que los formularios
     * provoquen errores al intentar cifrar un campo opcional.
     *
     * @param value texto original.
     * @return valor cifrado preparado para almacenarse en SQLite.
     */
    private String encryptValue(String value)
            throws GeneralSecurityException {

        ensureSessionOpen();

        return vaultSession.encrypt(value == null ? "" : value);
    }

    /**
     * Descifra un valor recuperado de SQLite.
     *
     * @param encryptedValue texto cifrado almacenado en la base de datos.
     * @return valor original en texto normal.
     */
    private String decryptValue(String encryptedValue)
            throws GeneralSecurityException {

        ensureSessionOpen();

        return vaultSession.decrypt(encryptedValue);
    }

    // -----------------------------------------------------------------
    // 8. COMPROBACIÓN DE LA SESIÓN
    // -----------------------------------------------------------------

    /**
     * Impide operar con la base de datos si el usuario ha cerrado la sesión.
     *
     * Aunque el DAO conserve una referencia a VaultSession, esa sesión puede
     * haberse cerrado desde la aplicación. Comprobamos su estado antes de
     * cada operación y también antes de cada cifrado o descifrado.
     */
    private void ensureSessionOpen() {

        if (vaultSession == null || !vaultSession.isOpen()) {
            throw new IllegalStateException(
                    "La bóveda está bloqueada. Debes iniciar sesión de nuevo."
            );
        }
    }
}
