package com.safevault;

// -----------------------------------------------------------------------------
// 1. IMPORTACIONES
// -----------------------------------------------------------------------------

// Clases propias de Vaultix: acceso a la base de datos, autenticación y vistas.
import com.safevault.database.DatabaseManager;
import com.safevault.security.AuthenticationManager;
import com.safevault.security.AuthenticationManager.VaultSession;
import com.safevault.view.DashboardView;
import com.safevault.view.LoginView;

// Clases principales de JavaFX para iniciar la aplicación y gestionar ventanas.
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.image.Image;
import javafx.stage.Stage;

// Clases estándar de Java para localizar recursos y gestionar el flujo del icono.
import java.io.InputStream;
import java.net.URL;


/**
 * Clase principal y punto de coordinación de la aplicación Vaultix.
 *
 * Esta clase extiende {@link Application}, que es la clase base que JavaFX
 * exige para iniciar una aplicación de escritorio.
 *
 * RESPONSABILIDADES PRINCIPALES:
 *
 * 1. Arrancar el ciclo de vida de JavaFX.
 * 2. Conservar la ventana principal de la aplicación.
 * 3. Inicializar y comprobar la base de datos local.
 * 4. Determinar si Vaultix se ejecuta por primera vez o si ya existe una bóveda.
 * 5. Mostrar la pantalla de creación de bóveda o la pantalla de inicio de sesión.
 * 6. Coordinar la autenticación con AuthenticationManager.
 * 7. Crear el Dashboard únicamente cuando existe una sesión criptográfica válida.
 * 8. Aplicar la hoja de estilos CSS a las distintas pantallas.
 * 9. Mostrar ventanas emergentes cuando se producen errores importantes.
 * 10. Cerrar correctamente los recursos sensibles al terminar la aplicación.
 *
 * SEPARACIÓN DE RESPONSABILIDADES:
 *
 * SafeVaultDAM no implementa directamente los algoritmos criptográficos ni
 * ejecuta consultas SQL. Para esas tareas delega en AuthenticationManager y
 * DatabaseManager, respectivamente.
 *
 * Del mismo modo, LoginView y DashboardView se encargan de construir sus
 * respectivas interfaces gráficas. Esta clase decide cuándo mostrarlas y cómo
 * conectarlas con la lógica de la aplicación.
 *
 * SEGURIDAD:
 *
 * El Dashboard no se crea hasta que AuthenticationManager devuelve una
 * VaultSession válida. De esta forma, la interfaz que permite consultar o
 * modificar credenciales solo recibe una sesión que ya ha superado el proceso
 * de autenticación.
 */
public class SafeVaultDAM extends Application {

    // -------------------------------------------------------------------------
    // 2. ATRIBUTOS DE LA APLICACIÓN
    // -------------------------------------------------------------------------

    /**
     * Ventana principal de JavaFX.
     *
     * Stage representa la ventana del sistema operativo: contiene la Scene,
     * muestra el título, el icono y los controles de ventana.
     *
     * Se conserva como atributo para poder cambiar su contenido entre LoginView
     * y DashboardView sin crear una ventana nueva para cada pantalla.
     */
    private Stage primaryStage;

    /**
     * Referencia a la pantalla de acceso o configuración inicial.
     *
     * Se conserva mientras esa pantalla está activa. Además de facilitar su
     * gestión, permite liberar la referencia cuando se cierra la aplicación.
     */
    private LoginView loginView;

    /**
     * Referencia al panel principal de gestión de credenciales.
     *
     * Esta referencia permanece a null mientras el usuario no haya iniciado
     * sesión correctamente. Solo se instancia después de obtener una
     * VaultSession válida.
     */
    private DashboardView dashboard;

