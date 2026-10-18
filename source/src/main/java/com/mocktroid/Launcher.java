package com.mocktroid;

/**
 * Entry point for the bundled jar and the packaged app. A main class that extends
 * {@link javafx.application.Application} refuses to start from a plain classpath jar
 * ("JavaFX runtime components are missing"); going through a class that doesn't works.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        MocktroidApp.main(args);
    }
}
