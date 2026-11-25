/*
 * ============================================================================
 * VAULTIX - PANTALLA DE INICIO DE SESIÓN Y CONFIGURACIÓN INICIAL
 * ============================================================================
 *
 * Este archivo contiene la clase LoginView, responsable de construir la
 * pantalla que aparece cuando se inicia Vaultix.
 *
 * IMPORTANTE:
 * - Esta clase pertenece a la capa de presentación (interfaz gráfica).
 * - No abre conexiones con SQLite.
 * - No cifra ni descifra contraseñas.
 * - No calcula claves criptográficas.
 * - Recoge los datos del usuario, realiza comprobaciones básicas del formulario
 *   y comunica el resultado a la clase principal mediante un callback.
 *
 * Separar la interfaz de la lógica de seguridad facilita el mantenimiento:
 * podemos cambiar el diseño sin tener que modificar el sistema criptográfico.
 * ============================================================================
 */
package com.safevault.view;


/*
 * DOCUMENTACIÓN COMPLEMENTARIA PARA EL ESTUDIO DE VAULTIX
 *
 * LoginView es la capa visual de la autenticación. Su responsabilidad es
 * recoger los datos, mostrar validaciones y notificar el envío del formulario.
 * La autenticación real se delega a AuthenticationManager mediante el callback.
 *
 * Modos de funcionamiento:
 * - Primera ejecución: muestra los campos para crear la bóveda y confirmar
 *   la contraseña maestra.
 * - Uso habitual: muestra los campos necesarios para desbloquearla.
 *
 * El Label statusLabel implementa el mensaje integrado en la interfaz exigido
 * por el ejercicio. No debe confundirse con un Alert, que abre una ventana
 * emergente independiente.
 */

import java.util.Arrays;
import java.util.function.BiConsumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Pantalla de configuración inicial e inicio de sesión de Vaultix.
 *
 * Esta clase se ocupa únicamente de la interfaz gráfica y de recoger los
 * datos introducidos. No accede a SQLite ni realiza operaciones criptográficas.
 *
 * La clase principal decide qué operación ejecutar mediante el callback
 * establecido con setOnLogin():
 *
 * - Primera ejecución: AuthenticationManager.createVault(...)
 * - Ejecuciones posteriores: AuthenticationManager.authenticate(...)
 *
 * En ambos casos, la vista entrega el nombre de usuario y un char[] con la
 * contraseña maestra. El array se limpia al terminar el callback.
 */
public class LoginView {

    // -----------------------------------------------------------------
    // 1. CONTENEDORES Y CONTROLES DE LA INTERFAZ
    // -----------------------------------------------------------------

    /** Nodo raíz que se incorpora a la Scene de JavaFX. */
    /*
     * StackPane es el contenedor raíz de esta pantalla.
     *
     * Un StackPane permite colocar nodos unos encima de otros. En este caso
     * contiene el panel principal y facilita centrarlo dentro de la ventana.
     *
     * final significa que la referencia root no se reasignará a otro objeto;
     * sí podremos modificar sus propiedades y sus nodos internos.
     */
    private final StackPane root = new StackPane();

    /** Panel central que contiene todo el formulario. */
    /*
     * Panel principal del formulario.
     *
     * VBox organiza sus elementos verticalmente. El valor 18 establece el
     * espacio de separación, en píxeles lógicos, entre los elementos hijos.
     */ 
    private final VBox loginPanel = new VBox(18);

    /** Campo del único usuario principal de Vaultix. */
    private final TextField usernameField = new TextField();

    /** Campo protegido para introducir la contraseña maestra. */
    private final PasswordField masterPasswordField = new PasswordField();

    /**
     * Campo adicional que solo se muestra durante la configuración inicial.
     * Permite comprobar que el usuario no se ha equivocado al escribir
     * la contraseña maestra.
     */
    private final PasswordField confirmPasswordField = new PasswordField();

    /** Etiquetas cuyo texto cambia según sea configuración o acceso. */
    private final Label headingLabel = new Label();
    private final Label descriptionLabel = new Label();
    private final Label passwordLabel = new Label();

    /** Botón principal del formulario. */
    private final Button loginButton = new Button();

    /** Zona para mostrar errores e indicaciones. */
    /*
     * MENSAJE INTEGRADO EN LA INTERFAZ (PRIMERA METODOLOGÍA DEL EJERCICIO).
     *
     * Este Label se utiliza para informar al usuario sin abrir una ventana
     * independiente. Por ejemplo:
     * - "Introduce tu nombre de usuario."
     * - "La contraseña maestra no es correcta."
     * - "La operación se ha completado correctamente."
     *
     * El mensaje aparece dentro del panel de acceso, debajo del botón
     * principal. Su color y apariencia se controlan mediante clases CSS.
     *
     * Al principio está oculto. Los métodos showError() y showMessage()
     * lo hacen visible cuando existe información que mostrar.
     */
    private final Label statusLabel = new Label();

