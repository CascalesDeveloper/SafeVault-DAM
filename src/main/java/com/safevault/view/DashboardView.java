
package com.safevault.view;


/*
 * DOCUMENTACIÓN COMPLEMENTARIA PARA EL ESTUDIO DE VAULTIX
 *
 * DashboardView es la vista principal después de desbloquear la bóveda.
 * Presenta las credenciales y recoge las acciones del usuario, pero delega
 * la persistencia y el cifrado en CredentialDAO.
 *
 * Elementos arquitectónicos:
 * - JavaFX construye y actualiza la interfaz.
 * - ObservableList mantiene sincronizados los datos y la tabla.
 * - CredentialDAO realiza las operaciones CRUD.
 * - VaultSession autoriza temporalmente el cifrado y descifrado.
 * - Timeline controla el borrado temporizado del portapapeles.
 *
 * La vista debe evitar mostrar una lista vacía como si todo estuviera bien
 * cuando falla la lectura o el descifrado de la base de datos.
 */

// ============================================================
// IMPORTACIONES
// ============================================================

// Clases para controlar animaciones y temporizadores.
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;

// Propiedades observables de JavaFX para actualizar la tabla.
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

// Colecciones observables que permiten actualizar la interfaz.
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

// Clases para distribuir los componentes visuales.
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.util.Duration;

// Utilidades de Java.
import java.util.List;
import java.time.Year;

// Generación segura de contraseñas.
import java.security.SecureRandom;

// Acceso a la capa de persistencia SQLite.
import com.safevault.database.CredentialDAO;
import com.safevault.security.AuthenticationManager.VaultSession;
import java.sql.SQLException;
import java.util.Objects;


// ============================================================
// CLASE PRINCIPAL DE LA INTERFAZ
// ============================================================

/**
 * Vista principal de Vaultix.
 *
 * Esta clase construye y coordina la interfaz gráfica mediante JavaFX.
 * Presenta las credenciales, recoge las acciones del usuario y solicita
 * las operaciones de persistencia al objeto CredentialDAO.
 *
 * La vista no ejecuta directamente sentencias SQL: esa responsabilidad
 * corresponde a la capa de acceso a datos.
 *
 * Esta versión utiliza exclusivamente SQLite. Se ha retirado la selección
 * entre SQLite y MariaDB para simplificar la aplicación y mantener un
 * único origen de datos.
 *
 * El acceso a las credenciales requiere una VaultSession previamente
 * autenticada. El DAO utiliza esa sesión para cifrar y descifrar los datos.
 * El Dashboard no conserva la contraseña maestra: utiliza únicamente la
 * sesión temporal que recibe desde la clase principal.
 */
public class DashboardView {

    // ========================================================
    // 1. COMPONENTES PRINCIPALES
    // ========================================================

    // Contenedor principal de toda la aplicación.
    private final BorderPane root = new BorderPane();

    // Tabla donde se mostrarán las credenciales almacenadas.
    private final TableView<CredentialRow> table =
            new TableView<>();

    // Campo de búsqueda de credenciales.
    private final TextField search = new TextField();

    /*
     * Vaultix utiliza SQLite como único sistema de almacenamiento.
     * Por ese motivo no se necesita un selector de motores de base de datos.
     * Todas las operaciones de persistencia se realizan mediante
     * CredentialDAO.
     */

    // Etiquetas del panel lateral de detalles.
    private final Label detailTitle =
            new Label("Selecciona una credencial");

    private final Label detailUser =
            new Label("—");

    private final Label detailPassword =
            new Label("—");

    private final Label detailCategory =
            new Label("—");

    // Botones para copiar información. La contraseña nunca se muestra en claro.
    private final Button copyPasswordButton =
            new Button("Copiar contraseña");

    private final Button copyUserButton =
            new Button("Copiar usuario");

    // Elementos visuales del temporizador del portapapeles.
    private final ProgressBar clipboardProgress =
            new ProgressBar(0);

    private final Label clipboardStatus =
            new Label("Portapapeles inactivo");

    // Temporizador que controla el borrado automático.
    private Timeline clipboardTimeline;

    // Último contenido copiado por la aplicación.
    private String lastCopiedValue;

    // Tiempo de permanencia de la información en el portapapeles.
    private static final int CLIPBOARD_SECONDS = 20;

    // Credencial actualmente seleccionada.
    private CredentialRow selectedCredential;



    // ========================================================
    // 2. MODELO DE DATOS Y CONEXIÓN CON EL DAO
    // ========================================================

    /*
     * ObservableList:
     * Colección de credenciales utilizada por la interfaz.
     *
     * Sus cambios se reflejan en los componentes de JavaFX.
     */
    private final ObservableList<CredentialRow> credentials =
            FXCollections.observableArrayList();

    /*
     * CredentialDAO:
     * Objeto responsable de acceder a la base de datos.
     *
     * Se encarga de las operaciones SQL:
     * - Consultar credenciales.
     * - Insertar credenciales.
     * - Modificar credenciales.
     * - Eliminar credenciales.
     */
    /*
     * Objeto de acceso a datos (DAO).
     *
     * Centraliza las operaciones de persistencia:
     * - findAll(): recupera todas las credenciales.
     * - insert(): almacena una nueva credencial.
     * - update(): modifica una credencial existente.
     * - delete(): elimina una credencial por su identificador.
     *
     * La vista utiliza este objeto sin tener que conocer los detalles
     * internos de las consultas SQL ni de la conexión SQLite.
     */
    /*
     * Sesión autenticada de Vaultix.
     *
     * Contiene temporalmente la clave AES que permite al DAO descifrar
     * las credenciales al leerlas y cifrarlas al guardarlas.
     * La clave nunca se obtiene directamente de SQLite.
     */
    private final VaultSession vaultSession;

    /*
     * DAO asociado exclusivamente a la sesión autenticada.
     * Ya no se permite crear un DAO sin una bóveda desbloqueada.
     */
    private final CredentialDAO credentialDAO;

    /*
     * Evita ejecutar dos veces la limpieza de recursos si se pulsa Salir
     * y después JavaFX invoca también el cierre de la aplicación.
     */
    private boolean shutdownCompleted;

    // Categorías disponibles en la aplicación.
    private final List<String> categories = List.of(
            "Personal",
            "Trabajo",
            "Finanzas",
            "Internet",
            "Correo",
            "Otros"
    );


    // ========================================================
    // 3. CONSTRUCTOR
    // ========================================================

    /**
     * Constructor de la vista.
     *
     * Primero se construyen los componentes gráficos y se organizan
     * dentro del contenedor principal. A continuación se recuperan
     * desde SQLite las credenciales que ya estén almacenadas.
     *
     * Como solo existe un motor de base de datos, no hace falta
     * registrar eventos para cambiar entre distintos sistemas.
     *
     * La sesión autenticada se recibe desde SafeVaultDAM. El Dashboard
     * no crea su propio acceso a los datos ni se abre antes del login.
     *
     * @param vaultSession sesión válida obtenida desde AuthenticationManager.
     */
    public DashboardView(VaultSession vaultSession) {

        /*
         * La vista necesita una sesión válida. Así impedimos que el
         * Dashboard se abra antes de autenticar al usuario principal.
         */
        this.vaultSession = Objects.requireNonNull(
                vaultSession,
                "DashboardView necesita una sesión autenticada."
        );

        if (!vaultSession.isOpen()) {
            throw new IllegalStateException(
                    "No se puede abrir el Dashboard con la bóveda bloqueada."
            );
        }

        /*
         * El DAO recibe la sesión autenticada. Todas las operaciones CRUD
         * utilizarán esta sesión para cifrar y descifrar los cuatro campos.
         */
        this.credentialDAO = new CredentialDAO(vaultSession);

        // Construimos primero los componentes gráficos.
        createInterface();

        // Solo después de validar la sesión cargamos los datos descifrados.
        loadCredentialsFromDatabase();
    }