    /**
     * Sesión criptográfica que representa el acceso autorizado a la bóveda.
     *
     * AuthenticationManager crea esta sesión después de crear la bóveda o
     * autenticar correctamente al usuario.
     *
     * La sesión contiene el material criptográfico necesario para que los
     * componentes autorizados puedan cifrar y descifrar los datos.
     *
     * Debe cerrarse al terminar la aplicación para que la implementación de
     * VaultSession pueda limpiar la clave que mantiene en memoria.
     */
    private VaultSession vaultSession;

    /**
     * Indicador que impide ejecutar más de una vez las tareas de cierre.
     *
     * JavaFX puede notificar el cierre mediante el evento de la ventana y,
     * además, ejecutar el método stop() del ciclo de vida de Application.
     * Ambos caminos llaman a shutdownApplication().
     *
     * Este boolean evita repetir la limpieza de la sesión y de las vistas.
     */
    private boolean shutdownCompleted;


    // -------------------------------------------------------------------------
    // 3. INICIO DEL CICLO DE VIDA DE JAVAFX
    // -------------------------------------------------------------------------

    /**
     * Método de inicio de JavaFX.
     *
     * JavaFX invoca automáticamente este método después de crear la instancia
     * de SafeVaultDAM y preparar el entorno gráfico.
     *
     * El parámetro stage es la ventana principal que JavaFX entrega a la
     * aplicación. No es necesario crear manualmente otro Stage para la ventana
     * principal.
     *
     * ORDEN GENERAL DE ARRANQUE:
     *
     * 1. Guardar la referencia a la ventana.
     * 2. Registrar el procedimiento de cierre.
     * 3. Inicializar y comprobar SQLite.
     * 4. Cargar el icono de la aplicación.
     * 5. Comprobar si existe una bóveda configurada.
     * 6. Mostrar la pantalla correspondiente.
     *
     * Si alguna operación esencial falla, se informa al usuario y no se
     * continúa hacia una pantalla que podría no funcionar correctamente.
     *
     * @param stage ventana principal proporcionada por el entorno JavaFX.
     */
    @Override
    public void start(Stage stage) {

        // Guardamos la ventana recibida para poder utilizarla desde los demás
        // métodos de esta clase, por ejemplo al cambiar de pantalla o mostrar
        // una alerta asociada a la ventana principal.
        primaryStage = stage;

        // Registramos el evento que se produce cuando el usuario pulsa el
        // botón de cerrar de la ventana.
        //
        // setOnCloseRequest recibe una función que se ejecutará cuando se
        // solicite cerrar la ventana. El parámetro event representa el evento
        // de cierre, aunque en este caso no necesitamos consultar sus datos.
        //
        // Antes de que termine la aplicación, llamamos a nuestro método
        // centralizado de limpieza para cerrar la sesión criptográfica.
        primaryStage.setOnCloseRequest(event -> shutdownApplication());

        // El bloque try agrupa las operaciones necesarias para iniciar Vaultix.
        // Si alguna lanza una excepción, el control pasa al bloque catch y se
        // informa del error mediante una ventana emergente.
        try {

            // -----------------------------------------------------------------
            // 3.1. INICIALIZACIÓN Y COMPROBACIÓN DE LA BASE DE DATOS
            // -----------------------------------------------------------------

            // Prepara la base de datos local.
            //
            // La implementación de DatabaseManager se ocupa de las tareas de
            // inicialización que necesita el almacenamiento SQLite, como la
            // creación de las estructuras requeridas si todavía no existen.
            DatabaseManager.initializeDatabase();

            // Comprobamos que la conexión con SQLite funciona realmente.
            //
            // Inicializar la base de datos y comprobar la conexión son tareas
            // relacionadas, pero distintas: la primera prepara el almacenamiento
            // y la segunda verifica que podemos comunicarnos con él.
            //
            // Si la prueba devuelve false, lanzamos una excepción para impedir
            // que la aplicación continúe en un estado incompleto.
            if (!DatabaseManager.testConnection()) {

                // IllegalStateException indica que la aplicación no puede
                // continuar porque uno de sus componentes esenciales no está
                // en el estado esperado.
                throw new IllegalStateException(
                        "No se pudo establecer conexión con la base de datos SQLite."
                );
            }

            // -----------------------------------------------------------------
            // 3.2. CARGA DEL ICONO DE LA APLICACIÓN
            // -----------------------------------------------------------------

            // Obtenemos el icono desde los recursos internos de la aplicación.
            //
            // La ruta /images/icono.png es una ruta del classpath, no una ruta
            // absoluta del ordenador. El archivo debe estar ubicado en:
            //
            // src/main/resources/images/icono.png
            //
            // Maven copia los recursos de src/main/resources al classpath
            // durante el proceso de compilación y empaquetado.
            //
            // getResourceAsStream devuelve un InputStream para leer el recurso.
            // Si el archivo no existe o no se encuentra en esa ruta, devuelve
            // null en lugar de proporcionar un flujo de lectura.
            InputStream iconStream = getClass().getResourceAsStream("/images/icono.png");

            // Comprobamos que el recurso se ha encontrado antes de intentar
            // construir una imagen. Esta comprobación evita pasar null al
            // constructor de Image.
            if (iconStream != null) {

                // try-with-resources cierra automáticamente el InputStream
                // cuando termina el bloque, incluso si se produce una excepción.
                //
                // Image lee el contenido del flujo para construir la imagen.
                // Una vez creada, JavaFX conserva la imagen en el objeto Image,
                // por lo que podemos cerrar el flujo de entrada.
                try (InputStream input = iconStream) {

                    // Construimos el objeto Image a partir del flujo de bytes
                    // del archivo PNG.
                    Image appIcon = new Image(input);

                    // isError permite comprobar si JavaFX ha detectado un
                    // problema al interpretar el contenido de la imagen.
                    //
                    // Solo añadimos el icono si la carga se ha completado bien.
                    if (!appIcon.isError()) {

                        // Stage.getIcons() devuelve la lista de iconos asociados
                        // a la ventana. El sistema operativo puede utilizar esta
                        // imagen en la barra de título, la barra de tareas o el
                        // selector de ventanas, según la plataforma.
                        primaryStage.getIcons().add(appIcon);

                    } else {

                        // Un icono ausente o dañado no debe impedir que Vaultix
                        // arranque. Informamos del problema en la consola y
                        // continuamos con el inicio de la aplicación.
                        System.err.println(
                                "Vaultix: no se pudo interpretar el archivo /images/icono.png."
                        );
                    }
                }

            } else {

                // Si el recurso no existe, dejamos constancia del problema.
                // No lanzamos una excepción porque el icono es un elemento
                // visual secundario y su ausencia no afecta a la seguridad ni
                // al funcionamiento del gestor de contraseñas.
                System.err.println(
                        "Vaultix: no se ha encontrado el recurso /images/icono.png."
                );
            }

            // -----------------------------------------------------------------
            // 3.3. COMPROBACIÓN DEL ESTADO DE LA BÓVEDA
            // -----------------------------------------------------------------

            // Consultamos a AuthenticationManager si ya existe una bóveda
            // configurada.
            //
            // isVaultConfigured() devuelve true cuando la configuración de
            // seguridad de la bóveda ya está creada.
            //
            // firstRun será true cuando todavía no exista esa configuración.
            // El operador ! invierte el resultado: si no está configurada,
            // significa que estamos ante la primera ejecución.
            boolean firstRun = !AuthenticationManager.isVaultConfigured();

            // -----------------------------------------------------------------
            // 3.4. PRESENTACIÓN DE LA PANTALLA INICIAL
            // -----------------------------------------------------------------

            // Según el valor de firstRun, mostraremos:
            //
            // - true: formulario para crear el usuario y la contraseña maestra.
            // - false: formulario para introducir las credenciales existentes.
            //
            // El método showLoginScreen se ocupa de construir la vista,
            // conectarla con la autenticación y mostrarla en la ventana.
            showLoginScreen(firstRun);

        } catch (Exception e) {

            // Este bloque captura los errores producidos durante el arranque.
            //
            // Por ejemplo:
            // - No se puede inicializar SQLite.
            // - No se puede comprobar la conexión.
            // - Se produce un error al consultar el estado de la bóveda.
            //
            // No continuamos hacia el Dashboard porque todavía no se ha
            // completado el proceso de inicialización de la aplicación.
            showStartupError(e);
        }
    }


