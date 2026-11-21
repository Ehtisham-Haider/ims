package com.stockpro.service;

import com.stockpro.domain.User;
import com.stockpro.domain.UserRole;
import com.stockpro.repository.UserRepository;
import com.stockpro.util.PasswordUtil;

import java.util.Optional;

/**
 * Handles staff authentication with BCrypt password verification, role validation, and Demo Mode sign-in.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class AuthService {
    private final UserRepository userRepository;
    private final AppSession session;

    /**
     * Creates an authentication service backed by the default repository and session.
     */
    public AuthService() {
        this(new UserRepository(), AppSession.getInstance());
    }

    /**
     * Creates an authentication service with explicit dependencies.
     *
     * @param userRepository staff account repository
     * @param session active user session singleton
     */
    public AuthService(UserRepository userRepository, AppSession session) {
        this.userRepository = userRepository;
        this.session = session;
    }

    /**
     * Authenticates a staff member using username, raw password, and optional expected role check.
     *
     * @param username login username
     * @param rawPassword plaintext password
     * @param expectedRole optional role selected in login dropdown, or {@code null} to accept account role
     * @return operation result containing authenticated {@link User} or diagnostic error
     */
    public InventoryOperationResult<User> login(String username, String rawPassword, UserRole expectedRole) {
        if (username == null || username.isBlank()) {
            return InventoryOperationResult.fail("Username is required.");
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            return InventoryOperationResult.fail("Password is required.");
        }

        Optional<User> userOpt = userRepository.findByUsername(username.trim());
        if (userOpt.isEmpty()) {
            return InventoryOperationResult.fail("Invalid username or password.");
        }

        User user = userOpt.get();
        if (!user.isActive()) {
            return InventoryOperationResult.fail("This staff account is currently disabled.");
        }
        if (!PasswordUtil.verify(rawPassword, user.getPasswordHash())) {
            return InventoryOperationResult.fail("Invalid username or password.");
        }
        if (expectedRole != null && user.getRole() != expectedRole) {
            return InventoryOperationResult.fail(
                    "Account '" + user.getUsername() + "' has role " + user.getRole().getDisplayName()
                            + ", not " + expectedRole.getDisplayName() + ".");
        }

        session.setCurrentUser(user);
        return InventoryOperationResult.ok(user, "Signed in as " + user.getFullName() + " (" + user.getRole() + ").");
    }

    /**
     * Signs in immediately using the built-in demo account matching the requested role.
     *
     * @param role role to preview in Demo Mode (defaults to {@link UserRole#OWNER})
     * @return operation result containing the active demo {@link User}
     */
    public InventoryOperationResult<User> loginDemoMode(UserRole role) {
        UserRole targetRole = role != null ? role : UserRole.OWNER;
        String demoUsername = switch (targetRole) {
            case OWNER -> "admin";
            case MANAGER -> "manager";
            case CASHIER -> "cashier";
        };
        Optional<User> demoUser = userRepository.findByUsername(demoUsername);
        User user = demoUser.orElseGet(() -> {
            User fallback = new User();
            fallback.setUsername(demoUsername);
            fallback.setFullName("Syed Ahmed");
            fallback.setRole(targetRole);
            fallback.setActive(true);
            return fallback;
        });
        session.setCurrentUser(user);
        return InventoryOperationResult.ok(user, "Demo Mode active as " + user.getRole().getDisplayName() + ".");
    }

    /**
     * Clears the active authenticated session.
     */
    public void logout() {
        session.clearUser();
    }

    /**
     * Creates a new staff account securely hashing its password with BCrypt.
     *
     * @param username unique login username
     * @param rawPassword initial plaintext password
     * @param fullName staff display name
     * @param role assigned {@link UserRole}
     * @return operation result containing created user or validation failure
     */
    public InventoryOperationResult<User> registerStaff(
            String username, String rawPassword, String fullName, UserRole role) {
        if (!session.isOwner()) {
            return InventoryOperationResult.fail("Only the Store Owner can create staff accounts.");
        }
        if (username == null || username.isBlank() || fullName == null || fullName.isBlank()) {
            return InventoryOperationResult.fail("Username and full name are required.");
        }
        if (rawPassword == null || rawPassword.length() < 4) {
            return InventoryOperationResult.fail("Password must be at least 4 characters.");
        }
        if (userRepository.findByUsername(username.trim()).isPresent()) {
            return InventoryOperationResult.fail("Username '" + username.trim() + "' is already taken.");
        }
        User user = new User();
        user.setUsername(username.trim());
        user.setPasswordHash(PasswordUtil.hash(rawPassword));
        user.setFullName(fullName.trim());
        user.setRole(role != null ? role : UserRole.CASHIER);
        user.setActive(true);
        User created = userRepository.insert(user);
        return InventoryOperationResult.ok(created, "Created staff account '" + created.getUsername() + "'.");
    }
}
