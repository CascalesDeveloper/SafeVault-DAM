package com.safevault.database;

// -----------------------------------------------------------------------------
// IMPORTACIONES
// -----------------------------------------------------------------------------
// Las clases importadas permiten trabajar con rutas del sistema de archivos,
// crear carpetas, abrir conexiones JDBC con SQLite y ejecutar sentencias SQL.

import java.nio.file.Files;          // Operaciones sobre archivos y directorios.
import java.nio.file.Path;           // Representación independiente de una ruta.
import java.sql.Connection;          // Conexión activa con la base de datos.
import java.sql.DriverManager;       // Fábrica que obtiene conexiones JDBC.
import java.sql.SQLException;        // Excepción específica de operaciones SQL.
import java.sql.Statement;           // Permite ejecutar sentencias SQL sin parámetros.

/**
 * -----------------------------------------------------------------------------
 * CLASE SQLiteManager
 * -----------------------------------------------------------------------------
 *
 * RESPONSABILIDAD PRINCIPAL
 * -------------------------
 * Esta clase centraliza la conexión y la inicialización de la base de datos
 * SQLite utilizada por Vaultix.
 *
 * SQLite es un sistema gestor de bases de datos relacional y embebido. A
 * diferencia de MariaDB o MySQL, no necesita un servidor independiente:
 * trabaja directamente sobre un archivo almacenado en el equipo.
 *
 * FUNCIONES DE ESTA CLASE
 * -----------------------
 * 1. Definir dónde se encuentra el archivo de la base de datos.
 * 2. Crear la carpeta que contiene ese archivo cuando sea necesario.
 * 3. Obtener conexiones JDBC con SQLite.
 * 4. Crear la tabla de credenciales si todavía no existe.
 * 5. Comprobar que se puede abrir una conexión.
 *
 * SEPARACIÓN DE RESPONSABILIDADES
 * -------------------------------
 * SQLiteManager se ocupa de la infraestructura de conexión y de la estructura
 * inicial de la base de datos. No contiene la lógica de presentación ni la
 * lógica de cifrado de las credenciales.
 *
 * El acceso a los registros y el cifrado de sus campos se realiza desde
 * CredentialDAO, que utiliza una VaultSession activa.
 *
 * NOTA SOBRE LA RUTA
 * ------------------
 * La ruta "data/safevault.db" es relativa al directorio de trabajo desde el
 * que se inicia la aplicación. No es una ruta absoluta fija de Windows.
 * Esto facilita ejecutar el proyecto en distintos equipos, aunque al preparar
 * una distribución definitiva conviene decidir una ubicación estable para los
 * datos del usuario.
 */
public class SQLiteManager {

    // -------------------------------------------------------------------------
    // 1. CONFIGURACIÓN DE LA UBICACIÓN DE LA BASE DE DATOS
    // -------------------------------------------------------------------------

    /**
     * Ruta del archivo físico que contiene la base de datos.
     *
     * Path.of(...) construye una ruta utilizando los separadores adecuados
     * para el sistema operativo donde se ejecute Java.
     *
     * En este caso, la ruta está formada por dos elementos:
     * - "data": carpeta donde se guardará la base de datos.
     * - "safevault.db": nombre del archivo SQLite.
     *
     * Se declara como private para que otras clases no puedan modificar
     * directamente esta configuración.
     *
     * Se declara como static porque existe una única ruta compartida por todas
     * las operaciones de esta clase.
     *
     * Se declara como final porque la referencia a la ruta no debe reasignarse
     * después de inicializar la clase.
     */
    private static final Path DATABASE_PATH =
            Path.of("data", "safevault.db");

    /**
     * URL JDBC utilizada por el controlador de SQLite.
     *
     * JDBC (Java Database Connectivity) es la API estándar de Java para
     * comunicarse con bases de datos.
     *
     * El prefijo "jdbc:sqlite:" indica al DriverManager que debe utilizar el
     * controlador JDBC de SQLite.
     *
     * toAbsolutePath() convierte la ruta relativa anterior en una ruta
     * absoluta. De esta manera, la URL JDBC recibe una ubicación completa
     * del archivo, por ejemplo:
     *
     * jdbc:sqlite:C:\ruta\del\proyecto\data\safevault.db
     *
     * La ruta exacta depende del directorio de trabajo del proceso.
     *
     * La URL se calcula al inicializar la clase y se mantiene constante durante
     * la ejecución.
     */
    private static final String URL =
            "jdbc:sqlite:" + DATABASE_PATH.toAbsolutePath();

