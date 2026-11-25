package com.safevault.security;


/*
 * DOCUMENTACIÓN COMPLEMENTARIA PARA EL ESTUDIO DE VAULTIX
 *
 * Esta clase concentra la autenticación y las operaciones criptográficas de
 * la bóveda. La interfaz nunca debe guardar la contraseña maestra ni construir
 * por su cuenta la clave AES.
 *
 * Flujo general:
 * 1. La primera ejecución valida los datos y crea la configuración.
 * 2. PBKDF2 deriva una clave a partir de la contraseña y una sal aleatoria.
 * 3. AES-GCM cifra el verificador y los campos de las credenciales.
 * 4. En los accesos posteriores se deriva de nuevo la clave y se comprueba
 *    el verificador cifrado.
 * 5. Una VaultSession mantiene temporalmente la clave mientras la bóveda
 *    permanece desbloqueada.
 *
 * Las funciones criptográficas deben mantenerse separadas de la interfaz y
 * del DAO. Los comentarios de este archivo explican las responsabilidades;
 * no sustituyen una auditoría de seguridad ni pruebas criptográficas.
 */

import com.safevault.database.DatabaseManager;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Base64;

/**
 * Gestiona la creación de la bóveda y la autenticación del usuario principal.
 *
 * Vaultix está planteado como una aplicación local de un único usuario:
 *
 * - En el primer inicio se crea el usuario principal y su contraseña maestra.
 * - En los siguientes inicios se comprueban las credenciales introducidas.
 * - La contraseña maestra nunca se guarda en SQLite.
 * - La clave de cifrado se deriva con PBKDF2WithHmacSHA256.
 * - Los datos de la bóveda se cifran con AES-256-GCM.
 *
 * IMPORTANTE:
 * Esta clase utiliza la tabla "credentials" que ya crea SQLiteManager.
 * También crea "vault_config" si todavía no existe.
 *
 * En la primera configuración, los registros que ya existan en "credentials"
 * se cifran dentro de la misma transacción en la que se crea la configuración
 * de la bóveda. Si algo falla, se revierte la transacción para no dejar una
 * migración a medias.
 *
 * Los campos title, username, password y category se cifran individualmente.
 * Por tanto, el DAO deberá utilizar la VaultSession desbloqueada para cifrar
 * y descifrar esos campos. No debe leerlos directamente como texto normal.
 */
public class AuthenticationManager {

    // -----------------------------------------------------------------
    // 1. PARÁMETROS CRIPTOGRÁFICOS
    // -----------------------------------------------------------------

    /**
     * Número de iteraciones PBKDF2.
     *
     * 600.000 iteraciones suponen un coste deliberado para dificultar ataques
     * de diccionario contra una contraseña maestra obtenida fuera de línea.
     * El tiempo real dependerá del ordenador del usuario.
     */
    private static final int PBKDF2_ITERATIONS = 600_000;

    /** Longitud de la sal aleatoria en bytes. */
    private static final int SALT_LENGTH = 16;

    /** Longitud de la clave AES-256 en bytes. */
    private static final int KEY_LENGTH_BYTES = 32;

    /** Longitud del nonce recomendado para AES-GCM en bytes. */
    private static final int GCM_NONCE_LENGTH = 12;

    /** Tamaño de la etiqueta de autenticación GCM en bits. */
    private static final int GCM_TAG_LENGTH_BITS = 128;

    /** Identificador de versión del formato de los datos cifrados. */
    private static final String ENCRYPTED_PREFIX = "v1:";

    /**
     * Texto de comprobación que se cifra al crear la bóveda.
     *
     * No es una contraseña ni un secreto: sirve para comprobar que la clave
     * derivada al iniciar sesión es la correcta. Si la contraseña no coincide,
     * AES-GCM no podrá autenticar y descifrar correctamente este valor.
     */
    private static final byte[] VERIFICATION_TEXT =
            "VAULTIX_MASTER_KEY_VERIFICATION_V1"
                    .getBytes(StandardCharsets.UTF_8);

