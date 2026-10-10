package com.nordicframtiden.security;

import com.nordicframtiden.security.model.Role;
import com.nordicframtiden.security.repo.AppUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("accountAuthorization")
public class AccountAuthorization {

    private static final String ADMIN = "ROLE_ADMIN";
    private static final String PEOPLE = "PERM_PEOPLE";

    private final AppUserRepository userRepository;

    public AccountAuthorization(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public boolean canManage(Authentication authentication, Long targetUserId) {
        if (authentication == null || !authentication.isAuthenticated() || targetUserId == null) {
            return false;
        }

        if (hasAuthority(authentication, ADMIN)) {
            return true;
        }

        if (!hasAuthority(authentication, PEOPLE)) {
            return false;
        }

        return userRepository.findById(targetUserId)
            .map(user -> user.getRoles().size() == 1 && user.getRoles().contains(Role.USER))
            .orElse(false);
    }

    /**
     * Who may see the documents on a profile: exactly the canManage audience
     * (admins; PERM_PEOPLE holders for plain pharmacist accounts only — never
     * admin or staff profiles) plus the owner themselves.
     */
    public boolean canViewDocuments(Authentication authentication, Long targetUserId) {
        if (canManage(authentication, targetUserId)) {
            return true;
        }
        if (authentication == null || !authentication.isAuthenticated() || targetUserId == null) {
            return false;
        }

        return userRepository.findById(targetUserId)
            .map(user -> user.getUsername().equals(authentication.getName()))
            .orElse(false);
    }

    public boolean isOwner(Authentication authentication, Long targetUserId) {
        if (authentication == null || !authentication.isAuthenticated() || targetUserId == null) {
            return false;
        }
        return userRepository.findById(targetUserId)
            .map(user -> user.getUsername().equals(authentication.getName()))
            .orElse(false);
    }

    private static boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
            .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