    // -------------------------------------------------------------------------
    // 2. OBTENCIÓN DE CONEXIONES
    // -------------------------------------------------------------------------

    /**
     * Obtiene una conexión JDBC con la base de datos SQLite.
     *
     * Antes de abrir la conexión, comprueba que exista la carpeta que debe
     * contener el archivo de base de datos. Si no existe, la crea.
     *
     * Files.createDirectories(...) crea todos los directorios intermedios que
     * falten. Si la carpeta ya existe, la operación no produce ningún problema.
     *
     * DATABASE_PATH.getParent() obtiene la carpeta padre de la ruta del archivo:
     * en este caso, la carpeta "data".
     *
     * Después se utiliza DriverManager.getConnection(URL) para solicitar una
     * conexión al controlador JDBC de SQLite.
     *
     * IMPORTANTE:
     * Abrir una conexión con una URL SQLite puede crear el archivo de base de
     * datos si todavía no existe. La creación de la tabla, sin embargo, se
     * realiza en initializeDatabase().
     *
     * @return una conexión JDBC abierta con SQLite.
     * @throws SQLException si no se puede crear la carpeta o abrir la conexión.
     */
    public static Connection getConnection() throws SQLException {

        // Intentamos garantizar que exista el directorio que contendrá el .db.
        try {

            // getParent() obtiene "data", es decir, la carpeta contenedora.
            // createDirectories() la crea si todavía no está disponible.
            Files.createDirectories(DATABASE_PATH.getParent());

        } catch (Exception e) {

            /*
             * La API de archivos puede lanzar distintas excepciones de entrada
             * y salida. Se convierten en SQLException para que las clases que
             * utilizan este gestor reciban un tipo de error coherente con las
             * operaciones de base de datos.
             *
             * El segundo argumento del constructor conserva la excepción
             * original como causa. Esto permite consultar el error técnico
             * completo durante la depuración.
             */
            throw new SQLException(
                    "No se pudo crear la carpeta de SQLite.", e
            );
        }

        // Solicitamos al controlador JDBC una conexión con la URL configurada.
        // El llamador será responsable de cerrarla, normalmente con try-with-resources.
        return DriverManager.getConnection(URL);
    }

    // -------------------------------------------------------------------------
    // 3. INICIALIZACIÓN DE LA BASE DE DATOS Y CREACIÓN DE LA TABLA
    // -------------------------------------------------------------------------

