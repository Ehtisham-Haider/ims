package com.stockpro.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central SLF4J logger factory for StockPro modules.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public final class AppLogger {

    /**
     * Prevents instantiation of utility class.
     */
    private AppLogger() {
    }

    /**
     * Obtains a structured logger scoped to the supplied class.
     *
     * @param owner owning class requesting a logger
     * @return configured SLF4J {@link Logger}
     */
    public static Logger get(Class<?> owner) {
        return LoggerFactory.getLogger(owner);
    }
}