    /** Generador criptográficamente seguro para sales y nonces. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // -----------------------------------------------------------------
    // 2. CONSTRUCTOR
    // -----------------------------------------------------------------

    /**
     * Constructor privado: esta clase ofrece operaciones estáticas y no
     * necesita mantener estado entre llamadas.
     */
    private AuthenticationManager() {
        // Evita crear instancias innecesarias de AuthenticationManager.
    }

    // -----------------------------------------------------------------
    // 3. ESTADO DE LA BÓVEDA
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Consulta la tabla vault_config para saber si ya se ha creado la configuración de la bóveda. Se utiliza para decidir si la aplicación debe mostrar el registro inicial o el formulario de acceso.
    /**
     * Indica si ya existe una configuración de usuario principal.
     *
     * Si devuelve false, la aplicación debe mostrar el formulario de primera
     * configuración. Si devuelve true, debe mostrar el formulario de acceso.
     *
     * @return true si la bóveda ya tiene usuario configurado.
     * @throws SQLException si no se puede consultar SQLite.
     */
    public static boolean isVaultConfigured() throws SQLException {
        ensureVaultConfigTable();

        String sql = "SELECT COUNT(*) FROM vault_config WHERE id = 1";

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            return result.next() && result.getInt(1) > 0;
        }
    }

    // NOTA DIDÁCTICA: Lee el nombre del usuario principal guardado en la configuración. El nombre puede mostrarse en la interfaz, pero no sustituye a la contraseña maestra como mecanismo de protección.
    /**
     * Recupera el nombre del usuario principal para mostrarlo en la pantalla
     * de acceso, si se desea.
     *
     * El nombre de usuario no se considera la clave secreta de la bóveda.
     * La contraseña maestra nunca se recupera ni se almacena.
     *
     * @return nombre del usuario o null si todavía no hay bóveda configurada.
     * @throws SQLException si falla la consulta.
     */
    public static String getConfiguredUsername() throws SQLException {
        ensureVaultConfigTable();

        String sql = "SELECT username FROM vault_config WHERE id = 1";

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            return result.next() ? result.getString("username") : null;
        }
    }

    // -----------------------------------------------------------------
    // 4. CONFIGURACIÓN INICIAL
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Realiza la configuración inicial de la bóveda: valida los datos, genera sal, deriva la clave, crea el verificador cifrado y migra los registros antiguos dentro de una transacción.
    /**
     * Crea la bóveda del usuario principal.
     *
     * En esta operación:
     * 1. Se validan el nombre de usuario y la contraseña maestra.
     * 2. Se genera una sal aleatoria.
     * 3. Se deriva una clave AES-256 mediante PBKDF2.
     * 4. Se cifra un texto de verificación con AES-GCM.
     * 5. Se guarda la configuración criptográfica, nunca la contraseña.
     * 6. Se cifran los registros existentes para migrarlos desde texto plano.
     * 7. Se confirma todo mediante una única transacción SQLite.
     *
     * La contraseña recibida se sobrescribe al terminar, tanto si la operación
     * tiene éxito como si se produce un error.
     *
     * @param username nombre del único usuario principal.
     * @param masterPassword contraseña maestra introducida por el usuario.
     * @return sesión desbloqueada que deberá conservar la aplicación mientras
     *         el usuario esté dentro de la bóveda.
     * @throws SQLException si falla la operación de base de datos.
     * @throws GeneralSecurityException si falla una operación criptográfica.
     * @throws IllegalArgumentException si los datos no cumplen los requisitos.
     */
    public static VaultSession createVault(
            String username,
            char[] masterPassword)
            throws SQLException, GeneralSecurityException {

        byte[] salt = null;
        byte[] derivedKey = null;

        try {
            // La validación también queda dentro del try/finally para limpiar
            // el array aunque la contraseña incumpla la política.
            validateCredentials(username, masterPassword);
            ensureVaultConfigTable();

            salt = new byte[SALT_LENGTH];
            SECURE_RANDOM.nextBytes(salt);

            // Derivamos la clave sin guardar nunca la contraseña maestra.
            derivedKey = deriveKey(masterPassword, salt, PBKDF2_ITERATIONS);

            // El texto de verificación permite comprobar la clave en futuros
            // inicios de sesión sin almacenar la contraseña original.
            EncryptedData verifier = encryptBytes(VERIFICATION_TEXT, derivedKey);

            try (Connection connection = DatabaseManager.getConnection()) {
                connection.setAutoCommit(false);

                try {
                    // Evitamos sobrescribir accidentalmente una bóveda creada.
                    if (isVaultConfigured(connection)) {
                        throw new IllegalStateException(
                                "La bóveda ya está configurada. "
                                + "No se puede crear otra desde este proceso."
                        );
                    }

                    insertVaultConfiguration(
                            connection,
                            username.trim(),
                            salt,
                            PBKDF2_ITERATIONS,
                            verifier
                    );

                    /*
                     * Migración inicial:
                     * si la base de datos ya tenía contraseñas guardadas en
                     * texto plano, las ciframos antes de confirmar la creación.
                     * La operación se ejecuta dentro de la misma transacción.
                     */
                    encryptExistingCredentials(connection, derivedKey);

                    connection.commit();

                } catch (Exception exception) {
                    // Si falla la configuración o la migración, no dejamos
                    // una base de datos parcialmente convertida.
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackException) {
                        exception.addSuppressed(rollbackException);
                    }

                    if (exception instanceof SQLException sqlException) {
                        throw sqlException;
                    }

                    if (exception instanceof GeneralSecurityException securityException) {
                        throw securityException;
                    }

                    if (exception instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }

                    throw new SQLException(
                            "No se pudo crear la bóveda de Vaultix.",
                            exception
                    );
                }
            }

            // La sesión recibe una copia independiente de la clave.
            return new VaultSession(derivedKey);

        } finally {
            // Limpieza de datos temporales sensibles.
            clearPassword(masterPassword);
            if (derivedKey != null) {
                Arrays.fill(derivedKey, (byte) 0);
            }
            if (salt != null) {
                Arrays.fill(salt, (byte) 0);
            }
        }
    }

    // -----------------------------------------------------------------
    // 5. AUTENTICACIÓN DE INICIO DE SESIÓN
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Comprueba las credenciales introducidas. Si el usuario y la contraseña son correctos, devuelve una sesión con la clave necesaria para operar con los datos cifrados.
    /**
     * Comprueba las credenciales y desbloquea la bóveda.
     *
     * Si el usuario o la contraseña no son correctos, devuelve null.
     * Si la autenticación es correcta, devuelve una VaultSession que contiene
     * la clave derivada necesaria para trabajar con los datos cifrados.
     *
     * La contraseña recibida se sobrescribe al terminar.
     *
     * @param username nombre de usuario introducido.
     * @param masterPassword contraseña maestra introducida.
     * @return sesión desbloqueada o null si las credenciales no son válidas.
     * @throws SQLException si falla SQLite.
     * @throws GeneralSecurityException si falla el proceso criptográfico.
     */
    public static VaultSession authenticate(
            String username,
            char[] masterPassword)
            throws SQLException, GeneralSecurityException {

        if (username == null || username.trim().isEmpty()
                || masterPassword == null || masterPassword.length == 0) {
            clearPassword(masterPassword);
            return null;
        }

        ensureVaultConfigTable();

        String sql = """
                SELECT username, salt, iterations,
                       verifier_nonce, verifier_ciphertext
                FROM vault_config
                WHERE id = 1
                """;

        byte[] salt = null;
        byte[] derivedKey = null;

        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            if (!result.next()) {
                return null;
            }

            String storedUsername = result.getString("username");

            /*
             * El nombre de usuario se compara sin distinguir mayúsculas.
             * No es el secreto criptográfico; la protección principal reside
             * en la contraseña maestra y en la clave derivada.
             */
            if (storedUsername == null
                    || !storedUsername.equalsIgnoreCase(username.trim())) {
                return null;
            }

            salt = result.getBytes("salt");
            int iterations = result.getInt("iterations");
            byte[] verifierNonce = result.getBytes("verifier_nonce");
            byte[] verifierCiphertext = result.getBytes("verifier_ciphertext");

            if (salt == null || verifierNonce == null
                    || verifierCiphertext == null
                    || iterations < 100_000) {
                throw new SQLException(
                        "La configuración de seguridad de Vaultix está dañada."
                );
            }

            // La misma sal e iteraciones producen la misma clave para la
            // contraseña correcta. Una contraseña incorrecta produce otra.
            derivedKey = deriveKey(masterPassword, salt, iterations);

            try {
                byte[] decryptedVerifier = decryptBytes(
                        verifierNonce,
                        verifierCiphertext,
                        derivedKey
                );

                boolean valid = MessageDigest.isEqual(
                        VERIFICATION_TEXT,
                        decryptedVerifier
                );

                Arrays.fill(decryptedVerifier, (byte) 0);

                if (!valid) {
                    return null;
                }

                // La sesión conserva su propia copia de la clave.
                return new VaultSession(derivedKey);

            } catch (AEADBadTagException exception) {
                // Una etiqueta GCM incorrecta significa contraseña errónea
                // o datos de verificación manipulados/dañados.
                return null;
            }

        } finally {
            clearPassword(masterPassword);

            if (salt != null) {
                Arrays.fill(salt, (byte) 0);
            }

            if (derivedKey != null) {
                Arrays.fill(derivedKey, (byte) 0);
            }
        }
    }

    // -----------------------------------------------------------------
    // 6. VALIDACIÓN DE DATOS
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Aplica las reglas de registro a los datos recibidos. Esta validación en la capa de seguridad es necesaria aunque la interfaz también muestre los requisitos visualmente.
    /**
     * Valida los datos necesarios para crear la primera bóveda.
     *
     * La longitud mínima de contraseña se establece en 12 caracteres.
     * Se pueden ajustar los requisitos más adelante si el proyecto lo exige.
     */
    private static void validateCredentials(
            String username,
            char[] masterPassword) {

        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "El nombre de usuario es obligatorio."
            );
        }

        String normalizedUsername = username.trim();
        if (normalizedUsername.length() < 3) {
            throw new IllegalArgumentException(
                    "El nombre de usuario debe tener al menos 3 caracteres."
            );
        }

        if (masterPassword == null || masterPassword.length < 12) {
            throw new IllegalArgumentException(
                    "La contraseña maestra debe tener al menos 12 caracteres."
            );
        }

        if (masterPassword.length > 20) {
            throw new IllegalArgumentException(
                    "La contraseña maestra no puede superar los 20 caracteres."
            );
        }

        boolean hasUppercase = false;
        boolean hasLowercase = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (char character : masterPassword) {
            hasUppercase |= Character.isUpperCase(character);
            hasLowercase |= Character.isLowerCase(character);
            hasDigit |= Character.isDigit(character);
            hasSpecial |= !Character.isLetterOrDigit(character);
        }

        if (!hasUppercase) {
            throw new IllegalArgumentException(
                    "La contraseña debe contener al menos una letra mayúscula."
            );
        }
        if (!hasLowercase) {
            throw new IllegalArgumentException(
                    "La contraseña debe contener al menos una letra minúscula."
            );
        }
        if (!hasDigit) {
            throw new IllegalArgumentException(
                    "La contraseña debe contener al menos un número."
            );
        }
        if (!hasSpecial) {
            throw new IllegalArgumentException(
                    "La contraseña debe contener al menos un carácter especial."
            );
        }

        // Compara el usuario dentro del array sin crear una String adicional
        // que contenga la contraseña maestra completa.
        if (containsIgnoreCase(masterPassword, normalizedUsername)) {
            throw new IllegalArgumentException(
                    "La contraseña no puede contener el nombre de usuario."
            );
        }
    }

    // NOTA DIDÁCTICA: Busca una cadena dentro de otra sin distinguir mayúsculas y minúsculas. Se utiliza para impedir que la contraseña contenga el nombre de usuario.
    /**
     * Busca el nombre de usuario dentro de la contraseña sin distinguir
     * mayúsculas/minúsculas y sin convertir la contraseña completa a String.
     */
    private static boolean containsIgnoreCase(char[] password, String username) {
        if (username.isEmpty() || username.length() > password.length) {
            return false;
        }

        for (int start = 0; start <= password.length - username.length(); start++) {
            boolean matches = true;

            for (int offset = 0; offset < username.length(); offset++) {
                char passwordCharacter = Character.toLowerCase(password[start + offset]);
                char usernameCharacter = Character.toLowerCase(username.charAt(offset));

                if (passwordCharacter != usernameCharacter) {
                    matches = false;
                    break;
                }
            }

            if (matches) {
                return true;
            }
        }

        return false;
    }

    // -----------------------------------------------------------------
    // 7. DERIVACIÓN DE CLAVE CON PBKDF2
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Deriva una clave criptográfica a partir de la contraseña y de una sal mediante PBKDF2. La contraseña original no se almacena en la base de datos.
    /**
     * Deriva una clave de 256 bits a partir de la contraseña maestra y la sal.
     *
     * PBKDF2 no cifra directamente la contraseña: transforma la contraseña
     * introducida en una clave criptográfica mediante muchas iteraciones.
     *
     * @param password contraseña maestra.
     * @param salt sal aleatoria de la bóveda.
     * @param iterations número de iteraciones PBKDF2.
     * @return clave derivada de 32 bytes.
     * @throws GeneralSecurityException si el proveedor criptográfico falla.
     */
    private static byte[] deriveKey(
            char[] password,
            byte[] salt,
            int iterations)
            throws GeneralSecurityException {

        PBEKeySpec keySpec = new PBEKeySpec(
                password,
                salt,
                iterations,
                KEY_LENGTH_BYTES * 8
        );

        try {
            SecretKeyFactory factory =
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");

            return factory.generateSecret(keySpec).getEncoded();

        } finally {
            // PBEKeySpec mantiene internamente una copia de la contraseña.
            keySpec.clearPassword();
        }
    }

    // -----------------------------------------------------------------
    // 8. CIFRADO Y DESCIFRADO AES-256-GCM
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Cifra una secuencia de bytes con AES-GCM y un nonce aleatorio. Devuelve por separado el nonce y el texto cifrado para poder descifrarlos posteriormente.
    /**
     * Cifra datos binarios mediante AES-256-GCM.
     *
     * Cada llamada genera un nonce aleatorio nuevo. Nunca debe reutilizarse
     * un nonce con la misma clave AES-GCM.
     */
    private static EncryptedData encryptBytes(
            byte[] plainText,
            byte[] key)
            throws GeneralSecurityException {

        byte[] nonce = new byte[GCM_NONCE_LENGTH];
        SECURE_RANDOM.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        GCMParameterSpec parameters =
                new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameters);

        byte[] encrypted = cipher.doFinal(plainText);

        return new EncryptedData(nonce, encrypted);
    }

    // NOTA DIDÁCTICA: Intenta descifrar y autenticar los datos mediante AES-GCM. Si la clave o los datos no son correctos, la autenticación GCM falla.
    /**
     * Descifra datos binarios mediante AES-256-GCM.
     *
     * AES-GCM también verifica la integridad: si los datos o la clave no
     * coinciden, el proceso falla en lugar de devolver datos manipulados.
     */
    private static byte[] decryptBytes(
            byte[] nonce,
            byte[] encrypted,
            byte[] key)
            throws GeneralSecurityException {

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        GCMParameterSpec parameters =
                new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);

        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameters);

        return cipher.doFinal(encrypted);
    }

    // -----------------------------------------------------------------
    // 9. TABLA DE CONFIGURACIÓN DE LA BÓVEDA
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Garantiza que exista la tabla de configuración criptográfica. La operación crea la estructura solo cuando todavía no está presente.
    /**
     * Crea la tabla de configuración si aún no existe.
     *
     * Esta tabla guarda:
     * - nombre del usuario principal;
     * - sal aleatoria;
     * - iteraciones PBKDF2;
     * - nonce del texto de verificación;
     * - texto de verificación cifrado.
     *
     * No guarda la contraseña maestra ni la clave AES derivada.
     */
    private static void ensureVaultConfigTable() throws SQLException {

        String sql = """
                CREATE TABLE IF NOT EXISTS vault_config (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    username TEXT NOT NULL,
                    salt BLOB NOT NULL,
                    iterations INTEGER NOT NULL,
                    verifier_nonce BLOB NOT NULL,
                    verifier_ciphertext BLOB NOT NULL,
                    format_version INTEGER NOT NULL DEFAULT 1
                )
                """;

        try (Connection connection = DatabaseManager.getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute(sql);
        }
    }

    // NOTA DIDÁCTICA: Consulta la tabla vault_config para saber si ya se ha creado la configuración de la bóveda. Se utiliza para decidir si la aplicación debe mostrar el registro inicial o el formulario de acceso.
    /** Comprueba la configuración dentro de una conexión ya abierta. */
    private static boolean isVaultConfigured(Connection connection)
            throws SQLException {

        String sql = "SELECT 1 FROM vault_config WHERE id = 1";

        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {

            return result.next();
        }
    }

    // NOTA DIDÁCTICA: Guarda en vault_config los parámetros necesarios para verificar futuros accesos: usuario, sal, iteraciones y verificador cifrado.
    /**
     * Inserta la configuración criptográfica del usuario principal.
     */
    private static void insertVaultConfiguration(
            Connection connection,
            String username,
            byte[] salt,
            int iterations,
            EncryptedData verifier)
            throws SQLException {

        String sql = """
                INSERT INTO vault_config (
                    id, username, salt, iterations,
                    verifier_nonce, verifier_ciphertext, format_version
                )
                VALUES (1, ?, ?, ?, ?, ?, 1)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setBytes(2, salt);
            statement.setInt(3, iterations);
            statement.setBytes(4, verifier.nonce());
            statement.setBytes(5, verifier.cipherText());
            statement.executeUpdate();
        }
    }

    // -----------------------------------------------------------------
    // 10. MIGRACIÓN DE REGISTROS EXISTENTES
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Recorre las credenciales que ya existían y cifra sus campos dentro de la transacción de creación de la bóveda, evitando una migración parcial.
    /**
     * Cifra los registros que existían antes de crear la bóveda.
     *
     * En la versión anterior de SafeVault, los campos se guardaban como texto
     * normal. Esta operación los convierte a un formato cifrado con prefijo
     * "v1:" para que el DAO pueda reconocer el formato.
     *
     * Se cifran los cuatro campos, no solamente la contraseña:
     * - title
     * - username
     * - password
     * - category
     *
     * El método se ejecuta dentro de la transacción de createVault().
     */
    private static void encryptExistingCredentials(
            Connection connection,
            byte[] key)
            throws SQLException, GeneralSecurityException {

        String selectSql = """
                SELECT id, title, username, password, category
                FROM credentials
                """;

        String updateSql = """
                UPDATE credentials
                SET title = ?, username = ?, password = ?, category = ?
                WHERE id = ?
                """;

        try (PreparedStatement select = connection.prepareStatement(selectSql);
             ResultSet result = select.executeQuery();
             PreparedStatement update = connection.prepareStatement(updateSql)) {

            while (result.next()) {
                int id = result.getInt("id");

                String title = result.getString("title");
                String username = result.getString("username");
                String password = result.getString("password");
                String category = result.getString("category");

                /*
                 * Si aparece un campo que ya tiene el prefijo de cifrado,
                 * detenemos la migración. Esto evita cifrar dos veces datos
                 * que ya estuvieran convertidos por una operación anterior.
                 */
                if (isEncrypted(title) || isEncrypted(username)
                        || isEncrypted(password) || isEncrypted(category)) {
                    throw new SQLException(
                            "Se ha detectado un registro parcialmente cifrado "
                            + "(ID " + id + "). No se ha modificado la bóveda."
                    );
                }

                update.setString(1, encryptString(title, key));
                update.setString(2, encryptString(username, key));
                update.setString(3, encryptString(password, key));
                update.setString(4, encryptString(category, key));
                update.setInt(5, id);
                update.addBatch();
            }

            update.executeBatch();
        }
    }

    // -----------------------------------------------------------------
    // 11. REPRESENTACIÓN DE LOS DATOS CIFRADOS
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Convierte un texto a bytes UTF-8, lo cifra y prepara una representación textual versionada para almacenarlo en SQLite.
    /**
     * Cifra una cadena y la convierte a un formato de texto almacenable
     * en las columnas TEXT que ya utiliza la tabla credentials.
     *
     * Formato:
     * v1:BASE64(nonce):BASE64(texto_cifrado_y_etiqueta)
     */
    private static String encryptString(String value, byte[] key)
            throws GeneralSecurityException {

        String safeValue = value == null ? "" : value;

        byte[] plainBytes = safeValue.getBytes(StandardCharsets.UTF_8);
        EncryptedData encrypted = encryptBytes(plainBytes, key);

        try {
            return ENCRYPTED_PREFIX
                    + Base64.getEncoder().encodeToString(encrypted.nonce())
                    + ":"
                    + Base64.getEncoder().encodeToString(encrypted.cipherText());
        } finally {
            Arrays.fill(plainBytes, (byte) 0);
        }
    }

    // NOTA DIDÁCTICA: Comprueba si un valor tiene el prefijo que identifica el formato cifrado de Vaultix. Sirve para distinguir registros ya migrados de los antiguos.
    /** Comprueba si una cadena utiliza el formato cifrado de Vaultix. */
    private static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENCRYPTED_PREFIX);
    }

    // -----------------------------------------------------------------
    // 12. LIMPIEZA DE CONTRASEÑAS
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Sobrescribe el array de caracteres de la contraseña para reducir el tiempo durante el que permanece en memoria.
    /** Sobrescribe un array de contraseña si no es null. */
    private static void clearPassword(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    // -----------------------------------------------------------------
    // 13. CLASE AUXILIAR PARA RESULTADOS AES-GCM
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Contenedor interno de los elementos necesarios para representar un resultado AES-GCM: nonce y texto cifrado.
    /**
     * Agrupa el nonce y el texto cifrado producido por AES-GCM.
     *
     * Es un record porque solamente transporta dos valores inmutables.
     */
    private record EncryptedData(byte[] nonce, byte[] cipherText) {
    }

    // -----------------------------------------------------------------
    // 14. SESIÓN DESBLOQUEADA DE LA BÓVEDA
    // -----------------------------------------------------------------

    /**
     * Representa una bóveda que ha sido desbloqueada correctamente.
     *
     * La sesión mantiene en memoria la clave AES derivada. El DAO utilizará
     * esta clase para cifrar y descifrar los campos de cada credencial.
     *
     * Cuando el usuario cierre sesión o cierre la aplicación, debe llamarse
     * a close() para sobrescribir la clave que conserva esta sesión.
     *
     * No se debe guardar esta clave en SQLite, en archivos ni en preferencias.
     */
    public static final class VaultSession implements AutoCloseable {

        /** Clave AES mantenida temporalmente en memoria. */
        private byte[] key;

        /** Indica si la sesión ya se ha cerrado. */
        private boolean closed;

        private VaultSession(byte[] key) {
            this.key = Arrays.copyOf(key, key.length);
            this.closed = false;
        }

    // NOTA DIDÁCTICA: Método de la sesión que cifra texto usando la clave de la bóveda actualmente desbloqueada.
        /**
         * Cifra una cadena para almacenarla en SQLite.
         *
         * Cada valor obtiene un nonce aleatorio nuevo.
         *
         * @param value texto original.
         * @return cadena cifrada con el formato v1.
         * @throws GeneralSecurityException si falla AES-GCM.
         */
        public synchronized String encrypt(String value)
                throws GeneralSecurityException {

            ensureOpen();

            String safeValue = value == null ? "" : value;
            byte[] plainBytes = safeValue.getBytes(StandardCharsets.UTF_8);

            try {
                EncryptedData encrypted = encryptBytes(plainBytes, key);

                return ENCRYPTED_PREFIX
                        + Base64.getEncoder().encodeToString(encrypted.nonce())
                        + ":"
                        + Base64.getEncoder().encodeToString(encrypted.cipherText());

            } finally {
                Arrays.fill(plainBytes, (byte) 0);
            }
        }

    // NOTA DIDÁCTICA: Método de la sesión que descifra texto usando la clave de la bóveda actualmente desbloqueada.
        /**
         * Descifra una cadena leída de SQLite.
         *
         * @param storedValue valor cifrado guardado en la base de datos.
         * @return texto original.
         * @throws GeneralSecurityException si el formato, la clave o los datos
         *         no son válidos.
         */
        public synchronized String decrypt(String storedValue)
                throws GeneralSecurityException {

            ensureOpen();

            if (storedValue == null || !storedValue.startsWith(ENCRYPTED_PREFIX)) {
                throw new GeneralSecurityException(
                        "El registro no utiliza un formato cifrado reconocido."
                );
            }

            String[] parts = storedValue.split(":", 3);

            if (parts.length != 3 || !"v1".equals(parts[0])) {
                throw new GeneralSecurityException(
                        "El formato cifrado del registro no es válido."
                );
            }

            byte[] nonce;
            byte[] cipherText;

            try {
                nonce = Base64.getDecoder().decode(parts[1]);
                cipherText = Base64.getDecoder().decode(parts[2]);
            } catch (IllegalArgumentException exception) {
                throw new GeneralSecurityException(
                        "No se pudo interpretar el registro cifrado.",
                        exception
                );
            }

            if (nonce.length != GCM_NONCE_LENGTH) {
                throw new GeneralSecurityException(
                        "El nonce del registro cifrado no tiene una longitud válida."
                );
            }

            byte[] plainBytes = decryptBytes(nonce, cipherText, key);

            try {
                return new String(plainBytes, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plainBytes, (byte) 0);
                Arrays.fill(nonce, (byte) 0);
                Arrays.fill(cipherText, (byte) 0);
            }
        }

    // NOTA DIDÁCTICA: Indica si la sesión criptográfica continúa abierta y puede utilizarse.
        /**
         * Indica si la sesión continúa abierta.
         */
        public synchronized boolean isOpen() {
            return !closed;
        }

    // NOTA DIDÁCTICA: Comprueba que la sesión no se haya cerrado antes de permitir una operación criptográfica.
        /**
         * Comprueba que la sesión no se haya cerrado.
         */
        private void ensureOpen() {
            if (closed || key == null) {
                throw new IllegalStateException(
                        "La bóveda está bloqueada. Inicia sesión de nuevo."
                );
            }
        }

        /**
         * Bloquea la sesión y sobrescribe la clave que mantiene en memoria.
         *
         * Es importante invocar este método al cerrar la aplicación o al
         * cerrar sesión. La limpieza reduce el tiempo durante el que la clave
         * permanece disponible, aunque Java no permite garantizar que no
         * existan copias internas en memoria.
         */
        @Override
    // NOTA DIDÁCTICA: Cierra la sesión y limpia la copia de la clave que mantiene en memoria.
        public synchronized void close() {
            if (!closed && key != null) {
                Arrays.fill(key, (byte) 0);
                key = null;
                closed = true;
            }
        }
    }
}