    // -------------------------------------------------------------------------
    // 4. CONSTRUCCIÓN Y CONFIGURACIÓN DE LA PANTALLA DE ACCESO
    // -------------------------------------------------------------------------

    /**
     * Construye, configura y muestra LoginView.
     *
     * Este método se utiliza tanto cuando Vaultix se ejecuta por primera vez
     * como cuando ya existe una bóveda.
     *
     * La diferencia entre ambos escenarios se controla mediante firstRun:
     *
     * - true: LoginView muestra el formulario de creación inicial.
     * - false: LoginView muestra el formulario de inicio de sesión.
     *
     * También se conecta el botón principal de LoginView con
     * AuthenticationManager mediante un callback.
     *
     * Un callback es una función que se entrega a otro componente para que
     * este pueda ejecutarla cuando ocurra un evento. En este caso, LoginView
     * invoca el callback cuando el usuario envía el formulario.
     *
     * @param firstRun indica si todavía no se ha configurado una bóveda.
     */
    private void showLoginScreen(boolean firstRun) {

        // Creamos una nueva instancia de la vista de acceso.
        // El constructor de LoginView prepara sus controles y sus eventos.
        loginView = new LoginView();

        // Indicamos a LoginView qué modo debe mostrar.
        // La vista adapta sus textos, campos y ayudas según este valor.
        loginView.setFirstRunMode(firstRun);

        // ---------------------------------------------------------------------
        // 4.1. CONEXIÓN DEL FORMULARIO CON LA AUTENTICACIÓN
        // ---------------------------------------------------------------------

        // Registramos la función que LoginView ejecutará cuando el usuario
        // pulse el botón de crear bóveda o desbloquear Vaultix.
        //
        // El callback recibe:
        // - username: nombre de usuario introducido en el formulario.
        // - masterPassword: contraseña maestra como array de caracteres.
        //
        // Se utiliza char[] en lugar de String para permitir que el contenido
        // del array se sobrescriba después de utilizarlo.
        loginView.setOnLogin((username, masterPassword) -> {

            // El bloque try permite capturar posibles errores de creación o
            // autenticación y presentar un mensaje comprensible al usuario.
            try {

                // Si es la primera ejecución, se crea la configuración de la
                // bóveda y se obtiene la primera sesión criptográfica.
                if (firstRun) {

                    // createVault recibe el nombre de usuario y la contraseña
                    // maestra. La implementación de AuthenticationManager se
                    // encarga de crear la configuración segura de la bóveda.
                    vaultSession = AuthenticationManager.createVault(
                            username,
                            masterPassword
                    );

                } else {

                    // Si la bóveda ya existe, no se crea otra configuración.
                    // Se comprueban las credenciales introducidas y, si son
                    // correctas, se devuelve una sesión criptográfica.
                    vaultSession = AuthenticationManager.authenticate(
                            username,
                            masterPassword
                    );
                }

                // -----------------------------------------------------------------
                // 4.2. COMPROBACIÓN DEL RESULTADO DE AUTENTICACIÓN
                // -----------------------------------------------------------------

                // Una sesión null indica que no se ha obtenido una sesión válida.
                // En el caso del inicio de sesión, puede deberse a credenciales
                // incorrectas. En la creación inicial indica que no se ha podido
                // completar el proceso de configuración.
                if (vaultSession == null) {

                    // Si estamos en el inicio de sesión normal, mostramos el
                    // error dentro de la propia pantalla de LoginView.
                    //
                    // De esta forma, el usuario recibe una indicación inmediata
                    // junto al formulario, sin que se abra una ventana modal.
                    // Aprovechamos el método showError() que ya está implementado
                    // en LoginView y que aplica el estilo visual rojo definido
                    // en safevault.css.
                    if (!firstRun) {

                        loginView.showError(
                                "El usuario o la contraseña maestra no son correctos."
                        );

                    } else {

                        // Durante la configuración inicial, un resultado null
                        // significa que no se ha podido crear la bóveda. En ese
                        // caso conservamos la alerta emergente para informar
                        // del problema de creación.
                        showAuthenticationError(
                                "No se ha podido crear la bóveda. Comprueba los datos e inténtalo de nuevo."
                        );
                    }

                    // return termina la ejecución del callback. No se debe
                    // continuar hacia showDashboard() sin una sesión válida.
                    return;
                }

                // -----------------------------------------------------------------
                // 4.3. ACCESO AL PANEL PRINCIPAL
                // -----------------------------------------------------------------

                // Solo llegamos aquí cuando AuthenticationManager ha devuelto
                // una sesión válida. A partir de este momento podemos mostrar
                // el panel que permite gestionar las credenciales.
                showDashboard();

            } catch (Exception e) {

                // Si se produce una excepción, comprobamos si se llegó a crear
                // una sesión antes de que ocurriera el error.
                //
                // dashboard == null indica que todavía no se ha completado la
                // transición al panel principal.
                //
                // En ese caso cerramos la sesión para no dejar una clave
                // criptográfica innecesariamente activa en memoria.
                if (vaultSession != null && dashboard == null) {

                    // close() delega en VaultSession la limpieza de los
                    // recursos sensibles que mantiene.
                    vaultSession.close();

                    // Eliminamos nuestra referencia a la sesión ya cerrada.
                    vaultSession = null;
                }

                // Presentamos un mensaje general y añadimos una descripción
                // segura del error. safeErrorMessage evita mostrar una cadena
                // vacía cuando la excepción no contiene un mensaje.
                showAuthenticationError(
                        "No se ha podido completar la operación de seguridad.\n\n"
                        + safeErrorMessage(e)
                );
            }
        });

        // ---------------------------------------------------------------------
        // 4.4. CREACIÓN DE LA ESCENA Y CONFIGURACIÓN DE LA VENTANA
        // ---------------------------------------------------------------------

        // En JavaFX, una Scene contiene el árbol de nodos que forman una
        // pantalla. En este caso, getView() devuelve el nodo raíz de LoginView.
        //
        // Los valores 520 y 680 representan el ancho y alto iniciales de la
        // escena en píxeles lógicos.
        Scene loginScene = new Scene(loginView.getView(), 520, 680);

        // Aplicamos la hoja CSS común para que LoginView mantenga la identidad
        // visual de Vaultix.
        applyStylesheet(loginScene);

        // El título cambia para indicar si el usuario está creando la bóveda
        // por primera vez o accediendo a una bóveda ya existente.
        primaryStage.setTitle("Vaultix | " + (firstRun
                ? "Configuración inicial"
                : "Inicio de sesión"));

        // Establecemos las dimensiones mínimas de la ventana. El usuario podrá
        // ampliarla, pero no reducirla por debajo de estos valores.
        primaryStage.setMinWidth(420);
        primaryStage.setMinHeight(560);

        // Asignamos la escena de acceso a la ventana principal.
        primaryStage.setScene(loginScene);

        // Centramos la ventana en la pantalla disponible.
        primaryStage.centerOnScreen();

        // Hacemos visible la ventana.
        primaryStage.show();
    }