    /** Panel que muestra los requisitos de la contraseña durante la escritura. */
    private final VBox passwordHelpBlock = new VBox(6);

    /** Indicadores individuales de cumplimiento de la política de contraseña. */
    private final Label lengthRuleLabel = new Label();
    private final Label uppercaseRuleLabel = new Label();
    private final Label lowercaseRuleLabel = new Label();
    private final Label digitRuleLabel = new Label();
    private final Label specialRuleLabel = new Label();
    private final Label usernameRuleLabel = new Label();

    /** Indicador textual y barra de fortaleza orientativa. */
    private final Label strengthLabel = new Label("Fortaleza: pendiente");
    private final HBox strengthBar = new HBox(4);
    private final Region[] strengthSegments = new Region[4];

    /** Mensaje específico de coincidencia de la confirmación. */
    private final Label confirmationStatusLabel = new Label();

    /** Bloques de campos que se muestran u ocultan según el modo. */
    private final VBox confirmPasswordBlock = new VBox(7);

    /** Columnas del formulario de configuración inicial. */
    private final HBox registrationColumns = new HBox(24);
    private final VBox registrationLeftColumn = new VBox(16);
    private final VBox registrationRightColumn = new VBox(12);

    /**
     * true: primera ejecución, hay que crear la bóveda.
     * false: la bóveda ya existe, hay que desbloquearla.
     */
    private boolean firstRunMode;

    /**
     * Acción que ejecutará la clase principal al pulsar el botón.
     *
     * Recibe el nombre de usuario y un array de caracteres con la contraseña.
     * El callback debe procesar la contraseña inmediatamente y no conservarla.
     */
    private BiConsumer<String, char[]> loginHandler;

    // -----------------------------------------------------------------
    // 2. CONSTRUCTOR
    // -----------------------------------------------------------------

    /**
     * Construye la interfaz y conecta los eventos.
     *
     * Por defecto se prepara en modo inicio de sesión. La clase principal
     * debe llamar a setFirstRunMode(true) cuando detecte que todavía no
     * existe una bóveda configurada.
     */
    public LoginView() {
        configureRoot();
        createInterface();
        configureEvents();
        setFirstRunMode(false);
    }

    // -----------------------------------------------------------------
    // 3. CONFIGURACIÓN GENERAL
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Configura el contenedor raíz, sus dimensiones mínimas, márgenes y clases CSS.
    /** Configura el fondo, el tamaño mínimo y la alineación del formulario. */
    private void configureRoot() {
        root.getStyleClass().add("vaultix-login-root");
        root.setPadding(new Insets(30));
        root.setMinSize(760, 600);
    }

