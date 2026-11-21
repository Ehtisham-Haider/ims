package com.stockpro.ai;

import com.stockpro.domain.InventoryFilterCriteria;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.Supplier;
import com.stockpro.ml.OfflineMlEngine;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SupplierRepository;
import com.stockpro.service.AppSession;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Grounded store intelligence service uniting Groq cloud completions and full offline fallbacks
 * for store Q&amp;A, morning briefings, supplier email drafts, natural chart specs, explanations,
 * and casual search translation.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class AiAssistantService {
    private final GroqApiClient groqClient;
    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final OfflineMlEngine mlEngine;

    /**
     * Structured assistant reply optionally carrying a live chart specification.
     *
     * @param message formatted assistant response text
     * @param usedCloud {@code true} if response came from Groq API
     * @param chartSpec optional chart specification to render inline in JavaFX
     * @author Ehtisham Haider, Syed Ahmed
     */
    public record AssistantReply(String message, boolean usedCloud, ChartSpec chartSpec) {
    }

    /**
     * Constructs an AI Assistant service wired to store repositories and offline ML forecasts.
     *
     * @param groqClient Groq HTTP client
     * @param productRepository product repository
     * @param supplierRepository supplier repository
     * @param mlEngine offline ML forecasting &amp; anomaly engine
     */
    public AiAssistantService(
            GroqApiClient groqClient,
            ProductRepository productRepository,
            SupplierRepository supplierRepository,
            OfflineMlEngine mlEngine) {
        this.groqClient = groqClient;
        this.productRepository = productRepository;
        this.supplierRepository = supplierRepository;
        this.mlEngine = mlEngine;
    }

    /**
     * Answers natural-language store questions grounded in live SQLite inventory and ML forecasts.
     *
     * @param userQuestion question such as {@code "Which items run out this week?"}
     * @return grounded {@link AssistantReply}
     */
    public AssistantReply answerQuestion(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return new AssistantReply("Ask me anything about your stock, expiring items, profit, or charts.", false, null);
        }

        String lower = userQuestion.toLowerCase(Locale.ROOT);
        if (lower.contains("chart") || lower.contains("graph") || lower.contains("plot")) {
            return generateChartFromPrompt(userQuestion);
        }

        List<Product> products = productRepository.findAll(false);
        List<OfflineMlEngine.ProductForecast> forecasts = mlEngine.analyzeCatalog();
        String context = buildStoreContext(products, forecasts);

        GroqApiClient.GroqResponse cloud = groqClient.complete(
                "You are StockPro Store Assistant. Answer concisely and accurately using ONLY this live store inventory data:\n" + context,
                userQuestion
        );
        if (cloud.success()) {
            return new AssistantReply(cloud.content(), true, null);
        }

        return new AssistantReply(buildOfflineAnswer(userQuestion, products, forecasts), false, null);
    }

    /**
     * Generates a crisp morning executive briefing summarizing urgent restock, expiry, and revenue insights.
     *
     * @return plain-English briefing text
     */
    public String generateMorningBriefing() {
        List<Product> products = productRepository.findAll(false);
        List<OfflineMlEngine.ProductForecast> forecasts = mlEngine.analyzeCatalog();
        String context = buildStoreContext(products, forecasts);

        GroqApiClient.GroqResponse cloud = groqClient.complete(
                "Summarize the store's morning priorities in 3 short bullet points based strictly on:\n" + context,
                "Generate today's morning business briefing."
        );
        if (cloud.success()) {
            return cloud.content();
        }
        return buildOfflineBriefing(products, forecasts);
    }

    /**
     * Drafts a ready-to-send restock email for a supplier covering low-stock and urgent items.
     *
     * @param supplier target supplier
     * @return ready-to-send email body
     */
    public String draftSupplierEmail(Supplier supplier) {
        List<Product> products = productRepository.findAll(false);
        List<OfflineMlEngine.ProductForecast> urgent = mlEngine.analyzeCatalog().stream()
                .filter(f -> f.recommendedReorderQty() > 0 || f.product().getStatus() != StockStatus.IN_STOCK)
                .limit(5)
                .toList();

        String supName = supplier != null ? supplier.getName() : "Valued Supplier";
        String storeName = AppSession.getInstance().getStoreName();
        String itemsList = urgent.isEmpty()
                ? "- General shelf replenishment items (details pending)"
                : urgent.stream()
                .map(f -> String.format("- %s (%s): %d units",
                        f.product().getName(),
                        f.product().getSku(),
                        Math.max(10, f.recommendedReorderQty())))
                .collect(Collectors.joining("\n"));

        GroqApiClient.GroqResponse cloud = groqClient.complete(
                "Write a polite, concise B2B restock purchase inquiry email from " + storeName + " to " + supName + ".",
                "Include these requested items:\n" + itemsList
        );
        if (cloud.success()) {
            return cloud.content();
        }

        return String.format("""
                Subject: Urgent Restock Purchase Request — %s

                Dear %s Team,

                We would like to place a restock order from %s for the following items running low in our inventory:

                %s

                Please confirm unit pricing and earliest delivery availability (lead time %d day(s)).

                Best regards,
                %s
                %s
                """,
                storeName,
                supName,
                storeName,
                itemsList,
                supplier != null ? supplier.getDeliveryDays() : 3,
                AppSession.getInstance().getActorName(),
                storeName
        );
    }

    /**
     * Translates a chart request into a structured {@link ChartSpec} rendered live in JavaFX.
     *
     * @param prompt natural prompt such as {@code "Show a bar chart of top 5 valuable categories"}
     * @return {@link AssistantReply} containing explanation and {@link ChartSpec}
     */
    public AssistantReply generateChartFromPrompt(String prompt) {
        List<Product> products = productRepository.findAll(false);
        ChartSpec offlineSpec = NaturalQueryEngine.buildOfflineChartSpec(prompt, products);

        String system = "Return ONLY valid JSON matching {\"chartType\":\"BAR|LINE|PIE\",\"title\":\"...\",\"points\":[{\"label\":\"...\",\"value\":123.4}]} using this catalog data:\n"
                + buildStoreContext(products, List.of());
        GroqApiClient.GroqResponse cloud = groqClient.complete(system, prompt);
        if (cloud.success()) {
            ChartSpec parsed = NaturalQueryEngine.tryParseChartJson(cloud.content());
            if (parsed != null) {
                return new AssistantReply("Generated chart: " + parsed.title(), true, parsed);
            }
        }
        return new AssistantReply("Rendered live chart: " + offlineSpec.title(), false, offlineSpec);
    }

    /**
     * Produces a 2–3 bullet plain-English explanation for any ML alert, stockout warning, or chart spike.
     *
     * @param subject anomaly, alert, or chart insight to explain
     * @return concise plain-English bulleted explanation
     */
    public String explainInPlainEnglish(String subject) {
        List<OfflineMlEngine.AnomalyAlert> anomalies = mlEngine.detectAnomalies();
        String context = anomalies.isEmpty()
                ? "Catalog metrics are balanced with no extreme Z-score outliers."
                : anomalies.stream().map(OfflineMlEngine.AnomalyAlert::description).collect(Collectors.joining("\n"));

        GroqApiClient.GroqResponse cloud = groqClient.complete(
                "Explain clearly in 2 to 3 short bullet points for a store owner. Avoid jargon. Context:\n" + context,
                "Explain: " + (subject != null ? subject : "current inventory alerts")
        );
        if (cloud.success()) {
            return cloud.content();
        }
        if (!anomalies.isEmpty()) {
            return "• " + anomalies.get(0).description() + "\n"
                    + "• This was flagged because it sits far outside normal store unit counts (Z=" + anomalies.get(0).zScore() + ").\n"
                    + "• Double-check the shelf count or edit the product quantity if a typo occurred.";
        }
        return "• Your current stock levels and daily sales velocity are within expected ranges.\n"
                + "• Items with fewer than 7 days of stock remaining are prioritized first for restock.\n"
                + "• Keeping safety stock buffers prevents lost sales during supplier delivery lead times.";
    }

    /**
     * Converts a casual phrase into active {@link InventoryFilterCriteria}.
     *
     * @param casualPhrase phrase like {@code "cheap snacks running low"}
     * @return structured filter criteria
     */
    public InventoryFilterCriteria parseNaturalSearch(String casualPhrase) {
        List<String> cats = productRepository.findAll(false).stream()
                .map(Product::getCategory)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .toList();
        return NaturalQueryEngine.translateNaturalSearch(casualPhrase, cats);
    }

    /**
     * Builds a grounded store context string summarizing catalog items, values, and stockout countdowns.
     *
     * @param products catalog items
     * @param forecasts ML forecast entries
     * @return compact textual grounding prompt
     */
    private String buildStoreContext(List<Product> products, List<OfflineMlEngine.ProductForecast> forecasts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Store: ").append(AppSession.getInstance().getStoreName())
                .append(" | Total SKUs: ").append(products.size()).append("\n");
        for (Product p : products.stream().limit(25).toList()) {
            sb.append(String.format("- %s (%s): cat=%s, qty=%d, sell=%.2f, status=%s, exp=%s\n",
                    p.getName(), p.getSku(), p.getCategory(), p.getQuantity(),
                    p.getSellPrice(), p.getStatus().getDisplayName(), p.getExpiryDate()));
        }
        if (!forecasts.isEmpty()) {
            sb.append("Soonest stockouts:\n");
            for (OfflineMlEngine.ProductForecast f : forecasts.stream().limit(6).toList()) {
                sb.append(String.format("- %s: %.1f days left, recommended restock=%d, ABC=%s\n",
                        f.product().getName(), f.daysUntilEmpty(), f.recommendedReorderQty(), f.abcGrade()));
            }
        }
        return sb.toString();
    }

    /**
     * Answers store questions deterministically offline when Groq API is unavailable or unconfigured.
     *
     * @param question user prompt
     * @param products active products
     * @param forecasts ML forecasts
     * @return grounded offline response
     */
    private String buildOfflineAnswer(
            String question, List<Product> products, List<OfflineMlEngine.ProductForecast> forecasts) {
        String lower = question.toLowerCase(Locale.ROOT);
        if (products.isEmpty()) {
            return "Your store inventory is currently empty. Add items or load demo data to analyze stock.";
        }

        if (lower.contains("run out") || lower.contains("week") || lower.contains("empty") || lower.contains("low")) {
            List<OfflineMlEngine.ProductForecast> soon = forecasts.stream()
                    .filter(f -> f.daysUntilEmpty() <= 14.0 || f.product().getStatus() != StockStatus.IN_STOCK)
                    .limit(5)
                    .toList();
            if (soon.isEmpty()) {
                return "Good news! No items are projected to run out this week based on current stock velocity.";
            }
            StringBuilder sb = new StringBuilder("Here are the items likely to run out soon:\n");
            int idx = 1;
            for (OfflineMlEngine.ProductForecast f : soon) {
                sb.append(String.format("%d. %s (%.1f days left • %d in stock)\n",
                        idx++, f.product().getName(), f.daysUntilEmpty(), f.product().getQuantity()));
            }
            return sb.toString().trim();
        }

        if (lower.contains("value") || lower.contains("profit") || lower.contains("worth")) {
            double cost = products.stream().mapToDouble(Product::getCostValuation).sum();
            double retail = products.stream().mapToDouble(Product::getRetailValuation).sum();
            AppSession s = AppSession.getInstance();
            return String.format("Store Inventory Valuation:\n• Cost Value: %s\n• Retail Value: %s\n• Est. Gross Profit: %s",
                    s.formatMoney(cost), s.formatMoney(retail), s.formatMoney(retail - cost));
        }

        return buildOfflineBriefing(products, forecasts);
    }

    /**
     * Builds an offline morning briefing summary.
     *
     * @param products catalog items
     * @param forecasts ML forecasts
     * @return concise briefing text
     */
    private String buildOfflineBriefing(List<Product> products, List<OfflineMlEngine.ProductForecast> forecasts) {
        long outCount = products.stream().filter(p -> p.getQuantity() <= 0).count();
        long lowCount = products.stream().filter(p -> p.getStatus() == StockStatus.LOW_STOCK).count();
        String topSoon = forecasts.stream()
                .filter(f -> f.daysUntilEmpty() <= 10.0)
                .limit(2)
                .map(f -> f.product().getName())
                .collect(Collectors.joining(" and "));
        if (topSoon.isBlank()) {
            topSoon = "top-selling items";
        }
        return String.format("Morning Store Briefing:\n• %d active SKUs (%d out of stock, %d low stock).\n• Prioritize restocking %s.\n• Run Agent Audit in the Approval Inbox to review purchase recommendations.",
                products.size(), outCount, lowCount, topSoon);
    }
}