    // -------------------------------------------------------------------------
    // 5. CONSTRUCCIÓN Y PRESENTACIÓN DEL DASHBOARD
    // -------------------------------------------------------------------------

    /**
     * Crea y muestra el panel principal de Vaultix.
     *
     * Este método solo debe ejecutarse después de una autenticación correcta.
     * Antes de construir el Dashboard se comprueba que existe una sesión
     * criptográfica y que sigue abierta.
     *
     * La sesión se pasa al constructor de DashboardView. De esta manera, la
     * vista y los objetos de acceso a datos que utilice pueden operar sobre
     * la bóveda autenticada.
     */
    private void showDashboard() {

        // Comprobación de seguridad antes de permitir el acceso al Dashboard.
        //
        // Se verifican dos condiciones:
        // 1. vaultSession no puede ser null.
        // 2. La sesión debe seguir abierta.
        //
        // El operador || significa OR lógico: si cualquiera de las dos
        // condiciones se cumple, no se permite continuar.
        if (vaultSession == null || !vaultSession.isOpen()) {

            // Informamos al usuario mediante una ventana emergente y
            // permanecemos fuera del panel de credenciales.
            showAuthenticationError(
                    "No existe una sesión segura activa. Vuelve a iniciar sesión."
            );
            return;
        }

        // Creamos el Dashboard pasando la sesión autenticada.
        //
        // La inyección de la sesión consiste en entregarla como dependencia
        // al constructor de la vista, en vez de crear otra sesión dentro del
        // Dashboard. Así se utiliza exactamente la sesión validada.
        dashboard = new DashboardView(vaultSession);

        // Creamos la Scene que contendrá el nodo raíz del Dashboard.
        // Sus dimensiones iniciales son mayores porque el panel principal
        // necesita espacio para mostrar las credenciales y sus controles.
        Scene dashboardScene = new Scene(
                dashboard.getView(),
                1200,
                760
        );

        // Aplicamos el mismo CSS que utiliza la pantalla de acceso.
        applyStylesheet(dashboardScene);

        // Actualizamos el título para identificar la pantalla principal.
        primaryStage.setTitle("Vaultix | Gestor de contraseñas");

        // Establecemos el tamaño mínimo permitido para el Dashboard.
        primaryStage.setMinWidth(850);
        primaryStage.setMinHeight(600);

        // Sustituimos la escena de acceso por la escena del Dashboard.
        // Se reutiliza la misma ventana principal.
        primaryStage.setScene(dashboardScene);

        // Centramos y mostramos la ventana con su nuevo contenido.
        primaryStage.centerOnScreen();
        primaryStage.show();
    }


