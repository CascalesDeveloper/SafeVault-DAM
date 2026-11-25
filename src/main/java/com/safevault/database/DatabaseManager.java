package com.safevault.database;

// Importa la clase Connection, que representa una conexión activa con una base de datos JDBC.
import java.sql.Connection;

// Importa SQLException, la excepción estándar de JDBC para errores relacionados con SQL.
import java.sql.SQLException;

/**
 * Clase de compatibilidad para centralizar el acceso a la base de datos.
 *
 * <p><strong>¿Cuál es su finalidad?</strong></p>
 * Esta clase ofrece una interfaz sencilla y estable para el resto de la aplicación.
 * En lugar de que otras clases tengan que conocer directamente los detalles de
 * SQLiteManager, pueden utilizar los métodos públicos de DatabaseManager.
 *
 * <p><strong>Delegación de responsabilidades</strong></p>
 * DatabaseManager no crea conexiones, no define tablas y no ejecuta directamente
 * las sentencias SQL. Su responsabilidad es delegar esas tareas en SQLiteManager,
 * que es la clase encargada de la implementación concreta de SQLite.
 *
 * <p><strong>¿Por qué se mantiene esta clase?</strong></p>
 * Permite conservar las llamadas que ya utiliza el resto del proyecto, por ejemplo
 * CredentialDAO y SafeVaultDAM, sin tener que modificar esas clases cada vez que
 * cambie la implementación interna del gestor de base de datos.
 *
 * <p>Este patrón de delegación reduce el acoplamiento entre las clases: el código
 * que necesita una conexión depende de esta interfaz de acceso y no de los detalles
 * internos de inicialización de SQLite.</p>
 *
 * <p><strong>Importante:</strong> esta clase no almacena conexiones ni mantiene
 * una conexión global abierta. Cada llamada a getConnection() solicita a
 * SQLiteManager una conexión nueva, cuyo cierre corresponde al código que la usa.</p>
 */
public class DatabaseManager {

    /**
     * Obtiene una conexión JDBC con la base de datos SQLite.
     *
     * <p>El método actúa como intermediario: recibe la petición del resto de la
     * aplicación y la transmite a SQLiteManager.</p>
     *
     * <p>El tipo Connection pertenece a JDBC (Java Database Connectivity) y
     * proporciona los métodos necesarios para preparar y ejecutar consultas SQL.</p>
     *
     * <p>El método es static porque no es necesario crear un objeto DatabaseManager
     * para utilizarlo. Se invoca directamente mediante el nombre de la clase:</p>
     *
     * <pre>
     * Connection connection = DatabaseManager.getConnection();
     * </pre>
     *
     * <p>La cláusula throws SQLException indica que el método puede propagar al
     * llamador un error producido al obtener la conexión. La clase que lo invoque
     * deberá gestionar esa excepción o declararla también.</p>
     *
     * @return una conexión JDBC abierta con la base de datos SQLite.
     * @throws SQLException si SQLiteManager no puede establecer la conexión.
     */
    public static Connection getConnection() throws SQLException {

        // Delegamos la creación y configuración de la conexión en SQLiteManager.
        // De este modo evitamos duplicar aquí la lógica específica del motor SQLite.
        return SQLiteManager.getConnection();
    }

    /**
     * Inicializa la estructura y los recursos necesarios de la base de datos.
     *
     * <p>Este método se utiliza durante el arranque de Vaultix, antes de mostrar
     * la pantalla de inicio de sesión. La inicialización concreta —por ejemplo,
     * comprobar la existencia del archivo de base de datos o preparar las tablas—
     * está implementada en SQLiteManager.</p>
     *
     * <p>La operación puede producir una SQLException si falla alguna tarea de
     * acceso o configuración de la base de datos. La excepción se propaga para
     * que la clase principal pueda impedir que la aplicación continúe arrancando
     * en un estado inconsistente.</p>
     *
     * <p>Este método también es static: no requiere crear una instancia de
     * DatabaseManager.</p>
     *
     * @throws SQLException si se produce un error al inicializar SQLite.
     */
    public static void initializeDatabase() throws SQLException {

        // Transferimos la petición a la clase que conoce los detalles de SQLite.
        // DatabaseManager se limita a mantener una entrada común para el proyecto.
        SQLiteManager.initializeDatabase();
    }

    /**
     * Comprueba si es posible establecer comunicación con la base de datos.
     *
     * <p>La comprobación real se realiza en SQLiteManager. Esta clase devuelve
     * directamente el resultado booleano obtenido:</p>
     *
     * <ul>
     *   <li>true: la prueba de conexión ha resultado satisfactoria.</li>
     *   <li>false: no se ha podido verificar correctamente la conexión.</li>
     * </ul>
     *
     * <p>El resultado permite a la clase principal decidir si puede continuar
     * con el arranque o debe informar de un problema al usuario.</p>
     *
     * <p>A diferencia de los dos métodos anteriores, este método no declara
     * SQLException en su firma. Por tanto, el contrato público de
     * SQLiteManager.testConnection() gestiona internamente los errores de
     * comprobación y comunica el resultado mediante true o false.</p>
     *
     * @return true si la prueba de conexión tiene éxito; false en caso contrario.
     */
    public static boolean testConnection() {

        // No repetimos aquí la prueba de conexión.
        // Devolvemos el resultado que calcula el gestor específico de SQLite.
        return SQLiteManager.testConnection();
    }
}
