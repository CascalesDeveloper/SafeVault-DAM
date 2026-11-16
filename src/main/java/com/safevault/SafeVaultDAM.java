
package com.safevault;

import com.safevault.database.DatabaseManager;
import com.safevault.view.DashboardView;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

public class SafeVaultDAM extends Application {

    private DashboardView dashboard;

    @Override
    public void start(Stage stage) {

        // Inicializar y comprobar la base de datos SQLite
        try {
            DatabaseManager.initializeDatabase();

            if (!DatabaseManager.testConnection()) {
                throw new IllegalStateException(
                        "No se pudo conectar con SQLite."
                );
            }

        } catch (Exception e) {
            throw new RuntimeException(
                    "Error al iniciar la base de datos SQLite.", e
            );
        }

        // Crear la interfaz principal
        dashboard = new DashboardView();

        Scene scene = new Scene(
                dashboard.getView(), 1200, 760
        );

        // Cargar hoja de estilos CSS
        URL cssResource =
                getClass().getResource("/css/safevault.css");

        if (cssResource == null) {
            throw new IllegalStateException(
                    "No se encuentra safevault.css. "
                    + "Comprueba src/main/resources/css/"
            );
        }

        scene.getStylesheets().add(
                cssResource.toExternalForm()
        );

        // Configuración de ventana
        stage.setTitle(
                "SafeVault DAM | Gestor de contraseñas"
        );

        stage.setMinWidth(850);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.centerOnScreen();
        stage.show();
    }

    @Override
    public void stop() {
        if (dashboard != null) {
            dashboard.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
