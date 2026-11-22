package com.stockpro.domain;

/**
 * Structured outcome returned by parsing or executing a spoken voice command.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 * @param intent classified command intent ({@link VoiceIntent})
 * @param executed {@code true} when an inventory, navigation, or search action succeeded
 * @param rawTranscript transcribed spoken text
 * @param usedCloudWhisper {@code true} when Groq {@code whisper-large-v3-turbo} produced the transcript
 * @param extractedName parsed product name or search query
 * @param quantity parsed unit count (when applicable)
 * @param targetSectionName target {@code NavigationSection} enum name for {@code NAVIGATE} intents
 * @param matchedProductId matched product id for {@code VOICE_SEARCH} or stock mutations
 * @param matchedSku matched product SKU code
 * @param message actionable confirmation or clarification text
 */
public record VoiceCommandResult(
        VoiceIntent intent,
        boolean executed,
        String rawTranscript,
        boolean usedCloudWhisper,
        String extractedName,
        int quantity,
        String targetSectionName,
        long matchedProductId,
        String matchedSku,
        String message
) {
    /**
     * Supported hands-free voice command intents in StockPro.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     */
    public enum VoiceIntent {
        /** Creates a brand-new SKU in inventory. */
        ADD_ITEM,
        /** Deducts units via a recorded sale movement. */
        RECORD_SALE,
        /** Adds units via a recorded restock movement. */
        RESTOCK,
        /** Navigates the sidebar to another screen. */
        NAVIGATE,
        /** Searches inventory and opens the matching ProductDetailsDrawer. */
        VOICE_SEARCH,
        /** Forwards conversational prompts to AI Chat Assistant. */
        CHAT_QUERY
    }
}
