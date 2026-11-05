package sudfixer;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import sudfixer.ui.MainView;

/**
 * SUD Time Fixer – shifts the recorded time of SoundTrap .sud files.
 *
 * <p>Started from {@link Launcher}, never directly.
 */
public class SudFixerApp extends Application {

    @Override
    public void start(Stage stage) {
        MainView view = new MainView(stage);
        Scene scene = new Scene(view, 680, 900);

        var css = SudFixerApp.class.getResource("/sudfixer/sudfixer.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        var icon = SudFixerApp.class.getResource("/sudfixer/sudfixer_icon.png");
        if (icon != null) stage.getIcons().add(new Image(icon.toExternalForm()));

        stage.setTitle("SUD Time Fixer");
        stage.setMinWidth(620);
        stage.setMinHeight(760);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> view.cancel());
        stage.show();
    }
}
