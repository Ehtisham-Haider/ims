package com.stockpro.service;

import com.stockpro.ai.AgenticOrchestratorService;
import com.stockpro.ai.AiAssistantService;
import com.stockpro.ai.GroqApiClient;
import com.stockpro.dsa.CatalogSearchEngine;
import com.stockpro.ml.OfflineMlEngine;
import com.stockpro.repository.AgentProposalRepository;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.PurchaseOrderRepository;
import com.stockpro.repository.SettingsRepository;
import com.stockpro.repository.StockMovementRepository;
import com.stockpro.repository.SupplierRepository;
import com.stockpro.repository.UserRepository;
import com.stockpro.service.command.CommandHistoryManager;

/**
 * Central service registry wiring repositories, V1 business services, and Version 2
 * enterprise modules around the active {@link DatabaseManager}.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class ServiceContext {
    private static volatile ServiceContext instance;

    private final DatabaseManager databaseManager;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final SettingsRepository settingsRepository;
    private final StockMovementRepository stockMovementRepository;
    private final AuditLogRepository auditLogRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final AgentProposalRepository agentProposalRepository;
    private final InventoryService inventoryService;
    private final AuthService authService;
    private final AuditService auditService;
    private final StockOperationService stockOperationService;
    private final SupplierOrderService supplierOrderService;
    private final FinanceService financeService;
    private final CatalogSearchEngine catalogSearchEngine;
    private final OfflineMlEngine offlineMlEngine;
    private final GroqApiClient groqApiClient;
    private final AiAssistantService aiAssistantService;
    private final AgenticOrchestratorService agenticOrchestratorService;
    private final BarcodeLabelService barcodeLabelService;
    private final UsbBarcodeScannerListener usbBarcodeScannerListener;
    private final SpreadsheetImportExportService spreadsheetImportExportService;
    private final ExecutivePdfReportGenerator executivePdfReportGenerator;
    private final BackupService backupService;
    private final DemoDataService demoDataService;
    private final TelemetryMonitorService telemetryMonitorService;
    private final BuiltInLabTestRunner builtInLabTestRunner;
    private final ProductImageService productImageService;
    private final CatalogVariantBomService catalogVariantBomService;
    private final MultiBranchTransferRmaService multiBranchTransferRmaService;
    private final WarehouseMapPickingService warehouseMapPickingService;
    private final MultiCurrencyFxService multiCurrencyFxService;
    private final LandedCostEngine landedCostEngine;
    private final VolumePricingTierService volumePricingTierService;
    private final CashFlowForecastService cashFlowForecastService;
    private final PerceptualImageHasher perceptualImageHasher;
    private final OpenFoodFactsClient openFoodFactsClient;
    private final VisionStudioService visionStudioService;
    private final VoiceAudioRecorder voiceAudioRecorder;
    private final com.stockpro.ai.GroqWhisperClient groqWhisperClient;
    private final VoiceCommandEngine voiceCommandEngine;
    private final com.stockpro.ml.AdvancedOfflineMlService advancedOfflineMlService;
    private final com.stockpro.ai.WorldAwareGenAiService worldAwareGenAiService;
    private final com.stockpro.ai.MultiAgentStoreBrainService multiAgentStoreBrainService;
    private final com.stockpro.ml.MonteCarloSimulator monteCarloSimulator;
    private final UserColumnPreferenceService userColumnPreferenceService;
    private final AlertDispatcherService alertDispatcherService;
    private final LocalRestApiServer localRestApiServer;
    private final com.stockpro.dsa.LruProductCache<String, com.stockpro.domain.Product> lruProductCache;
    private final MegaScaleBenchmarkService megaScaleBenchmarkService;
    private final EncryptedBackupService encryptedBackupService;

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
        this.supplierRepository = new SupplierRepository(databaseManager);
        this.purchaseOrderRepository = new PurchaseOrderRepository(databaseManager);
        this.agentProposalRepository = new AgentProposalRepository(databaseManager);
        this.productImageService = new ProductImageService(settingsRepository, productRepository);
        this.inventoryService = new InventoryService(
                productRepository,
                new CommandHistoryManager(),
                InventoryEventBus.getInstance()
        );
        this.inventoryService.setProductImageService(productImageService);
        this.authService = new AuthService(userRepository, AppSession.getInstance());
        this.auditService = new AuditService(auditLogRepository);
        this.stockOperationService = new StockOperationService(
                productRepository,
                stockMovementRepository,
                auditService,
                InventoryEventBus.getInstance()
        );
        this.supplierOrderService = new SupplierOrderService(
                supplierRepository,
                purchaseOrderRepository,
                stockOperationService,
                auditService,
                InventoryEventBus.getInstance()
        );
        this.financeService = new FinanceService(databaseManager, productRepository);
        this.catalogSearchEngine = new CatalogSearchEngine(productRepository, InventoryEventBus.getInstance());
        this.offlineMlEngine = new OfflineMlEngine(databaseManager, productRepository);
        this.groqApiClient = new GroqApiClient(settingsRepository);
        this.aiAssistantService = new AiAssistantService(
                groqApiClient,
                productRepository,
                supplierRepository,
                offlineMlEngine
        );
        this.agenticOrchestratorService = new AgenticOrchestratorService(
                agentProposalRepository,
                productRepository,
                supplierRepository,
                supplierOrderService,
                offlineMlEngine,
                auditService,
                InventoryEventBus.getInstance()
        );
        this.barcodeLabelService = new BarcodeLabelService();
        this.usbBarcodeScannerListener = new UsbBarcodeScannerListener(productRepository, stockOperationService);
        this.spreadsheetImportExportService = new SpreadsheetImportExportService(inventoryService);
        this.executivePdfReportGenerator = new ExecutivePdfReportGenerator(
                productRepository,
                new DashboardMetricsService(databaseManager, productRepository),
                financeService,
                aiAssistantService
        );
        this.backupService = new BackupService(databaseManager, InventoryEventBus.getInstance());
        this.demoDataService = new DemoDataService(
                databaseManager,
                catalogSearchEngine,
                InventoryEventBus.getInstance(),
                productImageService
        );
        this.telemetryMonitorService = new TelemetryMonitorService(databaseManager, catalogSearchEngine);
        this.builtInLabTestRunner = new BuiltInLabTestRunner();
        this.catalogVariantBomService = new CatalogVariantBomService(
                databaseManager,
                productRepository,
                stockMovementRepository,
                auditService,
                InventoryEventBus.getInstance()
        );
        this.multiBranchTransferRmaService = new MultiBranchTransferRmaService(
                databaseManager,
                productRepository,
                purchaseOrderRepository,
                stockMovementRepository,
                auditService,
                InventoryEventBus.getInstance()
        );
        this.stockOperationService.setBranchService(this.multiBranchTransferRmaService);
        this.warehouseMapPickingService = new WarehouseMapPickingService(
                databaseManager, productRepository, auditService, InventoryEventBus.getInstance());
        this.multiCurrencyFxService = new MultiCurrencyFxService(databaseManager);
        this.landedCostEngine = new LandedCostEngine(
                databaseManager, purchaseOrderRepository, productRepository, auditService, InventoryEventBus.getInstance());
        this.volumePricingTierService = new VolumePricingTierService(databaseManager);
        this.cashFlowForecastService = new CashFlowForecastService(databaseManager, productRepository, purchaseOrderRepository);
        this.perceptualImageHasher = new PerceptualImageHasher(databaseManager);
        this.openFoodFactsClient = new OpenFoodFactsClient();
        this.visionStudioService = new VisionStudioService(
                productRepository, perceptualImageHasher, openFoodFactsClient, groqApiClient, auditService);
        this.voiceAudioRecorder = new VoiceAudioRecorder();
        this.groqWhisperClient = new com.stockpro.ai.GroqWhisperClient(groqApiClient);
        this.voiceCommandEngine = new VoiceCommandEngine(
                inventoryService, stockOperationService, catalogSearchEngine, groqWhisperClient, auditService);
        this.advancedOfflineMlService = new com.stockpro.ml.AdvancedOfflineMlService(
                databaseManager, productRepository, supplierRepository, purchaseOrderRepository, offlineMlEngine);
        this.worldAwareGenAiService = new com.stockpro.ai.WorldAwareGenAiService(
                productRepository, groqApiClient, new DashboardMetricsService(databaseManager, productRepository), financeService);
        this.executivePdfReportGenerator.setWorldAwareGenAiService(this.worldAwareGenAiService);
        this.multiAgentStoreBrainService = new com.stockpro.ai.MultiAgentStoreBrainService(
                databaseManager,
                agentProposalRepository,
                productRepository,
                supplierRepository,
                purchaseOrderRepository,
                settingsRepository,
                agenticOrchestratorService,
                advancedOfflineMlService,
                warehouseMapPickingService,
                auditService,
                InventoryEventBus.getInstance());
        this.monteCarloSimulator = new com.stockpro.ml.MonteCarloSimulator(
                productRepository,
                offlineMlEngine,
                settingsRepository,
                auditService,
                InventoryEventBus.getInstance());
        this.userColumnPreferenceService = new UserColumnPreferenceService(databaseManager);
        this.alertDispatcherService = new AlertDispatcherService(databaseManager, settingsRepository);
        this.localRestApiServer = new LocalRestApiServer(
                productRepository,
                stockOperationService,
                userRepository,
                settingsRepository);
        this.lruProductCache = new com.stockpro.dsa.LruProductCache<>(1024);
        this.megaScaleBenchmarkService = new MegaScaleBenchmarkService(databaseManager, lruProductCache);
        this.encryptedBackupService = new EncryptedBackupService(backupService, auditService);
        if ("true".equalsIgnoreCase(settingsRepository.get("api.local.enabled", "false"))) {
            try {
                int port = Integer.parseInt(settingsRepository.get("api.local.port", "8765"));
                this.localRestApiServer.start(port);
            } catch (Exception ignored) {
                // Keep UI startup non-fatal if local port is already bound.
            }
        }
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

    /**
     * Returns the supplier repository.
     *
     * @return supplier repository
     */
    public SupplierRepository getSupplierRepository() {
        return supplierRepository;
    }

    /**
     * Returns the purchase order repository.
     *
     * @return purchase order repository
     */
    public PurchaseOrderRepository getPurchaseOrderRepository() {
        return purchaseOrderRepository;
    }

    /**
     * Returns the supplier and purchase order workflow service.
     *
     * @return supplier order service
     */
    public SupplierOrderService getSupplierOrderService() {
        return supplierOrderService;
    }

    /**
     * Returns the financial engine service.
     *
     * @return finance service
     */
    public FinanceService getFinanceService() {
        return financeService;
    }

    /**
     * Returns the unified DS&amp;A catalog search engine.
     *
     * @return {@link CatalogSearchEngine}
     */
    public CatalogSearchEngine getCatalogSearchEngine() {
        return catalogSearchEngine;
    }

    /**
     * Returns the pure-Java offline ML forecasting and anomaly detection engine.
     *
     * @return {@link OfflineMlEngine}
     */
    public OfflineMlEngine getOfflineMlEngine() {
        return offlineMlEngine;
    }

    /**
     * Returns the Groq cloud API HTTP client.
     *
     * @return {@link GroqApiClient}
     */
    public GroqApiClient getGroqApiClient() {
        return groqApiClient;
    }

    /**
     * Returns the grounded AI Assistant service.
     *
     * @return {@link AiAssistantService}
     */
    public AiAssistantService getAiAssistantService() {
        return aiAssistantService;
    }

    /**
     * Returns the agent proposal repository.
     *
     * @return {@link AgentProposalRepository}
     */
    public AgentProposalRepository getAgentProposalRepository() {
        return agentProposalRepository;
    }

    /**
     * Returns the autonomous agentic orchestrator and approval safety queue service.
     *
     * @return {@link AgenticOrchestratorService}
     */
    public AgenticOrchestratorService getAgenticOrchestratorService() {
        return agenticOrchestratorService;
    }

    /**
     * Returns the ZXing barcode and QR label service.
     *
     * @return {@link BarcodeLabelService}
     */
    public BarcodeLabelService getBarcodeLabelService() {
        return barcodeLabelService;
    }

    /**
     * Returns the keyboard-wedge USB barcode scanner listener.
     *
     * @return {@link UsbBarcodeScannerListener}
     */
    public UsbBarcodeScannerListener getUsbBarcodeScannerListener() {
        return usbBarcodeScannerListener;
    }

    /**
     * Returns the Apache POI Excel and CSV import/export service.
     *
     * @return {@link SpreadsheetImportExportService}
     */
    public SpreadsheetImportExportService getSpreadsheetImportExportService() {
        return spreadsheetImportExportService;
    }

    /**
     * Returns the OpenPDF executive business report generator.
     *
     * @return {@link ExecutivePdfReportGenerator}
     */
    public ExecutivePdfReportGenerator getExecutivePdfReportGenerator() {
        return executivePdfReportGenerator;
    }

    /**
     * Returns the SQLite snapshot backup and restore service.
     *
     * @return {@link BackupService}
     */
    public BackupService getBackupService() {
        return backupService;
    }

    /**
     * Returns the 1,000-item realistic demo store data generator service.
     *
     * @return {@link DemoDataService}
     */
    public DemoDataService getDemoDataService() {
        return demoDataService;
    }

    /**
     * Returns the JVM and search-latency telemetry monitor service.
     *
     * @return {@link TelemetryMonitorService}
     */
    public TelemetryMonitorService getTelemetryMonitorService() {
        return telemetryMonitorService;
    }

    /**
     * Returns the built-in 8-case lab verification runner.
     *
     * @return {@link BuiltInLabTestRunner}
     */
    public BuiltInLabTestRunner getBuiltInLabTestRunner() {
        return builtInLabTestRunner;
    }

    /**
     * Returns the product image and optional Pexels photo resolver service.
     *
     * @return {@link ProductImageService}
     */
    public ProductImageService getProductImageService() {
        return productImageService;
    }

    /**
     * Returns the Version 2 catalog variants, UoM, BOM bundles, serials, batches, and custom fields service.
     *
     * @return {@link CatalogVariantBomService}
     */
    public CatalogVariantBomService getCatalogVariantBomService() {
        return catalogVariantBomService;
    }

    /**
     * Returns the Version 2 multi-branch, transfers, ATP, blind receiving, 3-way match, RMA, and backorder service.
     *
     * @return {@link MultiBranchTransferRmaService}
     */
    public MultiBranchTransferRmaService getMultiBranchTransferRmaService() {
        return multiBranchTransferRmaService;
    }

    /**
     * Returns the interactive 2D warehouse floorplan and Dijkstra pick-routing service.
     *
     * @return {@link WarehouseMapPickingService}
     */
    public WarehouseMapPickingService getWarehouseMapPickingService() {
        return warehouseMapPickingService;
    }

    /**
     * Returns the multi-currency FX conversion &amp; cache service.
     *
     * @return {@link MultiCurrencyFxService}
     */
    public MultiCurrencyFxService getMultiCurrencyFxService() {
        return multiCurrencyFxService;
    }

    /**
     * Returns the PO freight/duty/handling landed-cost allocation engine.
     *
     * @return {@link LandedCostEngine}
     */
    public LandedCostEngine getLandedCostEngine() {
        return landedCostEngine;
    }

    /**
     * Returns the supplier bulk volume price tiers &amp; MOQ advisor service.
     *
     * @return {@link VolumePricingTierService}
     */
    public VolumePricingTierService getVolumePricingTierService() {
        return volumePricingTierService;
    }

    /**
     * Returns the 30/60/90-day store cash-flow forecast engine.
     *
     * @return {@link CashFlowForecastService}
     */
    public CashFlowForecastService getCashFlowForecastService() {
        return cashFlowForecastService;
    }

    /**
     * Returns the 64-bit perceptual image duplicate detector service.
     *
     * @return {@link PerceptualImageHasher}
     */
    public PerceptualImageHasher getPerceptualImageHasher() {
        return perceptualImageHasher;
    }

    /**
     * Returns the Open Food Facts barcode metadata REST client.
     *
     * @return {@link OpenFoodFactsClient}
     */
    public OpenFoodFactsClient getOpenFoodFactsClient() {
        return openFoodFactsClient;
    }

    /**
     * Returns the AI Vision Studio &amp; ZXing optical scanner service.
     *
     * @return {@link VisionStudioService}
     */
    public VisionStudioService getVisionStudioService() {
        return visionStudioService;
    }

    /**
     * Returns the 16 kHz PCM/WAV microphone recorder helper.
     *
     * @return {@link VoiceAudioRecorder}
     */
    public VoiceAudioRecorder getVoiceAudioRecorder() {
        return voiceAudioRecorder;
    }

    /**
     * Returns the Groq {@code whisper-large-v3-turbo} speech-to-text client.
     *
     * @return {@link com.stockpro.ai.GroqWhisperClient}
     */
    public com.stockpro.ai.GroqWhisperClient getGroqWhisperClient() {
        return groqWhisperClient;
    }

    /**
     * Returns the hands-free voice command orchestrator.
     *
     * @return {@link VoiceCommandEngine}
     */
    public VoiceCommandEngine getVoiceCommandEngine() {
        return voiceCommandEngine;
    }

    /**
     * Returns the Branch 07 Advanced Offline ML analytics service.
     *
     * @return {@link com.stockpro.ml.AdvancedOfflineMlService}
     */
    public com.stockpro.ml.AdvancedOfflineMlService getAdvancedOfflineMlService() {
        return advancedOfflineMlService;
    }

    /**
     * Returns the Branch 08 World-Aware &amp; Generative AI service.
     *
     * @return {@link com.stockpro.ai.WorldAwareGenAiService}
     */
    public com.stockpro.ai.WorldAwareGenAiService getWorldAwareGenAiService() {
        return worldAwareGenAiService;
    }

    /**
     * Returns the Branch 09 7-Agent Store Brain, Self-Learning Feedback, and Store Health service.
     *
     * @return {@link com.stockpro.ai.MultiAgentStoreBrainService}
     */
    public com.stockpro.ai.MultiAgentStoreBrainService getMultiAgentStoreBrainService() {
        return multiAgentStoreBrainService;
    }

    /**
     * Returns the Branch 10 1,000-trial Monte Carlo What-If Time Machine simulator.
     *
     * @return {@link com.stockpro.ml.MonteCarloSimulator}
     */
    public com.stockpro.ml.MonteCarloSimulator getMonteCarloSimulator() {
        return monteCarloSimulator;
    }

    /**
     * Returns the Branch 11 per-user Inventory column visibility preference service.
     *
     * @return {@link UserColumnPreferenceService}
     */
    public UserColumnPreferenceService getUserColumnPreferenceService() {
        return userColumnPreferenceService;
    }

    /**
     * Returns the Branch 11 Telegram and Gmail multi-channel alert dispatcher.
     *
     * @return {@link AlertDispatcherService}
     */
    public AlertDispatcherService getAlertDispatcherService() {
        return alertDispatcherService;
    }

    /**
     * Returns the Branch 11 loopback JWT REST API server manager.
     *
     * @return {@link LocalRestApiServer}
     */
    public LocalRestApiServer getLocalRestApiServer() {
        return localRestApiServer;
    }

    /**
     * Returns the Branch 12 thread-safe O(1) LRU product cache.
     *
     * @return {@link com.stockpro.dsa.LruProductCache}
     */
    public com.stockpro.dsa.LruProductCache<String, com.stockpro.domain.Product> getLruProductCache() {
        return lruProductCache;
    }

    /**
     * Returns the Branch 12 SQLite pagination and 100k-SKU benchmark service.
     *
     * @return {@link MegaScaleBenchmarkService}
     */
    public MegaScaleBenchmarkService getMegaScaleBenchmarkService() {
        return megaScaleBenchmarkService;
    }

    /**
     * Returns the Branch 12 AES-256-GCM encrypted backup and restore coordinator.
     *
     * @return {@link EncryptedBackupService}
     */
    public EncryptedBackupService getEncryptedBackupService() {
        return encryptedBackupService;
    }
}
