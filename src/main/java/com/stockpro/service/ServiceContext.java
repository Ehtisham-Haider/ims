package com.stockpro.service;

import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SettingsRepository;
import com.stockpro.repository.StockMovementRepository;
import com.stockpro.repository.UserRepository;
import com.stockpro.service.command.CommandHistoryManager;

/**
 * Central service registry wiring repositories and business services around the active {@link DatabaseManager}.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class ServiceContext {
    private static volatile ServiceContext instance;

    private final DatabaseManager databaseManager;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final SettingsRepository settingsRepository;
    private final StockMovementRepository stockMovementRepository;
    private final AuditLogRepository auditLogRepository;
    private final InventoryService inventoryService;
    private final AuthService authService;
    private final AuditService auditService;
    private final StockOperationService stockOperationService;

    /**
     * Constructs a service registry bound to a specific {@link DatabaseManager}.
     *
     * @param databaseManager database manager supplying pooled SQLite connections
     */
    public ServiceContext(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.productRepository = new ProductRepository(databaseManager);
        this.userRepository = new UserRepository(databaseManager);
        this.settingsRepository = new SettingsRepository(databaseManager);
        this.stockMovementRepository = new StockMovementRepository(databaseManager);
        this.auditLogRepository = new AuditLogRepository(databaseManager);
        this.inventoryService = new InventoryService(
                productRepository,
                new CommandHistoryManager(),
                InventoryEventBus.getInstance()
        );
        this.authService = new AuthService(userRepository, AppSession.getInstance());
        this.auditService = new AuditService(auditLogRepository);
        this.stockOperationService = new StockOperationService(
                productRepository,
                stockMovementRepository,
                auditService,
                InventoryEventBus.getInstance()
        );
        syncSessionSettings();
    }

    /**
     * Returns the application-wide singleton {@link ServiceContext}.
     *
     * @return shared {@link ServiceContext}
     */
    public static ServiceContext getInstance() {
        if (instance == null) {
            synchronized (ServiceContext.class) {
                if (instance == null) {
                    instance = new ServiceContext(DatabaseManager.getInstance());
                }
            }
        }
        return instance;
    }

    /**
     * Loads saved store preferences into {@link AppSession}.
     */
    public void syncSessionSettings() {
        AppSession session = AppSession.getInstance();
        session.setStoreName(settingsRepository.get("store.name", "StockPro Store"));
        session.setCurrencyFromSetting(settingsRepository.get("store.currency", "USD ($)"));
        try {
            session.setTaxRatePercent(Double.parseDouble(settingsRepository.get("store.tax_rate", "15")));
        } catch (NumberFormatException ignored) {
            session.setTaxRatePercent(15.0);
        }
        session.setDarkMode("dark".equalsIgnoreCase(settingsRepository.get("ui.theme", "light")));
    }

    /**
     * Returns the active database manager.
     *
     * @return database manager
     */
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    /**
     * Returns the product repository.
     *
     * @return product repository
     */
    public ProductRepository getProductRepository() {
        return productRepository;
    }

    /**
     * Returns the staff user repository.
     *
     * @return user repository
     */
    public UserRepository getUserRepository() {
        return userRepository;
    }

    /**
     * Returns the settings key-value repository.
     *
     * @return settings repository
     */
    public SettingsRepository getSettingsRepository() {
        return settingsRepository;
    }

    /**
     * Returns the core inventory service.
     *
     * @return inventory service
     */
    public InventoryService getInventoryService() {
        return inventoryService;
    }

    /**
     * Returns the authentication service.
     *
     * @return authentication service
     */
    public AuthService getAuthService() {
        return authService;
    }

    /**
     * Returns the stock movement ledger repository.
     *
     * @return stock movement repository
     */
    public StockMovementRepository getStockMovementRepository() {
        return stockMovementRepository;
    }

    /**
     * Returns the tamper-evident SHA-256 audit service.
     *
     * @return audit service
     */
    public AuditService getAuditService() {
        return auditService;
    }

    /**
     * Returns the stock operation service.
     *
     * @return stock operation service
     */
    public StockOperationService getStockOperationService() {
        return stockOperationService;
    }
}
