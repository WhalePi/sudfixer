package sudfixer;

import javafx.application.Application;

/**
 * The application's only entry point.
 *
 * <p>A main class that does <em>not</em> extend {@code Application} lets the JVM start
 * normally when JavaFX is on the class path rather than the module path (as in the
 * jpackage installers and IDEs).
 */
public final class Launcher {

    private Launcher() { }

    public static void main(String[] args) {
        Application.launch(SudFixerApp.class, args);
    }
}