    // Devuelve el contenedor principal para mostrarlo en la ventana.
    // NOTA DIDÁCTICA: Devuelve el nodo raíz que la clase principal colocará en la ventana.
    public Parent getView() {
        return root;
    }


    // ========================================================
    // 4. CARGA DE CREDENCIALES DESDE SQLITE
    // ========================================================

    // NOTA DIDÁCTICA: Solicita al DAO las credenciales, que se descifran antes de mostrarse. Ante un error, la aplicación debe fallar de forma segura y no presentar una bóveda aparentemente vacía.
    /**
     * Recupera todas las credenciales almacenadas en SQLite.
     *
     * CredentialDAO encapsula el acceso a la base de datos. Su método
     * findAll() devuelve los registros y ObservableList permite que
     * JavaFX trabaje con la colección de datos.
     *
     * Si la consulta falla, se vacía la colección para evitar mostrar
     * información antigua, se informa al usuario mediante una ventana
     * emergente y se imprime la excepción en la consola para facilitar
     * el diagnóstico durante el desarrollo.
     */
    private void loadCredentialsFromDatabase() {
        try {
            // Recuperamos y desciframos las credenciales antes de mostrar el Dashboard.
            // Si el DAO no puede descifrar los datos, no debemos continuar como si
            // la bóveda estuviera vacía: podría ocultar un problema de integridad.
            List<CredentialRow> loadedCredentials = credentialDAO.findAll();

            // Solo sustituimos la colección cuando la lectura completa ha terminado.
            credentials.setAll(loadedCredentials);

            System.out.println(
                    "Vaultix: credenciales recuperadas de SQLite: "
                    + credentials.size()
            );

        } catch (SQLException e) {
            /*
             * Fallo cerrado:
             * si SQLite falla o algún registro no puede descifrarse, impedimos
             * que se abra el Dashboard con una lista aparentemente vacía.
             *
             * La excepción llega al SafeVaultDAM, que cierra la VaultSession
             * y mantiene al usuario fuera de la bóveda hasta resolver el error.
             */
            credentials.clear();

            throw new IllegalStateException(
                    "No se pudieron cargar y descifrar las credenciales. "
                    + "La bóveda no se abrirá para proteger tus datos.",
                    e
            );
        }
    }


    // ========================================================
    // 5. CONSTRUCCIÓN DE LA INTERFAZ
    // ========================================================
    // NOTA DIDÁCTICA: Crea los controles de registro e inicio de sesión y los organiza según el modo activo.

    private void createInterface() {

        // Aplicamos la clase CSS principal.
        root.getStyleClass().add("safevault-root");

        // Construimos la parte superior de la aplicación.
        // La cabecera reúne la identidad de Vaultix y las acciones
        // principales en una sola fila. Sustituye el menú clásico por
        // botones directos, manteniendo las operaciones ya existentes.
        HBox top = createTopBar();

        // Distribuimos los componentes en el BorderPane.
        root.setTop(top);
        root.setLeft(createSidebar());
        root.setCenter(createMainContent());
        root.setRight(createDetailsPanel());

        root.setPadding(Insets.EMPTY);
    }


    // ========================================================
    // 6. MENÚ SUPERIOR
    // ========================================================

    // NOTA DIDÁCTICA: Construye la cabecera y conecta los botones principales con las operaciones de gestión de credenciales y herramientas.
    /**
     * Construye la cabecera principal de Vaultix.
     *
     * La antigua barra de menús se sustituye por botones visibles.
     * Las acciones siguen llamando a los métodos ya existentes, por lo
     * que no se duplica la lógica de creación, edición, borrado ni seguridad.
     *
     * @return contenedor horizontal que se coloca en la parte superior.
     */
    private HBox createTopBar() {

        // Nombre y descriptor de la aplicación.
        Label brandName = new Label("VAULTIX");
        brandName.getStyleClass().add("vaultix-brand");

        Label brandSubtitle = new Label("SECURE PASSWORD MANAGER");
        brandSubtitle.getStyleClass().add("vaultix-brand-subtitle");

        VBox brand = new VBox(2, brandName, brandSubtitle);
        brand.getStyleClass().add("vaultix-brand-block");

        // Grupo de acciones para administrar credenciales.
        Button newButton = createTopBarButton("＋  Nueva", "topbar-primary");
        newButton.setTooltip(new Tooltip("Crear una nueva credencial"));
        newButton.setOnAction(event -> createCredential());

        Button editButton = createTopBarButton("Editar", "topbar-button");
        editButton.setTooltip(new Tooltip("Editar la credencial seleccionada"));
        editButton.setOnAction(event -> editCredential());

        Button deleteButton = createTopBarButton("Eliminar", "topbar-danger");
        deleteButton.setTooltip(new Tooltip("Eliminar la credencial seleccionada"));
        deleteButton.setOnAction(event -> deleteCredential());

        // Separador visual entre la gestión de cuentas y las herramientas.
        Region firstSeparator = new Region();
        firstSeparator.getStyleClass().add("topbar-separator");

        // Grupo de herramientas de Vaultix.
        Button generatorButton =
                createTopBarButton("Generador", "topbar-button");
        generatorButton.setTooltip(new Tooltip("Generar una contraseña segura"));
        generatorButton.setOnAction(event -> generatePassword());

        Button securityButton =
                createTopBarButton("Seguridad", "topbar-button");
        securityButton.setTooltip(
                new Tooltip("Analizar la seguridad de las contraseñas"));
        securityButton.setOnAction(event -> analyzePasswordSecurity());

        Button notesButton =
                createTopBarButton("Notas", "topbar-button");
        notesButton.setTooltip(new Tooltip("Consultar Notas seguras"));
        notesButton.setOnAction(event -> showNotesInformation());

        // El espacio flexible empuja el botón Salir al extremo derecho.
        Region flexibleSpace = new Region();
        HBox.setHgrow(flexibleSpace, Priority.ALWAYS);

        Button exitButton = createTopBarButton("Salir", "topbar-button");
        exitButton.setTooltip(new Tooltip("Cerrar Vaultix"));
        exitButton.setOnAction(event -> {
            // Liberamos el temporizador y limpiamos el portapapeles
            // si todavía contiene un dato copiado por esta aplicación.
            shutdown();

            /*
             * Solicitamos a JavaFX que cierre la aplicación de forma ordenada.
             * SafeVaultDAM.stop() también podrá ejecutarse para completar
             * el ciclo de vida normal de la aplicación.
             */
            Platform.exit();
        });

        // Componemos todos los elementos en una única fila horizontal.
        HBox topBar = new HBox(
                9,
                brand,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                newButton,
                editButton,
                deleteButton,
                firstSeparator,
                generatorButton,
                securityButton,
                notesButton,
                flexibleSpace,
                exitButton
        );

        topBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        topBar.getStyleClass().add("vaultix-topbar");

        return topBar;
    }


    // NOTA DIDÁCTICA: Crea un botón homogéneo y le asigna la clase CSS que determina su presentación.
    /**
     * Crea un botón de la cabecera y le asigna una clase CSS.
     *
     * Centralizar su creación mantiene una altura y un comportamiento
     * coherentes. Los colores, bordes y estados hover se definen en CSS,
     * separando la presentación de la lógica de la aplicación.
     *
     * @param text texto que verá el usuario.
     * @param styleClass clase CSS que define el aspecto del botón.
     * @return botón preparado para recibir su acción.
     */
    private Button createTopBarButton(String text, String styleClass) {

        Button button = new Button(text);

        // Todos los botones de la cabecera comparten la misma altura.
        button.setMinHeight(34);
        button.setPrefHeight(34);
        button.setFocusTraversable(true);

        // La clase CSS permite distinguir acciones normales,
        // la acción principal y la acción destructiva.
        button.getStyleClass().add(styleClass);

        return button;
    }