    // NOTA DIDÁCTICA: Crea los controles de registro e inicio de sesión y los organiza según el modo activo.
    /**
     * Construye los controles de la pantalla.
     *
     * Se mantienen las clases CSS utilizadas por el diseño anterior de
     * Vaultix para conservar su apariencia.
     */
    private void createInterface() {

        // ---------------- IDENTIDAD VISUAL ----------------

        /*
         * Logotipo de Vaultix.
         *
         * Se intenta cargar el recurso gráfico:
         * src/main/resources/images/vaultix-logo.png
         *
         * Si el archivo todavía no existe, se muestra un símbolo provisional
         * para que la pantalla siga funcionando sin lanzar excepciones.
         */
        javafx.scene.Node logoSymbol = createLogoNode();

        // El PNG definitivo ya contiene el escudo, el nombre VAULTIX
        // y el subtítulo. No añadimos etiquetas duplicadas debajo.
        VBox identityBlock = new VBox(0, logoSymbol);
        identityBlock.setAlignment(Pos.CENTER);

        // ---------------- TÍTULO Y DESCRIPCIÓN ----------------

        headingLabel.getStyleClass().add("vaultix-login-heading");

        descriptionLabel.getStyleClass().add("vaultix-login-description");
        descriptionLabel.setWrapText(true);
        descriptionLabel.setMaxWidth(340);
        descriptionLabel.setAlignment(Pos.CENTER);

        VBox welcomeBlock = new VBox(7, headingLabel, descriptionLabel);
        welcomeBlock.setAlignment(Pos.CENTER);

        // ---------------- NOMBRE DE USUARIO ----------------

        Label usernameLabel = new Label("USUARIO");
        usernameLabel.getStyleClass().add("vaultix-login-field-label");

        usernameField.setPromptText("Nombre de usuario");
        usernameField.setAccessibleText("Nombre de usuario");
        usernameField.setMaxWidth(Double.MAX_VALUE);
        usernameField.getStyleClass().add("vaultix-login-input");

        VBox usernameBlock = new VBox(7, usernameLabel, usernameField);

        // ---------------- CONTRASEÑA MAESTRA ----------------

        passwordLabel.getStyleClass().add("vaultix-login-field-label");

        masterPasswordField.setPromptText("Introduce tu contraseña maestra");
        masterPasswordField.setAccessibleText("Contraseña maestra");
        masterPasswordField.setMaxWidth(Double.MAX_VALUE);
        masterPasswordField.getStyleClass().add("vaultix-login-input");

        VBox passwordBlock = new VBox(7, passwordLabel, masterPasswordField);

        // Ayuda contextual: explica la política sin ocupar espacio permanentemente.
        Label passwordInfoLabel = new Label("i");
        passwordInfoLabel.getStyleClass().add("vaultix-password-info-icon");
        passwordInfoLabel.setTooltip(new Tooltip(
                "La contraseña maestra debe tener entre 12 y 20 caracteres, "
                + "incluir mayúsculas, minúsculas, números y caracteres especiales. "
                + "No puede contener tu nombre de usuario."
        ));

        Label passwordHelpTitle = new Label("Requisitos de la contraseña");
        passwordHelpTitle.getStyleClass().add("vaultix-password-help-title");

        HBox passwordHelpHeading = new HBox(8, passwordHelpTitle, passwordInfoLabel);
        passwordHelpHeading.setAlignment(Pos.CENTER_LEFT);

        configureRuleLabel(lengthRuleLabel, "12 a 20 caracteres");
        configureRuleLabel(uppercaseRuleLabel, "Una letra mayúscula");
        configureRuleLabel(lowercaseRuleLabel, "Una letra minúscula");
        configureRuleLabel(digitRuleLabel, "Al menos un número");
        configureRuleLabel(specialRuleLabel, "Al menos un carácter especial");
        configureRuleLabel(usernameRuleLabel, "No contiene el nombre de usuario");

        passwordHelpBlock.getStyleClass().add("vaultix-password-help");
        passwordHelpBlock.getChildren().addAll(
                passwordHelpHeading,
                lengthRuleLabel,
                uppercaseRuleLabel,
                lowercaseRuleLabel,
                digitRuleLabel,
                specialRuleLabel,
                usernameRuleLabel
        );

        // Barra de cuatro segmentos para mostrar la fortaleza aproximada.
        strengthBar.getStyleClass().add("vaultix-password-strength-bar");
        for (int i = 0; i < strengthSegments.length; i++) {
            Region segment = new Region();
            segment.getStyleClass().add("vaultix-password-strength-segment");
            segment.setPrefHeight(5);
            segment.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(segment, javafx.scene.layout.Priority.ALWAYS);
            strengthSegments[i] = segment;
            strengthBar.getChildren().add(segment);
        }
        strengthLabel.getStyleClass().add("vaultix-password-strength-label");

        VBox strengthBlock = new VBox(5, strengthBar, strengthLabel);
        strengthBlock.getStyleClass().add("vaultix-password-strength-block");

        // La confirmación muestra en rojo si no coincide y en verde cuando coincide.
        confirmationStatusLabel.getStyleClass().add("vaultix-confirmation-status");
        confirmationStatusLabel.setVisible(false);
        confirmationStatusLabel.setManaged(false);

        // ---------------- CONFIRMACIÓN DE CONTRASEÑA ----------------

        Label confirmLabel = new Label("CONFIRMAR CONTRASEÑA MAESTRA");
        confirmLabel.getStyleClass().add("vaultix-login-field-label");

        confirmPasswordField.setPromptText("Repite la contraseña maestra");
        confirmPasswordField.setAccessibleText("Confirmación de contraseña maestra");
        confirmPasswordField.setMaxWidth(Double.MAX_VALUE);
        confirmPasswordField.getStyleClass().add("vaultix-login-input");

        confirmPasswordBlock.getChildren().addAll(
                confirmLabel,
                confirmPasswordField,
                confirmationStatusLabel
        );

        // ---------------- BOTÓN PRINCIPAL ----------------

        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setMinHeight(46);
        loginButton.setDefaultButton(true);
        loginButton.getStyleClass().add("vaultix-login-button");

        // ---------------- MENSAJES DE ESTADO ----------------

        statusLabel.getStyleClass().add("vaultix-login-status");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(340);
        statusLabel.setMinHeight(Region.USE_PREF_SIZE);
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        // ---------------- PIE DE PANTALLA ----------------

        Label securityNote = new Label("BÓVEDA LOCAL · ACCESO PROTEGIDO");
        securityNote.getStyleClass().add("vaultix-login-security-note");

        Label copyright = new Label("© " + java.time.Year.now().getValue() + " CascalesDevelop");
        copyright.getStyleClass().add("vaultix-login-copyright");

        VBox footer = new VBox(7, securityNote, copyright);
        footer.setAlignment(Pos.CENTER);

        // ---------------- COMPOSICIÓN FINAL ----------------

        /*
         * Distribución en dos columnas para la configuración inicial:
         * - Izquierda: usuario, contraseña y confirmación.
         * - Derecha: requisitos y fortaleza de la contraseña.
         *
         * Así evitamos que el formulario crezca excesivamente en vertical.
         */
        registrationLeftColumn.getStyleClass().add("vaultix-registration-column");
        registrationLeftColumn.getChildren().addAll(
                usernameBlock,
                passwordBlock,
                confirmPasswordBlock
        );
        registrationLeftColumn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(registrationLeftColumn, javafx.scene.layout.Priority.ALWAYS);

        registrationRightColumn.getStyleClass().add("vaultix-registration-column");
        registrationRightColumn.getChildren().addAll(
                passwordHelpBlock,
                strengthBlock
        );
        registrationRightColumn.setMinWidth(270);
        registrationRightColumn.setPrefWidth(310);
        registrationRightColumn.setMaxWidth(340);

        registrationColumns.getStyleClass().add("vaultix-registration-columns");
        registrationColumns.getChildren().addAll(
                registrationLeftColumn,
                registrationRightColumn
        );
        registrationColumns.setAlignment(Pos.TOP_CENTER);
        registrationColumns.setMaxWidth(Double.MAX_VALUE);

        loginPanel.getStyleClass().add("vaultix-login-panel");
        loginPanel.setAlignment(Pos.CENTER);
        loginPanel.setPadding(new Insets(30, 36, 26, 36));
        loginPanel.setMaxWidth(880);
        loginPanel.setMinWidth(420);

        /*
         * ORDEN DE LOS ELEMENTOS DEL PANEL:
         * 1. Identidad visual y logotipo.
         * 2. Título y descripción.
         * 3. Campos del formulario y ayuda de contraseña.
         * 4. Botón para crear o desbloquear la bóveda.
         * 5. statusLabel: mensaje integrado de error o información.
         * 6. Pie de pantalla.
         *
         * La posición de statusLabel justo después del botón hace que el
         * resultado de la operación se vea cerca de la acción que lo provoca.
         */
        loginPanel.getChildren().addAll(
                identityBlock,
                welcomeBlock,
                registrationColumns,
                loginButton,
                statusLabel,
                footer
        );

        root.getChildren().add(loginPanel);
        StackPane.setAlignment(loginPanel, Pos.CENTER);
    }