    // -------------------------------------------------------------------------
    // 6. APLICACIÓN DE LA HOJA DE ESTILOS CSS
    // -------------------------------------------------------------------------

    /**
     * Añade la hoja de estilos de Vaultix a una escena.
     *
     * JavaFX permite separar la estructura de los controles y su apariencia.
     * La estructura se construye en las clases Java y los estilos visuales se
     * definen en el archivo CSS.
     *
     * Este método centraliza la carga del CSS para evitar repetir el mismo
     * código en LoginView y DashboardView.
     *
     * @param scene escena a la que se añadirá la hoja de estilos.
     */
    private void applyStylesheet(Scene scene) {

        // Buscamos el archivo CSS dentro de los recursos de la aplicación.
        //
        // La ruta /css/safevault.css corresponde a:
        // src/main/resources/css/safevault.css
        //
        // getResource devuelve una URL si encuentra el recurso y null si no
        // existe en el classpath.
        URL cssResource = getClass().getResource("/css/safevault.css");

        // Si no se encuentra el CSS, lanzamos una excepción. En este proyecto
        // la hoja de estilos forma parte de la presentación principal y su
        // ausencia indica que los recursos no están correctamente preparados.
        if (cssResource == null) {

            throw new IllegalStateException(
                    "No se encuentra safevault.css. Comprueba que está en "
                    + "src/main/resources/css/"
            );
        }

        // Convertimos la URL a una cadena en formato URI/URL que JavaFX pueda
        // utilizar para cargar la hoja de estilos.
        //
        // getStylesheets() devuelve la lista de hojas CSS asociadas a la Scene.
        scene.getStylesheets().add(cssResource.toExternalForm());
    }


