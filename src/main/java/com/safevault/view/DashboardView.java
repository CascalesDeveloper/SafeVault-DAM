
package com.safevault.view;

// ============================================================
// IMPORTACIONES
// ============================================================

// Clases para controlar animaciones y temporizadores.
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;

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

// Generación segura de contraseñas.
import java.security.SecureRandom;

// Acceso a la capa de persistencia SQLite.
import com.safevault.database.CredentialDAO;
import com.safevault.database.MariaDBCredentialDAO;
import java.sql.SQLException;


// ============================================================
// CLASE PRINCIPAL DE LA INTERFAZ
// ============================================================

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

    // Selector del motor de base de datos activo.
    private final ComboBox<String> databaseSelector = new ComboBox<>();
    private static final String SQLITE = "SQLite";
    private static final String MARIADB = "MariaDB";

    // Etiquetas del panel lateral de detalles.
    private final Label detailTitle =
            new Label("Selecciona una credencial");

    private final Label detailUser =
            new Label("—");

    private final Label detailPassword =
            new Label("—");

    private final Label detailCategory =
            new Label("—");

    // Botones para mostrar y copiar información.
    private final Button revealButton =
            new Button("Mostrar");

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

    // Indica si la contraseña se encuentra visible.
    private boolean passwordVisible = false;


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
    private final CredentialDAO credentialDAO = new CredentialDAO();

    // DAO independiente para MariaDB.
    private final MariaDBCredentialDAO mariaDBCredentialDAO =
            new MariaDBCredentialDAO();

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

    public DashboardView() {

        /*
         * Primero recuperamos los datos almacenados.
         * Después construimos la interfaz gráfica.
         */
        // SQLite es el motor predeterminado para conservar el
        // comportamiento que ya tenía la aplicación.
        databaseSelector.getItems().addAll(SQLITE, MARIADB);
        databaseSelector.setValue(SQLITE);

        createInterface();

        // Al cambiar de motor, cargamos los datos de esa base de datos.
        databaseSelector.setOnAction(event -> {
            clearDetails();
            loadCredentialsFromDatabase();
            refreshTable();
        });

        loadCredentialsFromDatabase();
    }


    // Devuelve el contenedor principal para mostrarlo en la ventana.
    public Parent getView() {
        return root;
    }


    // ========================================================
    // 4. SELECCIÓN Y CARGA DE LA BASE DE DATOS
    // ========================================================

    /** Indica qué motor de base de datos está seleccionado. */
    private boolean isMariaDBSelected() {
        return MARIADB.equals(databaseSelector.getValue());
    }

    /** Devuelve el nombre del motor activo para los mensajes. */
    private String getDatabaseName() {
        return isMariaDBSelected() ? MARIADB : SQLITE;
    }

    /** Recupera las credenciales del motor seleccionado. */
    private void loadCredentialsFromDatabase() {
        try {
            if (isMariaDBSelected()) {
                credentials.setAll(mariaDBCredentialDAO.findAll());
            } else {
                credentials.setAll(credentialDAO.findAll());
            }

            System.out.println(
                    "SafeVault: credenciales recuperadas de "
                    + getDatabaseName() + ": " + credentials.size()
            );
        } catch (SQLException e) {
            credentials.clear();
            showMessage(
                    "Error de base de datos",
                    "No se pudieron recuperar las credenciales de "
                    + getDatabaseName() + ": " + e.getMessage()
            );
            e.printStackTrace();
        }
    }


    // ========================================================
    // 5. CONSTRUCCIÓN DE LA INTERFAZ
    // ========================================================

    private void createInterface() {

        // Aplicamos la clase CSS principal.
        root.getStyleClass().add("safevault-root");

        // Construimos la parte superior de la aplicación.
        VBox top = new VBox(
                createMenuBar(),
                createToolBar()
        );

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

    private MenuBar createMenuBar() {

        MenuBar menuBar = new MenuBar();

        Menu archivo = new Menu("Archivo");
        Menu editar = new Menu("Editar");
        Menu ver = new Menu("Ver");
        Menu herramientas = new Menu("Herramientas");
        Menu ayuda = new Menu("Ayuda");

        // Opción para crear una nueva credencial.
        MenuItem newItem = new MenuItem("Nueva credencial");
        newItem.setOnAction(e -> createCredential());

        // Opción para salir de la aplicación.
        MenuItem exitItem = new MenuItem("Salir");
        exitItem.setOnAction(e -> {
            shutdown();
            System.exit(0);
        });

        archivo.getItems().addAll(
                newItem,
                new SeparatorMenuItem(),
                exitItem
        );

        // Opciones para editar y eliminar.
        MenuItem editItem =
                new MenuItem("Editar seleccionada");

        editItem.setOnAction(e -> editCredential());

        MenuItem deleteItem =
                new MenuItem("Eliminar seleccionada");

        deleteItem.setOnAction(e -> deleteCredential());

        editar.getItems().addAll(
                editItem,
                deleteItem
        );

        menuBar.getMenus().addAll(
                archivo,
                editar,
                ver,
                herramientas,
                ayuda
        );

        return menuBar;
    }


    // ========================================================
    // 7. BARRA DE HERRAMIENTAS
    // ========================================================

    private ToolBar createToolBar() {

        Button nuevo = new Button("＋ Nueva");
        Button editar = new Button("Editar");
        Button eliminar = new Button("Eliminar");
        Button generar = new Button("Generar contraseña");

        // Aplicamos los estilos CSS.
        nuevo.getStyleClass().add("primary-button");
        eliminar.getStyleClass().add("danger-button");

        // Asociamos cada botón con su operación.
        nuevo.setOnAction(e -> createCredential());
        editar.setOnAction(e -> editCredential());
        eliminar.setOnAction(e -> deleteCredential());
        generar.setOnAction(e -> generatePassword());

        databaseSelector.setPrefWidth(125);
        databaseSelector.setTooltip(
                new Tooltip("Selecciona la base de datos que utilizará SafeVault")
        );

        Label databaseLabel = new Label("Base de datos:");

        return new ToolBar(
                nuevo,
                editar,
                eliminar,
                new Separator(),
                generar,
                new Separator(),
                databaseLabel,
                databaseSelector
        );
    }


    // ========================================================
    // 8. PANEL LATERAL DE CATEGORÍAS
    // ========================================================

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

        all.getChildren().addAll(
                personal,
                work,
                finance,
                internet,
                email
        );

        all.setExpanded(true);

        // Árbol de navegación.
        TreeView<String> tree = new TreeView<>(all);

        tree.setShowRoot(true);
        tree.setMaxWidth(Double.MAX_VALUE);

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

        Label health =
                new Label("✚  Seguridad de contraseñas");

        Label notes =
                new Label("▤  Notas seguras");

        health.getStyleClass().add("sidebar-item");
        notes.getStyleClass().add("sidebar-item");

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


    // ========================================================
    // 9. CONTENIDO CENTRAL Y BÚSQUEDA
    // ========================================================

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

        // Mostrar u ocultar la contraseña.
        revealButton.setOnAction(e -> togglePassword());

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
                new HBox(8, revealButton, copyPasswordButton),
                new Separator(),
                clipboardStatus,
                clipboardProgress,
                categoryHeading,
                detailCategory,
                new Separator(),
                info
        );

        panel.getStyleClass().add("details-panel");

        panel.setPrefWidth(270);
        panel.setMinWidth(230);

        return panel;
    }


    // ========================================================
    // 13. CRUD: CREAR UNA CREDENCIAL
    // ========================================================

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

                    if (titleField.getText().isBlank()
                            || usernameField.getText().isBlank()
                            || passwordField.getText().isBlank()
                            || categoryField.getValue() == null
                            || categoryField.getValue().isBlank()) {

                        event.consume();

                        showMessage(
                                "Datos incompletos",
                                "Rellena todos los campos antes de guardar."
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
                        categoryField.getValue().trim()
                );
            }

            return null;
        });

        // Si el usuario pulsa Guardar, insertamos en SQLite.
        dialog.showAndWait().ifPresent(credential -> {

            try {

                // Insertamos el registro en la base de datos.
                int generatedId = isMariaDBSelected()
                        ? mariaDBCredentialDAO.insert(credential)
                        : credentialDAO.insert(credential);

                // Asignamos el identificador generado por el motor activo.
                credential.setId(generatedId);

                // Actualizamos la colección de la interfaz.
                credentials.add(credential);

                refreshTable();

                table.getSelectionModel().select(credential);

                showMessage(
                        "Credencial creada",
                        "La credencial se ha guardado correctamente en "
                        + getDatabaseName() + "."
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

                    if (titleField.getText().isBlank()
                            || usernameField.getText().isBlank()
                            || passwordField.getText().isBlank()
                            || categoryField.getValue() == null
                            || categoryField.getValue().isBlank()) {

                        event.consume();

                        showMessage(
                                "Datos incompletos",
                                "Rellena todos los campos antes de guardar."
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
                        categoryField.getValue().trim()
                );
            }

            return null;
        });

        dialog.showAndWait().ifPresent(updated -> {

            try {

                // Actualizamos el registro en SQLite.
                boolean updatedSuccessfully = isMariaDBSelected()
                        ? mariaDBCredentialDAO.update(updated)
                        : credentialDAO.update(updated);

                if (!updatedSuccessfully) {

                    showMessage(
                            "Registro no encontrado",
                            "No se ha encontrado la credencial en "
                            + getDatabaseName() + "."
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

                    // Eliminamos el registro utilizando su ID.
                    boolean deleted = isMariaDBSelected()
                            ? mariaDBCredentialDAO.delete(credential.getId())
                            : credentialDAO.delete(credential.getId());

                    if (!deleted) {

                        showMessage(
                                "Registro no encontrado",
                                "No se ha encontrado la credencial en "
                            + getDatabaseName() + "."
                        );

                        loadCredentialsFromDatabase();
                        refreshTable();
                        return;
                    }

                    // Eliminamos el registro de la colección local.
                    credentials.remove(credential);

                    clearDetails();

                    refreshTable();

                    showMessage(
                            "Credencial eliminada",
                            "La credencial se ha eliminado de "
                            + getDatabaseName() + "."
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
    // 16. GENERADOR DE CONTRASEÑAS
    // ========================================================

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

    private void showDetails(CredentialRow credential) {

        selectedCredential = credential;

        // Ocultamos la contraseña cada vez que se cambia de fila.
        passwordVisible = false;

        detailTitle.setText(credential.getTitle());
        detailUser.setText(credential.getUsername());
        detailCategory.setText(credential.getCategory());

        updatePasswordDisplay();
    }


    // Limpia el panel de detalles cuando no hay selección.
    private void clearDetails() {

        selectedCredential = null;
        passwordVisible = false;

        detailTitle.setText("Selecciona una credencial");
        detailUser.setText("—");
        detailPassword.setText("—");
        detailCategory.setText("—");

        revealButton.setText("Mostrar");
    }


    // ========================================================
    // 18. MOSTRAR Y OCULTAR CONTRASEÑAS
    // ========================================================

    private void togglePassword() {

        if (selectedCredential == null) {
            return;
        }

        passwordVisible = !passwordVisible;

        updatePasswordDisplay();
    }


    private void updatePasswordDisplay() {

        if (selectedCredential == null) {

            detailPassword.setText("—");
            revealButton.setText("Mostrar");

            return;
        }

        if (passwordVisible) {

            // Mostramos el valor real.
            detailPassword.setText(
                    selectedCredential.getPassword()
            );

            revealButton.setText("Ocultar");

        } else {

            // Sustituimos los caracteres por puntos.
            detailPassword.setText(
                    "•".repeat(
                            selectedCredential.getPassword().length()
                    )
            );

            revealButton.setText("Mostrar");
        }
    }


    // ========================================================
    // 19. COPIAR INFORMACIÓN AL PORTAPAPELES
    // ========================================================

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
         * exactamente el valor que copió SafeVault.
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

    private void stopClipboardTimer() {

        if (clipboardTimeline != null) {

            clipboardTimeline.stop();

            clipboardTimeline = null;
        }
    }


    // ========================================================
    // 22. LIBERAR RECURSOS AL CERRAR LA APLICACIÓN
    // ========================================================

    public void shutdown() {

        // Detenemos el temporizador.
        stopClipboardTimer();

        // Intentamos limpiar nuestro contenido del portapapeles.
        if (lastCopiedValue != null) {

            Clipboard clipboard =
                    Clipboard.getSystemClipboard();

            try {

                if (clipboard.hasString()
                        && lastCopiedValue.equals(clipboard.getString())) {

                    clipboard.clear();
                }

            } catch (IllegalStateException ex) {

                // El portapapeles puede estar temporalmente ocupado.
            }
        }

        lastCopiedValue = null;
    }


    // ========================================================
    // 23. VENTANAS DE INFORMACIÓN
    // ========================================================

    private void showMessage(String title, String message) {

        Alert alert =
                new Alert(Alert.AlertType.INFORMATION);

        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);

        alert.showAndWait();
    }


    // ========================================================
    // 24. MODELO DE DATOS: CREDENTIALROW
    // ========================================================

    /*
     * Esta clase representa una credencial de SafeVault.
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

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }


        // ---------------- NOMBRE ----------------

        public String getTitle() {
            return title.get();
        }

        public void setTitle(String value) {
            title.set(value);
        }

        public StringProperty titleProperty() {
            return title;
        }


        // ---------------- USUARIO ----------------

        public String getUsername() {
            return username.get();
        }

        public void setUsername(String value) {
            username.set(value);
        }

        public StringProperty usernameProperty() {
            return username;
        }


        // ---------------- CONTRASEÑA ----------------

        public String getPassword() {
            return password.get();
        }

        public void setPassword(String value) {
            password.set(value);
        }

        public StringProperty passwordProperty() {
            return password;
        }


        // ---------------- CATEGORÍA ----------------

        public String getCategory() {
            return category.get();
        }

        public void setCategory(String value) {
            category.set(value);
        }

        public StringProperty categoryProperty() {
            return category;
        }
    }
}