    // IDENTIDAD VISUAL DE LAS CATEGORÍAS
    // ========================================================

    // NOTA DIDÁCTICA: Devuelve el símbolo visual asociado a una categoría para facilitar su identificación.
    /**
     * Devuelve un símbolo sencillo para identificar visualmente cada categoría.
     * Se utilizan caracteres tipográficos para evitar depender de imágenes o
     * de paquetes de iconos externos.
     */
    private String getCategoryIcon(String category) {
        if (category == null) {
            return "◆";
        }

        return switch (category.trim().toLowerCase()) {
            case "todas las credenciales" -> "▦";
            case "personal" -> "♙";
            case "trabajo" -> "▣";
            case "finanzas" -> "€";
            case "internet" -> "◎";
            case "correo" -> "✉";
            default -> "◆";
        };
    }

    // NOTA DIDÁCTICA: Selecciona el color asociado a la categoría, manteniendo una codificación visual consistente.
    /**
     * Devuelve el color principal asociado a una categoría.
     * La paleta utiliza tonos moderados para mantener el aspecto sobrio de Vaultix.
     */
    private String getCategoryColor(String category) {
        if (category == null) {
            return "#7B8794";
        }

        return switch (category.trim().toLowerCase()) {
            case "personal" -> "#5B9BE6";
            case "trabajo" -> "#A78BDB";
            case "finanzas" -> "#55A77A";
            case "internet" -> "#D69A45";
            case "correo" -> "#4CAEB2";
            case "todas las credenciales" -> "#9AA8B5";
            default -> "#8995A3";
        };
    }

    // NOTA DIDÁCTICA: Prepara el estilo visual de la etiqueta de categoría a partir de su color.
    /**
     * Construye el estilo de la etiqueta de categoría que aparece en la tabla
     * y en el panel de detalles. Cada categoría tiene fondo y texto coordinados.
     */
    private String getCategoryBadgeStyle(String category) {
        String background;
        String foreground;

        if (category == null) {
            background = "#303943";
            foreground = "#D5DCE3";
        } else {
            switch (category.trim().toLowerCase()) {
                case "personal" -> {
                    background = "#243B55";
                    foreground = "#A9D0FF";
                }
                case "trabajo" -> {
                    background = "#3A3150";
                    foreground = "#D0BDF5";
                }
                case "finanzas" -> {
                    background = "#244536";
                    foreground = "#A8E0BC";
                }
                case "internet" -> {
                    background = "#4A3925";
                    foreground = "#F0C78E";
                }
                case "correo" -> {
                    background = "#244448";
                    foreground = "#A5E2E3";
                }
                default -> {
                    background = "#303943";
                    foreground = "#D5DCE3";
                }
            }
        }

        return "-fx-background-color: " + background + ";"
                + " -fx-text-fill: " + foreground + ";"
                + " -fx-background-radius: 5px;"
                + " -fx-font-size: 11px;";
    }


    // ========================================================
    // 8. PANEL LATERAL DE CATEGORÍAS
    // ========================================================
    // NOTA DIDÁCTICA: Construye el panel lateral de navegación y herramientas.

    private VBox createSidebar() {

        Label heading = new Label("MI BÓVEDA");
        heading.getStyleClass().add("sidebar-title");

        // Nodo raíz del árbol de categorías.
        TreeItem<String> all =
                new TreeItem<>("Todas las credenciales");

        TreeItem<String> personal =
                new TreeItem<>("Personal");

        TreeItem<String> work =
                new TreeItem<>("Trabajo");

        TreeItem<String> finance =
                new TreeItem<>("Finanzas");

        TreeItem<String> internet =
                new TreeItem<>("Internet");

        TreeItem<String> email =
                new TreeItem<>("Correo");

        // La categoría Otros también debe estar disponible en el menú.
        TreeItem<String> others =
                new TreeItem<>("Otros");

        all.getChildren().addAll(
                personal,
                work,
                finance,
                internet,
                email,
                others
        );

        all.setExpanded(true);

        // Árbol de navegación. Cada categoría tendrá un símbolo propio
        // y un color discreto para reconocerla de un vistazo.
        TreeView<String> tree = new TreeView<>(all);

        tree.setShowRoot(true);
        tree.setMaxWidth(Double.MAX_VALUE);

        // Personalizamos las celdas sin modificar los valores del árbol.
        // De esta forma, los filtros siguen utilizando los nombres originales.
        tree.setCellFactory(view -> new TreeCell<>() {

            @Override
    // NOTA DIDÁCTICA: Actualiza el elemento visual correspondiente para reflejar el estado o selección actual.
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                Label icon = new Label(getCategoryIcon(item));
                icon.setMinWidth(22);
                icon.setStyle(
                        "-fx-text-fill: " + getCategoryColor(item) + ";"
                        + " -fx-font-size: 14px;"
                        + " -fx-alignment: CENTER;"
                );

                Label name = new Label(item);
                name.getStyleClass().add("sidebar-item");

                HBox content = new HBox(9, icon, name);
                content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

                setText(null);
                setGraphic(content);
            }
        });

        // Al seleccionar una categoría filtramos la tabla.
        tree.getSelectionModel()
                .selectedItemProperty()
                .addListener((obs, oldValue, newValue) -> {

                    if (newValue == null) {
                        return;
                    }

                    if (newValue == all) {
                        search.clear();
                    } else {
                        search.setText(newValue.getValue());
                    }
                });

        Label securityHeading =
                new Label("HERRAMIENTAS");

        securityHeading.getStyleClass()
                .add("sidebar-title");

        // Botón que analiza las contraseñas cargadas y detecta riesgos básicos.
        Button health =
                new Button("✚  Seguridad de contraseñas");

        // Mantenemos la misma estética ligera que las categorías del menú.
        // El fondo solo se resalta suavemente cuando el usuario pasa el ratón.
        styleSidebarToolButton(health);
        health.setOnAction(event -> analyzePasswordSecurity());

        // Las notas seguras se conectarán a SQLite en una siguiente fase.
        // No se simula un guardado temporal: queremos que las notas persistan.
        Button notes =
                new Button("▤  Notas seguras");

        styleSidebarToolButton(notes);
        notes.setOnAction(event -> showNotesInformation());

        VBox sidebar = new VBox(
                18,
                heading,
                tree,
                securityHeading,
                health,
                notes
        );

        sidebar.getStyleClass().add("sidebar");

        sidebar.setPrefWidth(250);
        sidebar.setMinWidth(210);

        VBox.setVgrow(tree, Priority.ALWAYS);