    // -------------------------------------------------------------------------
    // 7. VENTANAS EMERGENTES DE ERROR
    // -------------------------------------------------------------------------

    /**
     * Muestra una alerta de error relacionada con la autenticación.
     *
     * Este método utiliza Alert de JavaFX, que crea una ventana emergente
     * estándar con un icono y un botón de cierre adecuados al tipo de mensaje.
     *
     * Se utiliza cuando no se puede crear la bóveda o cuando las credenciales
     * no permiten abrir una sesión.
     *
     * @param message texto que se mostrará en el contenido de la alerta.
     */
    private void showAuthenticationError(String message) {

        // Creamos una alerta de tipo ERROR.
        // JavaFX asigna automáticamente el icono y el comportamiento visual
        // propios de una notificación de error.
        Alert alert = new Alert(Alert.AlertType.ERROR);

        // Título de la ventana emergente.
        alert.setTitle("Vaultix | Seguridad");

        // Encabezado principal que resume el problema.
        alert.setHeaderText("No se ha podido acceder a la bóveda");

        // Texto detallado que explica al usuario qué ha sucedido.
        alert.setContentText(message);

        // Asociamos la alerta con la ventana principal.
        // Esto permite que JavaFX la trate como una ventana secundaria de
        // primaryStage y la sitúe de forma coherente con ella.
        alert.initOwner(primaryStage);

        // showAndWait() muestra la alerta y espera a que el usuario la cierre.
        // Es una ventana modal: mientras permanece abierta, el usuario no
        // puede interactuar con la ventana propietaria.
        alert.showAndWait();
    }