    // NOTA DIDÁCTICA: Carga el logotipo desde los recursos del proyecto y proporciona una alternativa visual si el recurso no está disponible.
    /**
     * Crea el componente visual del logotipo.
     *
     * El archivo PNG debe estar dentro de los recursos de Maven:
     * src/main/resources/images/vaultix-logo.png
     *
     * ImageView permite mostrar la imagen sin convertirla en un fondo CSS.
     * Si el recurso no está instalado todavía, se utiliza una V provisional.
     */
    private javafx.scene.Node createLogoNode() {

        /*
         * Ruta relativa al classpath de la aplicación, no al disco C:.
         * Maven copia src/main/resources al classpath al compilar.
         * Por eso el recurso se solicita como /images/vaultix-logo.png.
         */
        var logoUrl = getClass().getResource("/images/vaultix-logo.png");

        if (logoUrl != null) {
            // La carga síncrona permite comprobar inmediatamente si el PNG
            // se ha podido abrir; el archivo es pequeño y forma parte del programa.
            Image logoImage = new Image(logoUrl.toExternalForm(), false);

            if (!logoImage.isError()) {
                ImageView logoView = new ImageView(logoImage);
                // El logotipo es horizontal e incluye el nombre y el lema.
                // Se muestra ancho para que el escudo y el texto sean legibles.
                logoView.setFitWidth(460);
                logoView.setFitHeight(150);
                logoView.setPreserveRatio(true);
                logoView.setSmooth(true);
                logoView.setAccessibleText("Logotipo de Vaultix");

                StackPane imageContainer = new StackPane(logoView);
                imageContainer.getStyleClass().add("vaultix-login-logo-container");
                imageContainer.setMinSize(460, 150);
                imageContainer.setPrefSize(460, 150);
                imageContainer.setMaxSize(460, 150);

                return imageContainer;
            }

            System.err.println(
                    "Vaultix: no se pudo cargar /images/vaultix-logo.png: "
                    + logoImage.getException()
            );
        } else {
            System.err.println(
                    "Vaultix: no se encontró /images/vaultix-logo.png "
                    + "en los recursos de la aplicación."
            );
        }

        // Alternativa provisional si el recurso falta o el PNG está dañado.
        Label logoLetter = new Label("V");
        logoLetter.getStyleClass().add("vaultix-login-logo-letter");

        StackPane fallback = new StackPane(logoLetter);
        fallback.getStyleClass().add("vaultix-login-logo");
        fallback.setMinSize(100, 100);
        fallback.setPrefSize(100, 100);
        fallback.setMaxSize(100, 100);

        return fallback;
    }

