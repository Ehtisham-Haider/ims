package com.stockpro;

/**
 * Non-JavaFX launcher wrapper allowing seamless execution from IDEs and shaded archives.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmad Khan
 */
public final class Launcher {

    /**
     * Prevents instantiation of launcher entrypoint class.
     */
    private Launcher() {
    }

    /**
     * Delegates application startup to {@link StockProApp#main(String[])}.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        StockProApp.main(args);
    }
}