    /**
     * Muestra un error producido durante el arranque de Vaultix.
     *
     * Si no se puede inicializar SQLite o consultar el estado de la bóveda,
     * no es seguro continuar mostrando el formulario de acceso.
     *
     * Por ese motivo se informa del error y se finaliza la aplicación.
     *
     * @param exception excepción que contiene información del error.
     */
    private void showStartupError(Exception exception) {

        // Creamos una ventana emergente de tipo ERROR.
        Alert alert = new Alert(Alert.AlertType.ERROR);

        // Definimos el título y el encabezado de la alerta.
        alert.setTitle("Vaultix | Error de inicio");
        alert.setHeaderText("No se ha podido iniciar Vaultix");

        // Explicamos el problema de forma comprensible y añadimos el mensaje
        // disponible de la excepción.
        alert.setContentText(
                "Comprueba que la base de datos local está disponible.\n\n"
                + safeErrorMessage(exception)
        );

        // Mostramos la alerta y esperamos a que el usuario la cierre.
        alert.showAndWait();

        // Después de informar del problema, solicitamos el cierre de JavaFX.
        // No continuamos con una aplicación que no ha podido inicializar uno
        // de sus componentes esenciales.
        Platform.exit();
    }


    // -------------------------------------------------------------------------
    // 8. TRATAMIENTO DE MENSAJES DE EXCEPCIÓN
    // -------------------------------------------------------------------------

    /**
     * Devuelve un mensaje de error legible.
     *
     * Una excepción puede no incluir un mensaje descriptivo. Este método
     * comprueba esa posibilidad para evitar mostrar null o una cadena vacía
     * en las ventanas de error.
     *
     * @param throwable excepción o error del que se quiere obtener el mensaje.
     * @return mensaje de la excepción o un texto genérico si no existe.
     */
    private String safeErrorMessage(Throwable throwable) {

        // Comprobamos tres situaciones:
        // - throwable == null: no se ha recibido una excepción.
        // - getMessage() == null: la excepción no contiene mensaje.
        // - isBlank(): el mensaje está vacío o solo contiene espacios.
        //
        // El operador || significa que basta con que una de las condiciones
        // sea verdadera para devolver el mensaje genérico.
        if (throwable == null
                || throwable.getMessage() == null
                || throwable.getMessage().isBlank()) {

            return "Se ha producido un error inesperado.";
        }

        // Si existe un mensaje útil, lo devolvemos para que pueda mostrarse
        // en la alerta correspondiente.
        return throwable.getMessage();
    }