        return sidebar;
    }


    // NOTA DIDÁCTICA: Muestra información al usuario sobre la funcionalidad de notas mediante el mecanismo de diálogo definido en la vista.
    /**
     * Muestra el estado actual del apartado «Notas seguras».
     *
     * Se utiliza un Label con ajuste de línea y un ancho máximo definido
     * para que el texto no se salga de la ventana ni quede cortado.
     * También se amplía el tamaño mínimo del cuadro de diálogo.
     *
     * Este apartado todavía no guarda notas: primero se implementará
     * almacenamiento persistente y cifrado para proteger su contenido.
     */
    private void showNotesInformation() {

        Alert alert = new Alert(Alert.AlertType.INFORMATION);

        alert.setTitle("Notas seguras");
        alert.setHeaderText("Notas seguras de Vaultix");
        alert.setResizable(true);

        Label message = new Label(
                "El apartado de Notas seguras está preparado en la interfaz, "
                + "pero todavía no permite crear ni guardar notas.\n\n"
                + "Antes de habilitarlo, vamos a incorporar almacenamiento "
                + "persistente en SQLite y cifrado del contenido. Así, las "
                + "notas seguirán disponibles al cerrar la aplicación y "
                + "no quedarán almacenadas como texto legible."
        );

        message.setWrapText(true);
        message.setMaxWidth(430);
        message.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        alert.getDialogPane().setContent(message);
        alert.getDialogPane().setMinWidth(480);
        alert.getDialogPane().setPrefWidth(500);
        alert.getDialogPane().setMinHeight(190);

        alert.showAndWait();
    }


    // ========================================================
    // 9. CONTENIDO CENTRAL Y BÚSQUEDA
    // ========================================================

    // NOTA DIDÁCTICA: Aplica clases y propiedades visuales a los botones del panel lateral.
    // NOTA DIDÁCTICA: Actualiza los elementos visibles de la tabla a partir de la colección observable de credenciales.
    /**
     * Da a los botones de herramientas el mismo aspecto visual que la
     * navegación lateral: sin el fondo gris predeterminado de JavaFX,
     * con texto discreto y un resaltado suave al pasar el ratón.
     *
     * @param button botón lateral que se va a personalizar.
     */
    private void styleSidebarToolButton(Button button) {

        button.setMaxWidth(Double.MAX_VALUE);
        button.setMinHeight(36);
        button.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        button.setFocusTraversable(true);

        String normalStyle =
                "-fx-background-color: transparent;"
                + "-fx-background-radius: 6;"
                + "-fx-border-color: transparent;"
                + "-fx-border-radius: 6;"
                + "-fx-text-fill: #31566B;"
                + "-fx-font-size: 13px;"
                + "-fx-font-weight: normal;"
                + "-fx-padding: 8 10 8 10;"
                + "-fx-cursor: hand;";

        String hoverStyle =
                "-fx-background-color: #D2E5E9;"
                + "-fx-background-radius: 6;"
                + "-fx-border-color: transparent;"
                + "-fx-border-radius: 6;"
                + "-fx-text-fill: #173F52;"
                + "-fx-font-size: 13px;"
                + "-fx-font-weight: normal;"
                + "-fx-padding: 8 10 8 10;"
                + "-fx-cursor: hand;";

        button.setStyle(normalStyle);
        button.setOnMouseEntered(event -> button.setStyle(hoverStyle));
        button.setOnMouseExited(event -> button.setStyle(normalStyle));
    }
    // NOTA DIDÁCTICA: Construye el área central donde se presenta el listado de credenciales y las opciones de búsqueda.


    private VBox createMainContent() {

        Label title =
                new Label("Mis contraseñas");

        title.getStyleClass().add("heading");

        Label subtitle = new Label(
                "Todos tus accesos seguros en un único lugar."
        );

        subtitle.getStyleClass().add("subtitle");

        search.setPromptText(
                "⌕  Buscar en tus credenciales..."
        );

        search.setMaxWidth(Double.MAX_VALUE);

        // Construimos la tabla.
        createTable();

        /*
         * Escuchamos los cambios del campo de búsqueda.
         * Cada vez que se escribe, actualizamos los resultados.
         */
        search.textProperty().addListener(
                (observable, oldValue, newValue) ->
                        refreshTable()
        );

        VBox content = new VBox(
                16,
                title,
                subtitle,
                search,
                table
        );

        content.getStyleClass().add("main-content");

        VBox.setVgrow(table, Priority.ALWAYS);

        return content;
    }


    // ========================================================
    // 10. ACTUALIZACIÓN Y FILTRADO DE LA TABLA
    // ========================================================

    /*
     * Filtra las credenciales por:
     * - Nombre.
     * - Usuario.
     * - Categoría.
     *
     * Los datos originales siguen almacenados en la colección.
     */
    private void refreshTable() {

        String filter = search.getText() == null
                ? ""
                : search.getText().toLowerCase().trim();

        List<CredentialRow> filtered = credentials.stream()
                .filter(c ->
                        c.getTitle().toLowerCase().contains(filter)
                        || c.getUsername().toLowerCase().contains(filter)
                        || c.getCategory().toLowerCase().contains(filter)
                )
                .toList();

        // Actualizamos los elementos visibles de la tabla.
        table.setItems(
                FXCollections.observableArrayList(filtered)
        );

        // Si la credencial seleccionada desaparece del filtro,
        // limpiamos el panel de detalles.
        if (selectedCredential != null
                && !filtered.contains(selectedCredential)) {

            table.getSelectionModel().clearSelection();
            clearDetails();
        }
    }


    // ========================================================
    // 11. CREACIÓN DE LA TABLA DE CREDENCIALES
    // ========================================================
    // NOTA DIDÁCTICA: Configura columnas, propiedades y comportamiento de la tabla de credenciales.

    private void createTable() {

        TableColumn<CredentialRow, String> title =
                new TableColumn<>("NOMBRE");

        title.setCellValueFactory(
                data -> data.getValue().titleProperty()
        );

        TableColumn<CredentialRow, String> username =
                new TableColumn<>("USUARIO");

        username.setCellValueFactory(
                data -> data.getValue().usernameProperty()
        );

        TableColumn<CredentialRow, String> category =
                new TableColumn<>("CATEGORÍA");

        category.setCellValueFactory(
                data -> data.getValue().categoryProperty()
        );

        // La categoría se representa como una etiqueta de color suave.
        // El color ayuda a distinguir los tipos de cuenta sin recargar la tabla.
        category.setCellFactory(column -> new TableCell<>() {

            private final Label categoryBadge = new Label();

            {
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                categoryBadge.setPadding(new Insets(4, 9, 4, 9));
            }

            @Override
    // NOTA DIDÁCTICA: Actualiza el elemento visual correspondiente para reflejar el estado o selección actual.
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null || item.isBlank()) {
                    setGraphic(null);
                    return;
                }

                categoryBadge.setText(getCategoryIcon(item) + "  " + item);
                categoryBadge.setStyle(getCategoryBadgeStyle(item));
                setGraphic(categoryBadge);
            }
        });

        title.setPrefWidth(220);
        username.setPrefWidth(220);
        category.setPrefWidth(130);

        table.getColumns().setAll(
                title,
                username,
                category
        );

        // Inicializamos la tabla con los datos recuperados.
        table.setItems(credentials);

        table.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        );

        table.setPlaceholder(
                new Label("No hay credenciales almacenadas.")
        );

        // Al seleccionar una fila mostramos sus detalles.
        table.getSelectionModel()
                .selectedItemProperty()
                .addListener((obs, oldValue, newValue) -> {

                    if (newValue != null) {
                        showDetails(newValue);
                    } else {
                        clearDetails();
                    }
                });
    }


    // ========================================================
    // 12. PANEL LATERAL DE DETALLES
    // ========================================================
    // NOTA DIDÁCTICA: Construye el panel lateral derecho que muestra información de la credencial seleccionada y acciones relacionadas.

    private VBox createDetailsPanel() {

        Label heading = new Label("DETALLES");
        heading.getStyleClass().add("details-title");

        detailTitle.getStyleClass().add("details-heading");
        detailTitle.setWrapText(true);

        Label userHeading = new Label("USUARIO");
        userHeading.getStyleClass().add("details-label");

        detailUser.getStyleClass().add("details-value");

        // Botón para copiar el nombre de usuario.
        copyUserButton.setOnAction(e -> {

            if (selectedCredential != null) {

                copyToClipboard(
                        "usuario",
                        selectedCredential.getUsername()
                );
            }
        });

        Label passwordHeading =
                new Label("CONTRASEÑA");

        passwordHeading.getStyleClass()
                .add("details-label");

        detailPassword.getStyleClass()
                .add("details-value");

        // Botón para copiar la contraseña.
        copyPasswordButton.setOnAction(e -> {

            if (selectedCredential != null) {

                copyToClipboard(
                        "contraseña",
                        selectedCredential.getPassword()
                );
            }
        });

        Label categoryHeading =
                new Label("CATEGORÍA");

        categoryHeading.getStyleClass()
                .add("details-label");

        detailCategory.getStyleClass()
                .add("details-value");

        Label info = new Label(
                "Selecciona una credencial para consultar sus detalles."
        );

        info.setWrapText(true);
        info.getStyleClass().add("details-info");

        // Configuración de la barra de progreso.
        clipboardProgress.setMaxWidth(Double.MAX_VALUE);
        clipboardProgress.setProgress(0);
        clipboardProgress.setVisible(false);
        clipboardProgress.setManaged(false);

        clipboardStatus.getStyleClass()
                .add("details-info");

        // Pie de aplicación. El año se obtiene del reloj del sistema,
        // por lo que se actualizará automáticamente cada año.
        Label copyright = new Label(
                "© " + Year.now().getValue() + " CascalesDevelop"
        );
        copyright.setStyle(
                "-fx-text-fill: #7F8B98;"
                + " -fx-font-size: 10px;"
        );

        Region footerSpacer = new Region();
        VBox.setVgrow(footerSpacer, Priority.ALWAYS);

        VBox panel = new VBox(
                13,
                heading,
                detailTitle,
                new Separator(),
                userHeading,
                detailUser,
                new HBox(copyUserButton),
                passwordHeading,
                detailPassword,
                new HBox(copyPasswordButton),
                new Separator(),
                clipboardStatus,
                clipboardProgress,
                categoryHeading,
                detailCategory,
                new Separator(),
                info,
                footerSpacer,
                copyright
        );

        panel.getStyleClass().add("details-panel");

        panel.setPrefWidth(270);
        panel.setMinWidth(230);

        return panel;
    }


    // ========================================================
    // VALIDACIÓN DE CORREO ELECTRÓNICO
    // ========================================================

    // NOTA DIDÁCTICA: Comprueba de forma básica el formato de una dirección de correo antes de aceptar el dato.
    /**
     * Comprueba que el texto tenga una estructura habitual de correo:
     * usuario@dominio.extensión.
     *
     * Esta comprobación valida el formato, pero no confirma que la
     * dirección exista ni que se pueda recibir correo en ella.
     */
    private boolean isValidEmail(String email) {
        if (email == null) {
            return false;
        }

        String pattern =
                "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";

        return email.trim().matches(pattern);
    }

    // NOTA DIDÁCTICA: Obtiene el texto que debe mostrarse para una categoría.
    /**
     * Obtiene la categoría escrita o seleccionada en el ComboBox editable.
     * Se consulta el editor para admitir también categorías introducidas
     * manualmente por el usuario.
     */
    private String getCategoryText(ComboBox<String> categoryField) {
        String editorText = categoryField.getEditor().getText();

        if (editorText != null && !editorText.isBlank()) {
            return editorText.trim();
        }

        String selectedValue = categoryField.getValue();

        return selectedValue == null ? "" : selectedValue.trim();
    }


    // ========================================================
    // 13. CRUD: CREAR UNA CREDENCIAL
    // ========================================================
    // NOTA DIDÁCTICA: Solicita los datos de una nueva credencial, valida el formulario y delega el almacenamiento cifrado en CredentialDAO.

    private void createCredential() {

        Dialog<CredentialRow> dialog =
                new Dialog<>();

        dialog.setTitle("Nueva credencial");
        dialog.setHeaderText(
                "Introduce los datos de acceso"
        );

        ButtonType saveButton = new ButtonType(
                "Guardar",
                ButtonBar.ButtonData.OK_DONE
        );

        dialog.getDialogPane().getButtonTypes().addAll(
                saveButton,
                ButtonType.CANCEL
        );

        // Campos del formulario.
        TextField titleField = new TextField();
        titleField.setPromptText("Ej. Amazon");

        TextField usernameField = new TextField();
        usernameField.setPromptText("Nombre de usuario o correo");

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Contraseña");

        ComboBox<String> categoryField =
                new ComboBox<>();

        categoryField.getItems().addAll(categories);
        categoryField.setEditable(true);
        categoryField.setValue("Personal");
        categoryField.setMaxWidth(Double.MAX_VALUE);

        // Distribución del formulario.
        GridPane grid = new GridPane();

        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(15));

        grid.addRow(0, new Label("Nombre:"), titleField);
        grid.addRow(1, new Label("Usuario:"), usernameField);
        grid.addRow(2, new Label("Contraseña:"), passwordField);
        grid.addRow(3, new Label("Categoría:"), categoryField);

        dialog.getDialogPane().setContent(grid);

        // Evitamos guardar formularios incompletos.
        Button save = (Button) dialog.getDialogPane()
                .lookupButton(saveButton);

        save.addEventFilter(
                javafx.event.ActionEvent.ACTION,
                event -> {

                    String categoryText = getCategoryText(categoryField);

                    // Primero comprobamos que no falte ningún dato obligatorio.
                    if (titleField.getText().isBlank()
                            || usernameField.getText().isBlank()
                            || passwordField.getText().isBlank()
                            || categoryText.isBlank()) {

                        event.consume();

                        showMessage(
                                "Datos incompletos",
                                "Rellena todos los campos antes de guardar."
                        );

                        return;
                    }

                    // Si la categoría es Correo, exigimos un formato de email válido.
                    if ("Correo".equalsIgnoreCase(categoryText)
                            && !isValidEmail(usernameField.getText())) {

                        event.consume();

                        showError(
                                "Correo no válido",
                                "La dirección de correo no tiene un formato válido. "
                                + "Introduce una dirección como nombre@dominio.com."
                        );
                    }
                }
        );

        // Convertimos los datos del formulario en un objeto.
        dialog.setResultConverter(button -> {

            if (button == saveButton) {

                return new CredentialRow(
                        titleField.getText().trim(),
                        usernameField.getText().trim(),
                        passwordField.getText(),
                        getCategoryText(categoryField)
                );
            }

            return null;
        });

        // Si el usuario pulsa Guardar, insertamos en SQLite.
        dialog.showAndWait().ifPresent(credential -> {

            try {

                /*
                 * Insertamos la nueva credencial exclusivamente en SQLite.
                 * CredentialDAO devuelve el identificador generado por la
                 * base de datos. Lo asignamos al objeto para mantener
                 * sincronizados el modelo de la aplicación y el registro.
                 */
                int generatedId = credentialDAO.insert(credential);

                // Guardamos en el objeto el ID generado por SQLite.
                credential.setId(generatedId);

                // Actualizamos la colección de la interfaz.
                credentials.add(credential);

                refreshTable();

                table.getSelectionModel().select(credential);

                // Confirmamos al usuario que la operación ha finalizado.
                showMessage(
                        "Credencial creada",
                        "La credencial se ha guardado correctamente en SQLite."
                );

            } catch (SQLException e) {

                showMessage(
                        "Error al guardar",
                        "No se pudo insertar la credencial: "
                        + e.getMessage()
                );

                e.printStackTrace();
            }
        });
    }


    // ========================================================
    // 14. CRUD: EDITAR UNA CREDENCIAL
    // ========================================================
    // NOTA DIDÁCTICA: Permite modificar la credencial seleccionada y persiste los cambios mediante el DAO.

    private void editCredential() {

        CredentialRow credential =
                table.getSelectionModel().getSelectedItem();

        if (credential == null) {

            showMessage(
                    "Editar credencial",
                    "Selecciona primero una credencial."
            );

            return;
        }

        Dialog<CredentialRow> dialog =
                new Dialog<>();

        dialog.setTitle("Editar credencial");
        dialog.setHeaderText(
                "Modifica los datos de acceso"
        );

        ButtonType saveButton = new ButtonType(
                "Guardar cambios",
                ButtonBar.ButtonData.OK_DONE
        );

        dialog.getDialogPane().getButtonTypes().addAll(
                saveButton,
                ButtonType.CANCEL
        );

        // Rellenamos el formulario con los datos actuales.
        TextField titleField =
                new TextField(credential.getTitle());

        TextField usernameField =
                new TextField(credential.getUsername());

        PasswordField passwordField =
                new PasswordField();

        passwordField.setText(
                credential.getPassword()
        );

        ComboBox<String> categoryField =
                new ComboBox<>();

        categoryField.getItems().addAll(categories);
        categoryField.setEditable(true);
        categoryField.setValue(credential.getCategory());
        categoryField.setMaxWidth(Double.MAX_VALUE);

        GridPane grid = new GridPane();

        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(15));

        grid.addRow(0, new Label("Nombre:"), titleField);
        grid.addRow(1, new Label("Usuario:"), usernameField);
        grid.addRow(2, new Label("Contraseña:"), passwordField);
        grid.addRow(3, new Label("Categoría:"), categoryField);

        dialog.getDialogPane().setContent(grid);

        // Validamos los campos obligatorios.
        Button save = (Button) dialog.getDialogPane()
                .lookupButton(saveButton);

        save.addEventFilter(
                javafx.event.ActionEvent.ACTION,
                event -> {

                    String categoryText = getCategoryText(categoryField);

                    // Primero comprobamos que no falte ningún dato obligatorio.
                    if (titleField.getText().isBlank()
                            || usernameField.getText().isBlank()
                            || passwordField.getText().isBlank()
                            || categoryText.isBlank()) {

                        event.consume();

                        showMessage(
                                "Datos incompletos",
                                "Rellena todos los campos antes de guardar."
                        );

                        return;
                    }

                    // Si la categoría es Correo, exigimos un formato de email válido.
                    if ("Correo".equalsIgnoreCase(categoryText)
                            && !isValidEmail(usernameField.getText())) {

                        event.consume();

                        showError(
                                "Correo no válido",
                                "La dirección de correo no tiene un formato válido. "
                                + "Introduce una dirección como nombre@dominio.com."
                        );
                    }
                }
        );

        dialog.setResultConverter(button -> {

            if (button == saveButton) {

                /*
                 * Conservamos el identificador original.
                 * Es imprescindible para actualizar el registro correcto.
                 */
                return new CredentialRow(
                        credential.getId(),
                        titleField.getText().trim(),
                        usernameField.getText().trim(),
                        passwordField.getText(),
                        getCategoryText(categoryField)
                );
            }

            return null;
        });

        dialog.showAndWait().ifPresent(updated -> {

            try {

                /*
                 * Actualizamos el registro en SQLite.
                 * El DAO utiliza el identificador de la credencial para
                 * localizar el registro que debe modificarse.
                 */
                boolean updatedSuccessfully = credentialDAO.update(updated);

                if (!updatedSuccessfully) {

                    showMessage(
                            "Registro no encontrado",
                            "No se ha encontrado la credencial en SQLite."
                    );

                    loadCredentialsFromDatabase();
                    refreshTable();
                    return;
                }

                // Actualizamos el objeto que utiliza la interfaz.
                credential.setTitle(updated.getTitle());
                credential.setUsername(updated.getUsername());
                credential.setPassword(updated.getPassword());
                credential.setCategory(updated.getCategory());

                refreshTable();

                table.getSelectionModel().select(credential);
                showDetails(credential);

                showMessage(
                        "Credencial actualizada",
                        "Los cambios se han guardado correctamente."
                );

            } catch (SQLException e) {

                showMessage(
                        "Error al actualizar",
                        "No se pudo modificar la credencial: "
                        + e.getMessage()
                );

                e.printStackTrace();
            }
        });
    }


    // ========================================================
    // 15. CRUD: ELIMINAR UNA CREDENCIAL
    // ========================================================
    // NOTA DIDÁCTICA: Solicita confirmación y elimina del almacenamiento la credencial seleccionada.

    private void deleteCredential() {

        CredentialRow credential =
                table.getSelectionModel().getSelectedItem();

        if (credential == null) {

            showMessage(
                    "Eliminar credencial",
                    "Selecciona primero una credencial."
            );

            return;
        }

        // Pedimos confirmación antes de eliminar.
        Alert confirmation =
                new Alert(Alert.AlertType.CONFIRMATION);

        confirmation.setTitle("Eliminar credencial");

        confirmation.setHeaderText(
                "¿Quieres eliminar esta credencial?"
        );

        confirmation.setContentText(
                "Se eliminará el acceso a: "
                + credential.getTitle()
        );

        confirmation.showAndWait().ifPresent(response -> {

            if (response == ButtonType.OK) {

                try {

                    /*
                     * Eliminamos el registro de SQLite utilizando su ID.
                     * El DAO identifica así exactamente qué credencial debe
                     * borrar, sin depender del nombre del servicio.
                     */
                    boolean deleted = credentialDAO.delete(credential.getId());

                    if (!deleted) {

                        showMessage(
                                "Registro no encontrado",
                                "No se ha encontrado la credencial en SQLite."
                        );

                        loadCredentialsFromDatabase();
                        refreshTable();
                        return;
                    }

                    // Eliminamos el registro de la colección local.
                    credentials.remove(credential);

                    clearDetails();

                    refreshTable();

                    // Confirmamos que el registro se ha eliminado de SQLite.
                    showMessage(
                            "Credencial eliminada",
                            "La credencial se ha eliminado de SQLite."
                    );

                } catch (SQLException e) {

                    showMessage(
                            "Error al eliminar",
                            "No se pudo eliminar la credencial: "
                            + e.getMessage()
                    );

                    e.printStackTrace();
                }
            }
        });
    }


    // ========================================================
    // 16. ANÁLISIS BÁSICO DE SEGURIDAD DE CONTRASEÑAS
    // ========================================================

    // NOTA DIDÁCTICA: Analiza la contraseña de una credencial para ofrecer una indicación sobre su seguridad; no sustituye una auditoría especializada.
    /**
     * Revisa las credenciales cargadas en memoria sin mostrar sus contraseñas.
     * Detecta contraseñas cortas (menos de 12 caracteres) y contraseñas
     * reutilizadas en más de una cuenta. Es una comprobación orientativa,
     * no sustituye una auditoría criptográfica ni comprueba filtraciones.
     */
    private void analyzePasswordSecurity() {

        if (credentials.isEmpty()) {
            showMessage(
                    "Seguridad de contraseñas",
                    "Todavía no hay credenciales que analizar."
            );
            return;
        }

        int shortPasswords = 0;
        int reusedPasswords = 0;
        java.util.Map<String, Integer> passwordOccurrences =
                new java.util.HashMap<>();

        for (CredentialRow credential : credentials) {
            String password = credential.getPassword();

            if (password == null || password.length() < 12) {
                shortPasswords++;
            }

            if (password != null && !password.isEmpty()) {
                passwordOccurrences.merge(password, 1, Integer::sum);
            }
        }

        for (int occurrences : passwordOccurrences.values()) {
            if (occurrences > 1) {
                reusedPasswords += occurrences;
            }
        }

        String report = "Credenciales analizadas: " + credentials.size()
                + "\nContraseñas de menos de 12 caracteres: " + shortPasswords
                + "\nCredenciales que usan una contraseña repetida: " + reusedPasswords
                + "\n\nRecomendación: utiliza una contraseña larga y diferente "
                + "para cada servicio.";

        Alert reportAlert = new Alert(Alert.AlertType.INFORMATION);
        reportAlert.setTitle("Análisis de seguridad");
        reportAlert.setHeaderText("Resultado de la revisión");
        reportAlert.setContentText(report);
        reportAlert.showAndWait();
    }


    // ========================================================
    // 17. GENERADOR DE CONTRASEÑAS
    // ========================================================
    // NOTA DIDÁCTICA: Genera una contraseña aleatoria utilizando SecureRandom, apropiado para generar valores impredecibles.

    private void generatePassword() {

        // Conjunto de caracteres permitidos.
        String characters =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                + "abcdefghijklmnopqrstuvwxyz"
                + "0123456789"
                + "!@#$%&*+-_";

        // SecureRandom proporciona números aleatorios seguros.
        SecureRandom random = new SecureRandom();

        StringBuilder password = new StringBuilder();

        // Generamos una contraseña de 20 caracteres.
        for (int i = 0; i < 20; i++) {

            int index = random.nextInt(
                    characters.length()
            );

            password.append(
                    characters.charAt(index)
            );
        }

        TextInputDialog dialog =
                new TextInputDialog(password.toString());

        dialog.setTitle("Generador de contraseñas");

        dialog.setHeaderText(
                "Contraseña aleatoria generada"
        );

        dialog.setContentText(
                "Puedes copiarla o utilizarla al crear una credencial:"
        );

        // Si se acepta, copiamos el contenido al portapapeles.
        dialog.showAndWait().ifPresent(value -> {

            if (!value.isBlank()) {

                copyToClipboard(
                        "contraseña generada",
                        value
                );
            }
        });
    }


    // ========================================================
    // 17. MOSTRAR LOS DETALLES DE UNA CREDENCIAL
    // ========================================================
    // NOTA DIDÁCTICA: Actualiza el panel de detalles con la información de la credencial seleccionada.

    private void showDetails(CredentialRow credential) {

        selectedCredential = credential;

        detailTitle.setText(credential.getTitle());
        detailUser.setText(credential.getUsername());
        detailCategory.setText(getCategoryIcon(credential.getCategory())
                + "  " + credential.getCategory());
        detailCategory.setStyle(getCategoryBadgeStyle(credential.getCategory()));
        detailCategory.setPadding(new Insets(4, 9, 4, 9));

        // Mostramos únicamente puntos, nunca la contraseña real.
        detailPassword.setText("•".repeat(
                credential.getPassword().length()
        ));
    }


    // Limpia el panel de detalles cuando no hay selección.
    // NOTA DIDÁCTICA: Restablece los controles del panel de detalles cuando no hay ninguna credencial seleccionada.
    private void clearDetails() {

        selectedCredential = null;

        detailTitle.setText("Selecciona una credencial");
        detailUser.setText("—");
        detailPassword.setText("—");
        detailCategory.setText("—");
        detailCategory.setStyle("");
        detailCategory.setPadding(Insets.EMPTY);
    }


    // ========================================================
    // 19. COPIAR INFORMACIÓN AL PORTAPAPELES
    // ========================================================
    // NOTA DIDÁCTICA: Copia temporalmente un valor al portapapeles y activa el mecanismo de borrado automático.

    private void copyToClipboard(String type, String value) {

        if (value == null || value.isEmpty()) {

            clipboardStatus.setText(
                    "No hay información para copiar."
            );

            return;
        }

        // Detenemos cualquier temporizador anterior.
        stopClipboardTimer();

        Clipboard clipboard =
                Clipboard.getSystemClipboard();

        ClipboardContent content =
                new ClipboardContent();

        content.putString(value);

        // Enviamos la información al portapapeles.
        boolean copied = clipboard.setContent(content);

        if (!copied) {

            clipboardStatus.setText(
                    "No se pudo acceder al portapapeles."
            );

            return;
        }

        lastCopiedValue = value;

        clipboardProgress.setProgress(1);
        clipboardProgress.setVisible(true);
        clipboardProgress.setManaged(true);

        // Calculamos el instante de expiración.
        long expirationTime =
                System.nanoTime()
                + CLIPBOARD_SECONDS * 1_000_000_000L;

        clipboardStatus.setText(
                "Copiado " + type + ". "
                + CLIPBOARD_SECONDS
                + " segundos restantes."
        );

        // Actualizamos la barra cada 100 milisegundos.
        clipboardTimeline = new Timeline(
                new KeyFrame(
                        Duration.millis(100),
                        event -> {

                            long remainingNanos =
                                    expirationTime - System.nanoTime();

                            double remainingSeconds =
                                    Math.max(
                                            0,
                                            remainingNanos / 1_000_000_000.0
                                    );

                            clipboardProgress.setProgress(
                                    remainingSeconds / CLIPBOARD_SECONDS
                            );

                            int seconds =
                                    (int) Math.ceil(remainingSeconds);

                            if (remainingNanos <= 0) {

                                finishClipboardTimer();

                            } else {

                                clipboardStatus.setText(
                                        "Portapapeles: "
                                        + seconds
                                        + " segundos restantes."
                                );
                            }
                        }
                )
        );

        clipboardTimeline.setCycleCount(
                Animation.INDEFINITE
        );

        clipboardTimeline.play();
    }


    // ========================================================
    // 20. FINALIZAR EL TEMPORIZADOR DEL PORTAPAPELES
    // ========================================================
    // NOTA DIDÁCTICA: Finaliza el temporizador y realiza las acciones de limpieza asociadas al contenido copiado.

    private void finishClipboardTimer() {

        stopClipboardTimer();

        Clipboard clipboard =
                Clipboard.getSystemClipboard();

        String currentClipboard = null;

        try {

            if (clipboard.hasString()) {
                currentClipboard = clipboard.getString();
            }

        } catch (IllegalStateException ex) {

            clipboardStatus.setText(
                    "No se pudo comprobar el portapapeles."
            );
        }

        /*
         * Solo borramos el contenido si sigue siendo
         * exactamente el valor que copió Vaultix.
         *
         * Así evitamos borrar información diferente
         * que el usuario haya copiado después.
         */
        if (lastCopiedValue != null
                && lastCopiedValue.equals(currentClipboard)) {

            clipboard.clear();

            clipboardStatus.setText(
                    "Portapapeles limpiado automáticamente."
            );

        } else {

            clipboardStatus.setText(
                    "El portapapeles ha cambiado. "
                    + "Se ha respetado su contenido."
            );
        }

        clipboardProgress.setProgress(0);
        clipboardProgress.setVisible(false);
        clipboardProgress.setManaged(false);

        lastCopiedValue = null;
    }


    // ========================================================
    // 21. DETENER EL TEMPORIZADOR
    // ========================================================
    // NOTA DIDÁCTICA: Detiene y libera el Timeline si existe, evitando que siga ejecutándose después de dejar de necesitarse.

    private void stopClipboardTimer() {

        if (clipboardTimeline != null) {

            clipboardTimeline.stop();

            clipboardTimeline = null;
        }
    }


    // ========================================================
    // 22. LIBERAR RECURSOS AL CERRAR LA APLICACIÓN
    // ========================================================

    // NOTA DIDÁCTICA: Libera recursos temporales del Dashboard, detiene temporizadores y limpia el portapapeles cuando corresponde.
    /**
     * Libera recursos sensibles y bloquea la bóveda.
     *
     * Se detiene el temporizador del portapapeles, se limpia el dato copiado
     * por Vaultix si sigue presente, se vacían las credenciales descifradas
     * que conserva la vista y se cierra la VaultSession.
     *
     * Es un método idempotente: puede invocarse más de una vez sin provocar
     * errores, por ejemplo desde el botón Salir y desde SafeVaultDAM.stop().
     */
    public void shutdown() {

        if (shutdownCompleted) {
            return;
        }

        shutdownCompleted = true;

        // 1. Detenemos cualquier temporizador del portapapeles.
        stopClipboardTimer();

        // 2. Limpiamos nuestro contenido del portapapeles, si aún está presente.
        if (lastCopiedValue != null) {

            Clipboard clipboard =
                    Clipboard.getSystemClipboard();

            try {

                /*
                 * Solo borramos el contenido si sigue siendo exactamente
                 * el que copió Vaultix. Respetamos cualquier otro texto
                 * que el usuario haya copiado posteriormente.
                 */
                if (clipboard.hasString()
                        && lastCopiedValue.equals(clipboard.getString())) {

                    clipboard.clear();
                }

            } catch (IllegalStateException ex) {

                // El portapapeles puede estar temporalmente ocupado.
                System.err.println(
                        "Vaultix: no se pudo limpiar el portapapeles: "
                        + ex.getMessage()
                );
            }
        }

        lastCopiedValue = null;

        // 3. Sobrescribimos los valores que mantiene el modelo en memoria.
        /*
         * Las credenciales se encuentran descifradas mientras el Dashboard
         * está abierto. Limpiamos las propiedades antes de abandonar la vista
         * para reducir el tiempo durante el que esos datos permanecen
         * referenciados por la interfaz.
         *
         * Java utiliza Strings inmutables: no se puede garantizar el borrado
         * físico de todas las copias internas de memoria. Esta limpieza reduce
         * la exposición, pero no equivale a un borrado seguro de RAM.
         */
        for (CredentialRow credential : credentials) {
            credential.setTitle("");
            credential.setUsername("");
            credential.setPassword("");
            credential.setCategory("");
        }

        credentials.clear();

        // 4. Quitamos la selección y vaciamos el panel de detalles.
        selectedCredential = null;
        clearDetails();

        // 5. Bloqueamos la bóveda y sobrescribimos la clave de la sesión.
        vaultSession.close();
    }


    // ========================================================
    // 23. VENTANAS DE INFORMACIÓN
    // ========================================================

    // NOTA DIDÁCTICA: Presenta un mensaje informativo integrado en la interfaz.
    /**
     * Muestra un mensaje en una ventana emergente independiente.
     *
     * JavaFX Alert presenta información en una ventana separada de la
     * ventana principal. A diferencia de clipboardStatus, que es un Label
     * integrado en el panel de detalles, esta alerta llama la atención
     * del usuario y debe cerrarse para continuar.
     *
     * Esta implementación utiliza AlertType.INFORMATION para mensajes
     * generales. Los errores de validación utilizan showError(), que
     * presenta una alerta de tipo AlertType.ERROR.
     *
     * @param title   título que aparece en la barra de la ventana.
     * @param message texto explicativo que verá el usuario.
     */
    private void showMessage(String title, String message) {

        // Creamos una alerta informativa de JavaFX.
        Alert alert =
                new Alert(Alert.AlertType.INFORMATION);

        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);

        alert.showAndWait();
    }

    // NOTA DIDÁCTICA: Presenta un mensaje de error dentro de la propia pantalla, sin abrir una ventana emergente.
    /**
     * Muestra un error mediante una ventana emergente de tipo ERROR.
     * Se utiliza, por ejemplo, cuando el formato del correo no es válido.
     */
    private void showError(String title, String message) {

        Alert alert = new Alert(Alert.AlertType.ERROR);

        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);

        alert.showAndWait();
    }


    // ========================================================
    // 24. MODELO DE DATOS: CREDENTIALROW
    // ========================================================

    /*
     * Esta clase representa una credencial de Vaultix.
     *
     * Cada objeto contiene:
     * - id: identificador único de la base de datos.
     * - title: nombre del servicio.
     * - username: usuario o correo electrónico.
     * - password: contraseña.
     * - category: categoría de la credencial.
     *
     * Las propiedades StringProperty permiten que JavaFX
     * actualice automáticamente las columnas de la tabla.
     */
    public static class CredentialRow {

        // Identificador único generado por SQLite.
        private int id;

        // Propiedades observables de JavaFX.
        private final StringProperty title;
        private final StringProperty username;
        private final StringProperty password;
        private final StringProperty category;


        /*
         * Constructor de cuatro parámetros.
         *
         * Se utiliza para crear nuevas credenciales
         * que todavía no tienen un ID asignado.
         *
         * El identificador inicial será 0.
         */
        public CredentialRow(
                String title,
                String username,
                String password,
                String category) {

            this(
                    0,
                    title,
                    username,
                    password,
                    category
            );
        }


        /*
         * Constructor de cinco parámetros.
         *
         * Se utiliza cuando recuperamos una credencial
         * que ya existe en la base de datos.
         */
        public CredentialRow(
                int id,
                String title,
                String username,
                String password,
                String category) {

            this.id = id;

            this.title =
                    new SimpleStringProperty(title);

            this.username =
                    new SimpleStringProperty(username);

            this.password =
                    new SimpleStringProperty(password);

            this.category =
                    new SimpleStringProperty(category);
        }


        // ---------------- IDENTIFICADOR ----------------
    // NOTA DIDÁCTICA: Devuelve el identificador de la credencial, utilizado para localizar el registro en la base de datos.

        public int getId() {
            return id;
        }
    // NOTA DIDÁCTICA: Asigna el identificador de la credencial.

        public void setId(int id) {
            this.id = id;
        }


        // ---------------- NOMBRE ----------------
    // NOTA DIDÁCTICA: Devuelve el título descriptivo de la credencial.

        public String getTitle() {
            return title.get();
        }
    // NOTA DIDÁCTICA: Actualiza el título descriptivo de la credencial.

        public void setTitle(String value) {
            title.set(value);
        }
    // NOTA DIDÁCTICA: Expone la propiedad observable del título para que los controles JavaFX puedan enlazarse a ella.

        public StringProperty titleProperty() {
            return title;
        }


        // ---------------- USUARIO ----------------
    // NOTA DIDÁCTICA: Devuelve el nombre de usuario asociado a la credencial.

        public String getUsername() {
            return username.get();
        }
    // NOTA DIDÁCTICA: Actualiza el nombre de usuario asociado a la credencial.

        public void setUsername(String value) {
            username.set(value);
        }
    // NOTA DIDÁCTICA: Expone la propiedad observable del nombre de usuario.

        public StringProperty usernameProperty() {
            return username;
        }


        // ---------------- CONTRASEÑA ----------------
    // NOTA DIDÁCTICA: Devuelve el valor de contraseña mantenido por el modelo en memoria.

        public String getPassword() {
            return password.get();
        }
    // NOTA DIDÁCTICA: Actualiza el valor de contraseña del modelo en memoria.

        public void setPassword(String value) {
            password.set(value);
        }
    // NOTA DIDÁCTICA: Expone la propiedad observable de contraseña para los enlaces de JavaFX.

        public StringProperty passwordProperty() {
            return password;
        }


        // ---------------- CATEGORÍA ----------------
    // NOTA DIDÁCTICA: Devuelve la categoría asignada a la credencial.

        public String getCategory() {
            return category.get();
        }
    // NOTA DIDÁCTICA: Actualiza la categoría de la credencial.

        public void setCategory(String value) {
            category.set(value);
        }
    // NOTA DIDÁCTICA: Expone la propiedad observable de categoría.

        public StringProperty categoryProperty() {
            return category;
        }
    }
}
