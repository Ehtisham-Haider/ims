package com.stockpro.service;

import com.stockpro.domain.User;
import com.stockpro.domain.UserRole;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies BCrypt staff authentication, role checks, Demo Mode, and Spotlight command parsing.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
class AuthAndCommandTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        AppSession.getInstance().clearUser();
        dbManager = DatabaseManager.createForFile(tempDir.resolve("auth-test.db"));
        authService = new AuthService(new UserRepository(dbManager), AppSession.getInstance());
    }

    @AfterEach
    void tearDown() {
        AppSession.getInstance().clearUser();
        dbManager.close();
    }

    @Test
    @DisplayName("Authenticates seeded Owner account with BCrypt and rejects invalid password or mismatched role")
    void loginValidationAndRoleGuards() {
        InventoryOperationResult<User> ok = authService.login("admin", "admin123", UserRole.OWNER);
        assertTrue(ok.isSuccess());
        assertTrue(AppSession.getInstance().isOwner());
        assertTrue(AppSession.getInstance().canManageInventory());

        InventoryOperationResult<User> wrongPass = authService.login("admin", "wrong", UserRole.OWNER);
        assertFalse(wrongPass.isSuccess());

        InventoryOperationResult<User> wrongRole = authService.login("cashier", "cashier123", UserRole.OWNER);
        assertFalse(wrongRole.isSuccess());

        InventoryOperationResult<User> cashierOk = authService.login("cashier", "cashier123", UserRole.CASHIER);
        assertTrue(cashierOk.isSuccess());
        assertFalse(AppSession.getInstance().canManageInventory());
    }

    @Test
    @DisplayName("Demo mode logs into selected role immediately without entering credentials")
    void demoModeLogin() {
        InventoryOperationResult<User> demo = authService.loginDemoMode(UserRole.MANAGER);
        assertTrue(demo.isSuccess());
        assertEquals(UserRole.MANAGER, demo.getData().getRole());
        assertTrue(AppSession.getInstance().canManageInventory());
    }

    @Test
    @DisplayName("CommandParser parses quick-add, theme toggle, navigation, PDF export, and search queries")
    void spotlightCommandParsing() {
        CommandParser.ParsedCommand add = CommandParser.parse("add 10 soap");
        assertEquals(CommandParser.ActionType.QUICK_ADD, add.actionType());
        assertEquals(10, add.quantity());
        assertEquals("soap", add.argument());

        CommandParser.ParsedCommand theme = CommandParser.parse("toggle theme");
        assertEquals(CommandParser.ActionType.TOGGLE_THEME, theme.actionType());

        CommandParser.ParsedCommand pdf = CommandParser.parse("export pdf");
        assertEquals(CommandParser.ActionType.EXPORT_PDF, pdf.actionType());

        CommandParser.ParsedCommand nav = CommandParser.parse("go inventory");
        assertEquals(CommandParser.ActionType.NAVIGATE, nav.actionType());
        assertEquals("INVENTORY", nav.targetScreen());

        CommandParser.ParsedCommand search = CommandParser.parse("wireless mouse");
        assertEquals(CommandParser.ActionType.SEARCH_PRODUCTS, search.actionType());
        assertEquals("wireless mouse", search.argument());
    }
}