    // -------------------------------------------------------------------------
    // 9. CIERRE Y LIBERACIÓN DE RECURSOS
    // -------------------------------------------------------------------------

    /**
     * Ejecuta las tareas necesarias para cerrar Vaultix de forma controlada.
     *
     * La tarea más importante es cerrar la sesión criptográfica activa.
     * De este modo se solicita a VaultSession que libere los recursos sensibles
     * que mantiene en memoria.
     *
     * El método se invoca desde:
     * - El evento de cierre de la ventana principal.
     * - El método stop() del ciclo de vida de JavaFX.
     *
     * El indicador shutdownCompleted evita repetir el proceso.
     */
    private void shutdownApplication() {

        // Si la limpieza ya se realizó, no hay que repetirla.
        // return termina inmediatamente el método.
        if (shutdownCompleted) {
            return;
        }

        // Marcamos la limpieza como realizada antes de cerrar los recursos.
        // Así evitamos que una segunda llamada entre de nuevo en el proceso.
        shutdownCompleted = true;

        // Si el Dashboard llegó a crearse, delegamos en él su procedimiento
        // de cierre. Según la implementación de DashboardView, este método
        // también cierra la VaultSession que recibió en el constructor.
        if (dashboard != null) {

            // Cerramos los recursos que administra el Dashboard.
            dashboard.shutdown();

            // Liberamos la referencia al Dashboard.
            dashboard = null;

            // El Dashboard ya ha cerrado la sesión compartida; eliminamos
            // también la referencia mantenida por esta clase.
            vaultSession = null;

        } else if (vaultSession != null) {

            // Si no llegó a crearse el Dashboard, la sesión sigue siendo
            // responsabilidad directa de SafeVaultDAM.
            // Por tanto, la cerramos aquí.
            vaultSession.close();

            // Eliminamos la referencia a la sesión ya cerrada.
            vaultSession = null;
        }

        // Liberamos la referencia a la pantalla de acceso.
        // Java podrá recuperar sus objetos cuando ya no existan otras
        // referencias a ellos.
        loginView = null;
    }


    // -------------------------------------------------------------------------
    // 10. FINALIZACIÓN DEL CICLO DE VIDA DE JAVAFX
    // -------------------------------------------------------------------------

    /**
     * Método de parada del ciclo de vida de JavaFX.
     *
     * JavaFX invoca stop() cuando la aplicación está terminando.
     *
     * Se utiliza como segunda garantía para ejecutar la limpieza, incluso si
     * la aplicación finaliza por una vía distinta al botón de cierre de la
     * ventana.
     *
     * Como shutdownApplication() comprueba shutdownCompleted, no se repiten
     * las operaciones si ya se ejecutaron desde el evento de cierre.
     */
    @Override
    public void stop() {

        // Reutilizamos el procedimiento centralizado de cierre.
        shutdownApplication();
    }


    // -------------------------------------------------------------------------
    // 11. MÉTODO PRINCIPAL DE JAVA
    // -------------------------------------------------------------------------

    /**
     * Método main: punto de entrada convencional de la aplicación Java.
     *
     * La llamada a launch(args) inicia el entorno JavaFX.
     *
     * JavaFX crea la instancia de SafeVaultDAM y, posteriormente, invoca sus
     * métodos del ciclo de vida, entre ellos start() y stop().
     *
     * @param args argumentos recibidos desde la línea de comandos.
     */
    public static void main(String[] args) {

        // Application.launch inicia el ciclo de vida de JavaFX.
        // No se crea manualmente una instancia de SafeVaultDAM porque JavaFX
        // se encarga de construirla y gestionar su ejecución.
        launch(args);
    }
}