    // -----------------------------------------------------------------
    // 4. EVENTOS
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Registra los manejadores de eventos de teclado, botones y cambios de campos.
    /** Conecta los eventos de ratón y teclado. */
    private void configureEvents() {
        /*
         * Los eventos conectan las acciones del usuario con el comportamiento
         * de la aplicación. Sin estos manejadores, los botones y campos serían
         * únicamente elementos visuales.
         */

        // El botón y la tecla Intro utilizan el mismo método de validación.
        loginButton.setOnAction(event -> requestLogin());

        /*
         * Cada vez que cambia el contenido de un campo se oculta el mensaje
         * anterior. Así, si el usuario corrige un dato, no dejamos en pantalla
         * un error que puede haber quedado desactualizado.
         *
         * En modo de configuración inicial también se recalculan los requisitos
         * de la contraseña, su fortaleza aproximada y la coincidencia de ambas
         * contraseñas. En el inicio de sesión normal, ese cálculo no se ejecuta.
         */
        // En los tres campos se actualiza la vista cuando cambia su contenido.
        masterPasswordField.textProperty().addListener((observable, oldValue, newValue) -> {
            hideStatus();
            updatePasswordFeedback();
        });
        confirmPasswordField.textProperty().addListener((observable, oldValue, newValue) -> {
            hideStatus();
            updatePasswordFeedback();
        });
        usernameField.textProperty().addListener((observable, oldValue, newValue) -> {
            hideStatus();
            updatePasswordFeedback();
        });

        usernameField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                masterPasswordField.requestFocus();
            }
        });

        masterPasswordField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                if (firstRunMode) {
                    confirmPasswordField.requestFocus();
                } else {
                    requestLogin();
                }
            }
        });

        confirmPasswordField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                requestLogin();
            }
        });
    }

    // -----------------------------------------------------------------
    // 5. SELECCIÓN DEL MODO DE PANTALLA
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Alterna entre el formulario de creación inicial de la bóveda y el formulario habitual de inicio de sesión.
    /**
     * Cambia entre la configuración inicial y el inicio de sesión habitual.
     *
     * En configuración inicial se solicita confirmar la contraseña y se
     * informa de que debe tener al menos 12 caracteres, requisito que también
     * comprueba AuthenticationManager.
     *
     * @param firstRun true si todavía no se ha creado la bóveda.
     */
    public void setFirstRunMode(boolean firstRun) {
        this.firstRunMode = firstRun;

        clearFields();

        if (firstRun) {
            headingLabel.setText("Configura tu bóveda");
            descriptionLabel.setText(
                    "Crea tu usuario y una contraseña maestra para proteger "
                    + "todas tus credenciales. Necesitarás esta contraseña "
                    + "cada vez que abras Vaultix."
            );

            passwordLabel.setText("CONTRASEÑA MAESTRA");
            masterPasswordField.setPromptText("Entre 12 y 20 caracteres");

            passwordHelpBlock.setVisible(true);
            passwordHelpBlock.setManaged(true);
            strengthBar.setVisible(true);
            strengthBar.setManaged(true);
            strengthLabel.setVisible(true);
            strengthLabel.setManaged(true);

            confirmPasswordBlock.setVisible(true);
            confirmPasswordBlock.setManaged(true);
            registrationRightColumn.setVisible(true);
            registrationRightColumn.setManaged(true);
            registrationColumns.setMaxWidth(800);
            loginPanel.setMaxWidth(880);

            loginButton.setText("CREAR BÓVEDA");
            updatePasswordFeedback();
        } else {
            headingLabel.setText("Bienvenido");
            descriptionLabel.setText(
                    "Introduce tus credenciales para desbloquear tu bóveda."
            );

            passwordLabel.setText("CONTRASEÑA MAESTRA");
            masterPasswordField.setPromptText("Introduce tu contraseña maestra");

            // Las reglas de creación solo se muestran en la primera configuración.
            passwordHelpBlock.setVisible(false);
            passwordHelpBlock.setManaged(false);
            strengthBar.setVisible(false);
            strengthBar.setManaged(false);
            strengthLabel.setVisible(false);
            strengthLabel.setManaged(false);

            confirmPasswordBlock.setVisible(false);
            confirmPasswordBlock.setManaged(false);
            registrationRightColumn.setVisible(false);
            registrationRightColumn.setManaged(false);
            registrationColumns.setMaxWidth(390);
            loginPanel.setMaxWidth(460);

            loginButton.setText("DESBLOQUEAR VAULTIX");
        }
    }

    // -----------------------------------------------------------------
    // 6. VALIDACIÓN Y ENTREGA DE CREDENCIALES
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Recoge los datos del formulario, realiza las comprobaciones de interfaz y entrega la contraseña como char[] al callback.
    /**
     * Valida los campos y entrega los datos al AuthenticationManager
     * indirectamente, a través del callback configurado por SafeVaultDAM.
     *
     * La vista realiza validaciones de formulario; la comprobación real de
     * usuario y contraseña corresponde siempre a AuthenticationManager.
     */
    private void requestLogin() {

        String username = usernameField.getText() == null
                ? ""
                : usernameField.getText().trim();

        String passwordText = masterPasswordField.getText() == null
                ? ""
                : masterPasswordField.getText();

        String confirmationText = confirmPasswordField.getText() == null
                ? ""
                : confirmPasswordField.getText();

        // El usuario es obligatorio en ambos modos.
        if (username.isEmpty()) {
            showError("Introduce tu nombre de usuario.");
            usernameField.requestFocus();
            return;
        }

        if (username.length() < 3) {
            showError("El nombre de usuario debe tener al menos 3 caracteres.");
            usernameField.requestFocus();
            return;
        }

        if (passwordText.isEmpty()) {
            showError("Introduce tu contraseña maestra.");
            masterPasswordField.requestFocus();
            return;
        }

        // Durante la configuración inicial se aplica toda la política de seguridad.
        if (firstRunMode) {
            String passwordProblem = getPasswordPolicyError(username, passwordText);
            if (passwordProblem != null) {
                showError(passwordProblem);
                masterPasswordField.requestFocus();
                return;
            }

            if (!passwordText.equals(confirmationText)) {
                showError("Las contraseñas no coinciden. Comprueba ambos campos.");
                updatePasswordFeedback();
                confirmPasswordField.requestFocus();
                return;
            }
        }

        if (loginHandler == null) {
            showError("La pantalla todavía no está conectada con la autenticación.");
            return;
        }

        // Se crea un array mutable para que pueda borrarse de memoria al terminar.
        char[] password = passwordText.toCharArray();

        // Eliminamos las contraseñas de los controles cuanto antes.
        masterPasswordField.clear();
        confirmPasswordField.clear();
        confirmationStatusLabel.setText("");
        confirmationStatusLabel.setVisible(false);
        confirmationStatusLabel.setManaged(false);
        hideStatus();

        loginButton.setDisable(true);

        try {
            // La clase principal ejecutará createVault o authenticate.
            loginHandler.accept(username, password);

        } catch (RuntimeException exception) {
            showError(
                    "No se ha podido procesar la operación. "
                    + "Comprueba los datos e inténtalo de nuevo."
            );
            throw exception;

        } finally {
            // Limpieza de la copia de contraseña gestionada por esta vista.
            Arrays.fill(password, '\0');

            // Se reactiva el botón. Si el Dashboard se ha abierto, esta vista
            // ya no estará visible; si hubo un error, permitirá reintentar.
            loginButton.setDisable(false);
        }
    }

    // -----------------------------------------------------------------
    // 7. CONEXIÓN CON LA CLASE PRINCIPAL
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Prepara una etiqueta de requisito para mostrar si una regla de contraseña se cumple.
    /** Configura el aspecto inicial de una regla de contraseña. */
    private void configureRuleLabel(Label label, String description) {
        label.setText("○  " + description);
        label.getStyleClass().add("vaultix-password-rule-pending");
    }

    // NOTA DIDÁCTICA: Recalcula en tiempo real los requisitos y la fortaleza orientativa de la contraseña introducida.
    /**
     * Recalcula todos los indicadores a partir del texto actual.
     * Este análisis es orientativo y nunca sustituye la validación del servidor
     * local de autenticación (AuthenticationManager).
     */
    private void updatePasswordFeedback() {
        if (!firstRunMode) {
            return;
        }

        String password = masterPasswordField.getText() == null
                ? "" : masterPasswordField.getText();
        String username = usernameField.getText() == null
                ? "" : usernameField.getText().trim();
        String confirmation = confirmPasswordField.getText() == null
                ? "" : confirmPasswordField.getText();

        boolean lengthOk = password.length() >= 12 && password.length() <= 20;
        boolean upperOk = password.codePoints().anyMatch(Character::isUpperCase);
        boolean lowerOk = password.codePoints().anyMatch(Character::isLowerCase);
        boolean digitOk = password.codePoints().anyMatch(Character::isDigit);
        boolean specialOk = password.codePoints().anyMatch(c -> !Character.isLetterOrDigit(c));
        boolean usernameOk = username.isEmpty()
                || !password.toLowerCase(java.util.Locale.ROOT)
                        .contains(username.toLowerCase(java.util.Locale.ROOT));

        setRuleState(lengthRuleLabel, lengthOk, "12 a 20 caracteres");
        setRuleState(uppercaseRuleLabel, upperOk, "Una letra mayúscula");
        setRuleState(lowercaseRuleLabel, lowerOk, "Una letra minúscula");
        setRuleState(digitRuleLabel, digitOk, "Al menos un número");
        setRuleState(specialRuleLabel, specialOk, "Al menos un carácter especial");
        setRuleState(usernameRuleLabel, usernameOk, "No contiene el nombre de usuario");

        int score = 0;
        if (password.length() >= 12) score++;
        if (password.length() <= 20 && password.length() >= 12) score++;
        if (upperOk) score++;
        if (lowerOk) score++;
        if (digitOk) score++;
        if (specialOk) score++;
        if (usernameOk && !username.isEmpty()) score++;
        score = Math.min(4, score * 4 / 7);

        String strengthText;
        if (password.isEmpty()) {
            strengthText = "Fortaleza: pendiente";
        } else if (password.length() > 20 || (!username.isEmpty() && !usernameOk)) {
            strengthText = "Fortaleza: no válida";
            score = 0;
        } else if (score <= 1) {
            strengthText = "Fortaleza: débil";
        } else if (score == 2) {
            strengthText = "Fortaleza: media";
        } else if (score == 3) {
            strengthText = "Fortaleza: buena";
        } else {
            strengthText = "Fortaleza: alta";
        }
        strengthLabel.setText(strengthText);

        for (int i = 0; i < strengthSegments.length; i++) {
            strengthSegments[i].getStyleClass().removeAll(
                    "vaultix-strength-active-1", "vaultix-strength-active-2",
                    "vaultix-strength-active-3", "vaultix-strength-active-4"
            );
            if (i < score) {
                strengthSegments[i].getStyleClass().add(
                        "vaultix-strength-active-" + score
                );
            }
        }

        if (confirmation.isEmpty()) {
            confirmationStatusLabel.setText("");
            confirmationStatusLabel.setVisible(false);
            confirmationStatusLabel.setManaged(false);
            confirmationStatusLabel.getStyleClass().removeAll(
                    "vaultix-confirmation-error", "vaultix-confirmation-success"
            );
        } else if (password.equals(confirmation)) {
            confirmationStatusLabel.setText("✓  Las contraseñas coinciden");
            setConfirmationStyle("vaultix-confirmation-success");
        } else {
            confirmationStatusLabel.setText("✕  Las contraseñas no coinciden");
            setConfirmationStyle("vaultix-confirmation-error");
        }
    }

    // NOTA DIDÁCTICA: Actualiza el aspecto de un indicador de regla según se cumpla o no la condición.
    /** Cambia el tick y el color de una regla según se cumpla. */
    private void setRuleState(Label label, boolean valid, String description) {
        label.setText((valid ? "✓  " : "○  ") + description);
        label.getStyleClass().removeAll(
                "vaultix-password-rule-pending", "vaultix-password-rule-valid",
                "vaultix-password-rule-invalid"
        );
        label.getStyleClass().add(valid
                ? "vaultix-password-rule-valid"
                : "vaultix-password-rule-invalid");
    }

    // NOTA DIDÁCTICA: Actualiza el mensaje y el estilo visual de la confirmación de contraseña.
    /** Aplica el estilo del mensaje de coincidencia. */
    private void setConfirmationStyle(String styleClass) {
        confirmationStatusLabel.getStyleClass().removeAll(
                "vaultix-confirmation-error", "vaultix-confirmation-success"
        );
        confirmationStatusLabel.getStyleClass().add(styleClass);
        confirmationStatusLabel.setVisible(true);
        confirmationStatusLabel.setManaged(true);
    }

    // NOTA DIDÁCTICA: Devuelve el motivo por el que la contraseña incumple la política, o null cuando las comprobaciones de interfaz se superan.
    /**
     * Devuelve el primer incumplimiento de la política.
     * La misma política se comprueba también en AuthenticationManager.
     */
    private String getPasswordPolicyError(String username, String password) {
        if (password.length() < 12) {
            return "La contraseña maestra debe tener al menos 12 caracteres.";
        }
        if (password.length() > 20) {
            return "La contraseña maestra no puede superar los 20 caracteres.";
        }
        if (password.codePoints().noneMatch(Character::isUpperCase)) {
            return "Añade al menos una letra mayúscula.";
        }
        if (password.codePoints().noneMatch(Character::isLowerCase)) {
            return "Añade al menos una letra minúscula.";
        }
        if (password.codePoints().noneMatch(Character::isDigit)) {
            return "Añade al menos un número.";
        }
        if (password.codePoints().noneMatch(c -> !Character.isLetterOrDigit(c))) {
            return "Añade al menos un carácter especial.";
        }
        if (!username.isEmpty() && password.toLowerCase(java.util.Locale.ROOT)
                .contains(username.toLowerCase(java.util.Locale.ROOT))) {
            return "La contraseña no puede contener tu nombre de usuario.";
        }
        return null;
    }

    // NOTA DIDÁCTICA: Registra el callback que la clase principal ejecutará cuando el usuario envíe el formulario.
    /**
     * Establece la operación que se ejecutará al enviar el formulario.
     *
     * SafeVaultDAM recibe el usuario y la contraseña y decide si debe llamar
     * a AuthenticationManager.createVault() o authenticate(), según el modo.
     *
     * @param handler callback que recibe el usuario y el array de contraseña.
     */
    public void setOnLogin(BiConsumer<String, char[]> handler) {
        this.loginHandler = handler;
    }

    // -----------------------------------------------------------------
    // 8. MENSAJES PARA EL USUARIO
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Presenta un mensaje de error dentro de la propia pantalla, sin abrir una ventana emergente.
    /**
     * Muestra un mensaje de error DENTRO de la propia pantalla de Vaultix.
     *
     * Este método implementa la primera metodología solicitada en el ejercicio:
     * mostrar información mediante un cuadro de texto integrado en la interfaz.
     *
     * No crea una ventana nueva. Modifica el Label statusLabel que ya forma
     * parte del panel principal.
     *
     * @param message texto que explica el problema detectado y, cuando es
     *                posible, orienta al usuario sobre cómo solucionarlo.
     */
    public void showError(String message) {
        // setText() sustituye el contenido anterior por el nuevo mensaje.
        statusLabel.setText(message);
        // Primero se elimina el estilo de éxito para evitar mezclar estados.
        statusLabel.getStyleClass().remove("vaultix-login-status-success");

        // Se añade la clase de error solo si todavía no está aplicada.
        // Evitamos duplicar la misma clase CSS en la lista de estilos.
        if (!statusLabel.getStyleClass().contains("vaultix-login-status-error")) {
            statusLabel.getStyleClass().add("vaultix-login-status-error");
        }

        /*
         * En JavaFX, visible controla si el nodo se dibuja.
         * managed controla si el contenedor reserva espacio para él.
         *
         * Se activan ambas propiedades para que el mensaje aparezca y el
         * VBox ajuste su distribución teniendo en cuenta el espacio ocupado.
         */
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
    }

    // NOTA DIDÁCTICA: Presenta un mensaje informativo integrado en la interfaz.
    /**
     * Muestra un mensaje informativo o de confirmación dentro de la interfaz.
     *
     * Comparte el mismo Label que showError(), pero utiliza una clase CSS
     * diferente para que el usuario distinga visualmente una confirmación
     * de un error.
     *
     * @param message información que se desea comunicar al usuario.
     */
    public void showMessage(String message) {
        statusLabel.setText(message);
        // Quitamos el estilo de error antes de aplicar el estilo informativo.
        statusLabel.getStyleClass().remove("vaultix-login-status-error");

        // La clase success permite presentar el mensaje como confirmación.
        if (!statusLabel.getStyleClass().contains("vaultix-login-status-success")) {
            statusLabel.getStyleClass().add("vaultix-login-status-success");
        }

        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
    }

    // NOTA DIDÁCTICA: Oculta el mensaje de estado y evita que el espacio reservado siga ocupando sitio en el diseño.
    /**
     * Oculta y limpia el mensaje de estado actual.
     *
     * Se utiliza, por ejemplo, cuando el usuario modifica un campo después
     * de recibir un error. Así no dejamos visible una advertencia antigua.
     *
     * También se llama antes de iniciar una nueva operación de autenticación.
     */ 
    public void hideStatus() {
        // Se elimina el texto para no conservar un mensaje que ya no procede.
        statusLabel.setText("");

        // Se oculta visualmente el Label.
        statusLabel.setVisible(false);

        // Se indica al VBox que no reserve espacio para el Label oculto.
        statusLabel.setManaged(false);
    }

    // -----------------------------------------------------------------
    // 9. ACCESO A LA VISTA Y LIMPIEZA
    // -----------------------------------------------------------------

    // NOTA DIDÁCTICA: Devuelve el nodo raíz que la clase principal colocará en la ventana.
    /** Devuelve el nodo raíz que se insertará en la Scene. */
    public Parent getView() {
        return root;
    }

    // NOTA DIDÁCTICA: Limpia los campos de entrada y los indicadores para evitar que queden datos de una sesión anterior.
    /** Limpia los campos de usuario y contraseña. */
    public void clearFields() {
        usernameField.clear();
        masterPasswordField.clear();
        confirmPasswordField.clear();
        confirmationStatusLabel.setText("");
        confirmationStatusLabel.setVisible(false);
        confirmationStatusLabel.setManaged(false);
        hideStatus();
        usernameField.requestFocus();
    }
}