    /**
     * Inicializa la base de datos de Vaultix.
     *
     * Comprueba que la tabla "credentials" exista y, si no existe, la crea.
     *
     * CREATE TABLE IF NOT EXISTS significa:
     * - CREATE TABLE: crear una tabla.
     * - IF NOT EXISTS: hacerlo solamente si todavía no existe una tabla con
     *   ese nombre.
     *
     * Por tanto, este método puede ejecutarse en cada arranque sin borrar ni
     * recrear una tabla que ya contenga información.
     *
     * ESTRUCTURA DE LA TABLA
     * ----------------------
     * id:
     *   Identificador numérico de cada registro. Es la clave primaria y SQLite
     *   genera automáticamente un nuevo valor.
     *
     * title:
     *   Título o nombre descriptivo de la credencial.
     *
     * username:
     *   Nombre de usuario asociado al servicio guardado.
     *
     * password:
     *   Contraseña asociada al servicio. En la arquitectura actual de Vaultix,
     *   CredentialDAO cifra este campo antes de almacenarlo.
     *
     * category:
     *   Categoría utilizada para clasificar la credencial.
     *
     * NOT NULL:
     *   Impide insertar un valor SQL NULL en la columna correspondiente.
     *
     * IMPORTANTE SOBRE EL CIFRADO
     * --------------------------
     * La tabla declara sus campos como TEXT, pero eso no significa que se
     * almacenen necesariamente en texto legible. CredentialDAO transforma
     * los valores en texto cifrado antes de ejecutar las operaciones INSERT
     * o UPDATE.
     *
     * Este método crea la estructura; no cifra datos ni realiza la migración
     * de registros antiguos.
     *
     * @throws SQLException si no se puede abrir la conexión o ejecutar el SQL.
     */
    public static void initializeDatabase() throws SQLException {

        /*
         * Bloque de texto (text block) de Java.
         *
         * Las tres comillas permiten escribir la sentencia SQL en varias
         * líneas, manteniendo una estructura fácil de leer.
         *
         * INTEGER PRIMARY KEY AUTOINCREMENT:
         *   Define un identificador entero que SQLite incrementa al insertar
         *   nuevos registros.
         *
         * TEXT NOT NULL:
         *   Define una columna de texto obligatoria.
         */
        String sql = """
                CREATE TABLE IF NOT EXISTS credentials (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    username TEXT NOT NULL,
                    password TEXT NOT NULL,
                    category TEXT NOT NULL
                )
                """;

        /*
         * try-with-resources
         * ------------------
         * Esta construcción de Java cierra automáticamente los recursos
         * declarados entre paréntesis al abandonar el bloque, tanto si termina
         * correctamente como si se produce una excepción.
         *
         * Primero se obtiene una conexión y después se crea un Statement
         * asociado a ella.
         *
         * Statement se utiliza aquí porque la sentencia no contiene valores
         * variables proporcionados por el usuario. Para consultas con datos
         * variables se debe preferir PreparedStatement.
         */
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {

            // Ejecuta la sentencia DDL que crea la tabla si todavía no existe.
            // DDL significa Data Definition Language: lenguaje de definición
            // de estructuras de bases de datos, como tablas e índices.
            statement.execute(sql);

            // Mensajes de diagnóstico para la consola de desarrollo.
            // No son mensajes que se muestren en la interfaz gráfica.
            System.out.println(
                    "SafeVault: base de datos SQLite inicializada."
            );

            // Se muestra la ruta absoluta para facilitar la localización del
            // archivo .db durante las pruebas y tareas de mantenimiento.
            System.out.println(
                    "Ubicación: " + DATABASE_PATH.toAbsolutePath()
            );
        }
    }

    // -------------------------------------------------------------------------
    // 4. COMPROBACIÓN DE LA CONEXIÓN
    // -------------------------------------------------------------------------

    /**
     * Comprueba si se puede establecer una conexión con SQLite.
     *
     * El método intenta obtener una conexión y verifica que:
     * 1. El objeto Connection no sea null.
     * 2. La conexión no esté cerrada.
     *
     * La conexión se declara en un try-with-resources, por lo que se cierra
     * automáticamente al terminar la comprobación.
     *
     * Este método devuelve un boolean:
     * - true: se ha podido obtener una conexión abierta.
     * - false: se ha producido un error SQL o la conexión no está disponible.
     *
     * La clase principal SafeVaultDAM utiliza este resultado durante el
     * arranque para impedir que Vaultix continúe si no puede acceder a su
     * base de datos.
     *
     * @return true si la conexión está disponible; false en caso contrario.
     */
    public static boolean testConnection() {

        // Abrimos una conexión temporal únicamente para comprobar su estado.
        try (Connection connection = getConnection()) {

            // isClosed() devuelve true si la conexión ya está cerrada.
            // La comprobación connection != null es defensiva; normalmente
            // DriverManager devuelve una conexión o lanza una SQLException.
            return connection != null && !connection.isClosed();

        } catch (SQLException e) {

            /*
             * Si falla la conexión, informamos en la consola de errores.
             *
             * Se devuelve false en lugar de propagar la excepción porque este
             * método está diseñado como una comprobación sencilla de estado.
             * La clase que lo invoque decide cómo reaccionar ante ese false.
             */
            System.err.println(
                    "Error al conectar con SQLite: " + e.getMessage()
            );

            return false;
        }
    }
}
