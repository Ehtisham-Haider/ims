package com.stockpro.service;

import com.google.gson.Gson;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.SettingsRepository;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Multi-channel outbound alert dispatcher supporting zero-cost Telegram Bot API messaging,
 * Gmail SMTP STARTTLS delivery, severity filtering, and persistent SQLite dispatch auditing.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class AlertDispatcherService {

    /**
     * Injectable HTTP JSON transport interface allowing deterministic offline testing.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     */
    @FunctionalInterface
    public interface HttpJsonTransport {
        /**
         * Sends an HTTP POST request with a UTF-8 JSON body.
         *
         * @param url target endpoint URL
         * @param jsonPayload serialized JSON payload
         * @return HTTP status code and response body summary
         * @throws Exception if transport fails
         */
        TransportResponse postJson(String url, String jsonPayload) throws Exception;
    }

    /**
     * Injectable SMTP email transport allowing offline testing without live Gmail credentials.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     */
    @FunctionalInterface
    public interface SmtpEmailTransport {
        /**
         * Delivers an email message via SMTP.
         *
         * @param fromEmail sender Gmail address
         * @param appPassword Gmail app password
         * @param recipientEmail recipient email address
         * @param subject email subject line
         * @param body plain-text message body
         * @return status confirmation note
         * @throws Exception if SMTP handshake fails
         */
        String sendEmail(String fromEmail, String appPassword, String recipientEmail, String subject, String body) throws Exception;
    }

    /**
     * Immutable transport HTTP response wrapper.
     *
     * @param statusCode HTTP status code
     * @param body response text body
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     */
    public record TransportResponse(int statusCode, String body) {
    }

    /**
     * Immutable persisted alert dispatch outcome record.
     *
     * @param id database row ID
     * @param channel channel identifier ({@code TELEGRAM} or {@code GMAIL})
     * @param severity severity level ({@code INFO}, {@code WARNING}, {@code HIGH}, {@code CRITICAL})
     * @param recipient masked or logical target recipient
     * @param message delivered alert summary
     * @param delivered {@code true} if remote delivery succeeded
     * @param statusNote diagnostic response note
     * @param createdAt ISO-8601 dispatch timestamp
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     */
    public record DispatchLogEntry(
            long id,
            String channel,
            String severity,
            String recipient,
            String message,
            boolean delivered,
            String statusNote,
            String createdAt
    ) {
    }

    private static final Gson GSON = new Gson();

    private final DatabaseManager dbManager;
    private final SettingsRepository settingsRepository;
    private final HttpJsonTransport httpTransport;
    private final SmtpEmailTransport smtpTransport;

    /**
     * Constructs an alert dispatcher with live JDK HTTP and Gmail STARTTLS transports.
     *
     * @param dbManager SQLite connection manager
     * @param settingsRepository settings repository holding channel credentials
     */
    public AlertDispatcherService(DatabaseManager dbManager, SettingsRepository settingsRepository) {
        this(dbManager, settingsRepository, defaultHttpTransport(), defaultGmailSmtpTransport());
    }

    /**
     * Constructs an alert dispatcher with explicit injectable transports for testing.
     *
     * @param dbManager SQLite connection manager
     * @param settingsRepository settings repository holding channel credentials
     * @param httpTransport HTTP POST transport for Telegram Bot API
     * @param smtpTransport SMTP email transport for Gmail delivery
     */
    public AlertDispatcherService(
            DatabaseManager dbManager,
            SettingsRepository settingsRepository,
            HttpJsonTransport httpTransport,
            SmtpEmailTransport smtpTransport) {
        this.dbManager = dbManager;
        this.settingsRepository = settingsRepository;
        this.httpTransport = httpTransport;
        this.smtpTransport = smtpTransport;
    }

    /**
     * Sends a Telegram alert via {@code api.telegram.org/bot&lt;token&gt;/sendMessage} and logs the attempt.
     *
     * @param severity severity level ({@code INFO}, {@code WARNING}, {@code HIGH}, {@code CRITICAL})
     * @param message alert message body
     * @return persisted {@link DispatchLogEntry}
     */
    public DispatchLogEntry dispatchTelegramAlert(String severity, String message) {
        String token = settingsRepository.get("alerts.telegram.token", "").trim();
        String chatId = settingsRepository.get("alerts.telegram.chat_id", "").trim();
        String normSev = normalizeSeverity(severity);

        if (token.isEmpty() || chatId.isEmpty()) {
            return logDispatch("TELEGRAM", normSev, chatId.isEmpty() ? "unconfigured" : chatId,
                    message, false, "Skipped: Telegram token or chat_id not configured");
        }

        String url = "https://api.telegram.org/bot" + token + "/sendMessage";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", "[" + normSev + "] StockPro Alert: " + message);

        try {
            TransportResponse res = httpTransport.postJson(url, GSON.toJson(body));
            boolean ok = res.statusCode() >= 200 && res.statusCode() < 300;
            return logDispatch("TELEGRAM", normSev, chatId, message, ok,
                    "HTTP " + res.statusCode() + (ok ? " Delivered" : ": " + truncate(res.body(), 120)));
        } catch (Exception ex) {
            return logDispatch("TELEGRAM", normSev, chatId, message, false,
                    "Offline/Transport Error: " + truncate(ex.getMessage(), 120));
        }
    }

    /**
     * Sends a Gmail SMTP alert and persists a dispatch record in {@code alert_dispatch_logs}.
     *
     * @param severity severity level
     * @param subject email subject line
     * @param message alert body text
     * @return persisted {@link DispatchLogEntry}
     */
    public DispatchLogEntry dispatchEmailAlert(String severity, String subject, String message) {
        String from = settingsRepository.get("alerts.gmail.address", "").trim();
        String password = settingsRepository.get("alerts.gmail.app_password", "").trim();
        String recipient = settingsRepository.get("alerts.gmail.recipient", "").trim();
        if (recipient.isEmpty()) {
            recipient = from;
        }
        String normSev = normalizeSeverity(severity);

        if (from.isEmpty() || password.isEmpty() || recipient.isEmpty()) {
            return logDispatch("GMAIL", normSev, recipient.isEmpty() ? "unconfigured" : recipient,
                    message, false, "Skipped: Gmail address, app password, or recipient missing");
        }

        try {
            String note = smtpTransport.sendEmail(from, password, recipient,
                    "[" + normSev + "] " + (subject == null || subject.isBlank() ? "StockPro Alert" : subject),
                    message);
            return logDispatch("GMAIL", normSev, recipient, message, true,
                    note != null && !note.isBlank() ? note : "SMTP 250 OK");
        } catch (Exception ex) {
            return logDispatch("GMAIL", normSev, recipient, message, false,
                    "SMTP Error: " + truncate(ex.getMessage(), 120));
        }
    }

    /**
     * Dispatches across all configured channels when event severity meets {@code minSeverity}.
     *
     * @param minSeverity minimum severity gate ({@code INFO}, {@code WARNING}, {@code HIGH}, {@code CRITICAL})
     * @param severity incoming alert severity
     * @param subject alert title
     * @param message alert text
     * @return list of created {@link DispatchLogEntry} logs (empty if filtered by severity gate)
     */
    public List<DispatchLogEntry> dispatchMultiChannelAlert(
            String minSeverity, String severity, String subject, String message) {
        if (severityRank(severity) < severityRank(minSeverity)) {
            return List.of();
        }
        List<DispatchLogEntry> results = new ArrayList<>();
        if (!settingsRepository.get("alerts.telegram.token", "").isBlank()) {
            results.add(dispatchTelegramAlert(severity, message));
        }
        if (!settingsRepository.get("alerts.gmail.address", "").isBlank()) {
            results.add(dispatchEmailAlert(severity, subject, message));
        }
        if (results.isEmpty()) {
            results.add(logDispatch("LOCAL", normalizeSeverity(severity), "local-inbox",
                    message, false, "No external channel configured; logged locally."));
        }
        return results;
    }

    /**
     * Retrieves recent alert dispatch log entries ordered newest first.
     *
     * @param limit maximum number of rows to fetch
     * @return ordered list of {@link DispatchLogEntry} records
     */
    public List<DispatchLogEntry> getRecentLogs(int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        String sql = "SELECT id, channel, severity, recipient, message, delivered, status_note, created_at "
                + "FROM alert_dispatch_logs ORDER BY id DESC LIMIT ?";
        List<DispatchLogEntry> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, safeLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new DispatchLogEntry(
                            rs.getLong("id"),
                            rs.getString("channel"),
                            rs.getString("severity"),
                            rs.getString("recipient"),
                            rs.getString("message"),
                            rs.getInt("delivered") == 1,
                            rs.getString("status_note"),
                            rs.getString("created_at")
                    ));
                }
            }
            return list;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load alert_dispatch_logs", ex);
        }
    }

    private DispatchLogEntry logDispatch(
            String channel, String severity, String recipient, String message, boolean delivered, String note) {
        String now = LocalDateTime.now().toString();
        String sql = "INSERT INTO alert_dispatch_logs (channel, severity, recipient, message, delivered, status_note, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, channel);
            ps.setString(2, severity);
            ps.setString(3, recipient);
            ps.setString(4, message != null ? message : "");
            ps.setInt(5, delivered ? 1 : 0);
            ps.setString(6, note != null ? note : "");
            ps.setString(7, now);
            ps.executeUpdate();
            long id = 0L;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
            return new DispatchLogEntry(id, channel, severity, recipient, message, delivered, note, now);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to log alert dispatch", ex);
        }
    }

    private static HttpJsonTransport defaultHttpTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
        return (url, jsonPayload) -> {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new TransportResponse(resp.statusCode(), resp.body());
        };
    }

    private static SmtpEmailTransport defaultGmailSmtpTransport() {
        return (fromEmail, appPassword, recipientEmail, subject, body) -> {
            try (Socket raw = new Socket("smtp.gmail.com", 587)) {
                raw.setSoTimeout(8000);
                BufferedReader in = new BufferedReader(new InputStreamReader(raw.getInputStream(), StandardCharsets.US_ASCII));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(raw.getOutputStream(), StandardCharsets.US_ASCII));
                readSmtpCode(in, 220);
                sendSmtp(out, "EHLO stockpro.local");
                drainMultilineSmtp(in, 250);
                sendSmtp(out, "STARTTLS");
                readSmtpCode(in, 220);

                SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (SSLSocket tls = (SSLSocket) sf.createSocket(raw, "smtp.gmail.com", 587, true)) {
                    tls.setSoTimeout(8000);
                    BufferedReader tlsIn = new BufferedReader(new InputStreamReader(tls.getInputStream(), StandardCharsets.UTF_8));
                    BufferedWriter tlsOut = new BufferedWriter(new OutputStreamWriter(tls.getOutputStream(), StandardCharsets.UTF_8));
                    sendSmtp(tlsOut, "EHLO stockpro.local");
                    drainMultilineSmtp(tlsIn, 250);
                    sendSmtp(tlsOut, "AUTH LOGIN");
                    readSmtpCode(tlsIn, 334);
                    sendSmtp(tlsOut, Base64.getEncoder().encodeToString(fromEmail.getBytes(StandardCharsets.UTF_8)));
                    readSmtpCode(tlsIn, 334);
                    sendSmtp(tlsOut, Base64.getEncoder().encodeToString(appPassword.getBytes(StandardCharsets.UTF_8)));
                    readSmtpCode(tlsIn, 235);
                    sendSmtp(tlsOut, "MAIL FROM:<" + fromEmail + ">");
                    readSmtpCode(tlsIn, 250);
                    sendSmtp(tlsOut, "RCPT TO:<" + recipientEmail + ">");
                    readSmtpCode(tlsIn, 250);
                    sendSmtp(tlsOut, "DATA");
                    readSmtpCode(tlsIn, 354);
                    tlsOut.write("From: StockPro <" + fromEmail + ">\r\n"
                            + "To: <" + recipientEmail + ">\r\n"
                            + "Subject: " + subject + "\r\n"
                            + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                            + body.replace("\r\n.", "\r\n..") + "\r\n.\r\n");
                    tlsOut.flush();
                    readSmtpCode(tlsIn, 250);
                    sendSmtp(tlsOut, "QUIT");
                    return "Gmail STARTTLS 250 OK";
                }
            }
        };
    }

    private static void sendSmtp(BufferedWriter out, String cmd) throws Exception {
        out.write(cmd + "\r\n");
        out.flush();
    }

    private static void readSmtpCode(BufferedReader in, int expected) throws Exception {
        String line = in.readLine();
        if (line == null || !line.startsWith(String.valueOf(expected))) {
            throw new IllegalStateException("Unexpected SMTP response: " + line);
        }
    }

    private static void drainMultilineSmtp(BufferedReader in, int expected) throws Exception {
        String line;
        while ((line = in.readLine()) != null) {
            if (!line.startsWith(String.valueOf(expected))) {
                throw new IllegalStateException("Unexpected SMTP response: " + line);
            }
            if (line.length() < 4 || line.charAt(3) == ' ') {
                break;
            }
        }
    }

    private static String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return "INFO";
        }
        return severity.trim().toUpperCase(Locale.ROOT);
    }

    private static int severityRank(String severity) {
        return switch (normalizeSeverity(severity)) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "WARNING" -> 2;
            default -> 1;
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
