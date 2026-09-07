package tech.wenisch.s3webui.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.s3webui.entity.AppUser;
import tech.wenisch.s3webui.entity.AuthProvider;
import tech.wenisch.s3webui.entity.UserRole;
import tech.wenisch.s3webui.repository.AppUserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        AppUser user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user with e-mail " + email));

        if (user.getPasswordHash() == null || user.getPasswordHash().isEmpty()) {
            throw new UsernameNotFoundException("User " + email + " has no local password; sign in with SSO");
        }

        return User.withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .disabled(!user.isEnabled())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
                .build();
    }

    @Transactional(readOnly = true)
    public Optional<AppUser> findByEmail(String email) {
        return email == null ? Optional.empty() : userRepository.findByEmailIgnoreCase(email);
    }

    @Transactional(readOnly = true)
    public List<AppUser> listUsers() {
        return userRepository.findAllByOrderByEmailAsc();
    }

    @Transactional
    public AppUser createUser(String email, String rawPassword, UserRole role) {
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("A user with e-mail " + email + " already exists");
        }
        return userRepository.save(AppUser.builder()
                .email(email.trim())
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(role)
                .provider(AuthProvider.LOCAL)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
    }

    @Transactional
    public void updateLastLogin(String email) {
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);
        });
    }

    /**
     * Looks up - and optionally creates - the local record backing an OIDC login. Returns an empty
     * Optional when the user is unknown and automatic creation is switched off, or when the account
     * has been disabled.
     */
    @Transactional
    public Optional<AppUser> syncOidcUser(String email, String displayName, boolean createUsers) {
        Optional<AppUser> existing = userRepository.findByEmailIgnoreCase(email);
        if (existing.isPresent()) {
            AppUser user = existing.get();
            if (!user.isEnabled()) {
                return Optional.empty();
            }
            user.setLastLoginAt(LocalDateTime.now());
            if (displayName != null && !displayName.isBlank()) {
                user.setDisplayName(displayName);
            }
            return Optional.of(userRepository.save(user));
        }

        if (!createUsers) {
            log.warn("Rejecting OIDC login for unknown user {} because OIDC_CREATEUSERS is false", email);
            return Optional.empty();
        }

        log.info("Provisioning local record for OIDC user {}", email);
        return Optional.of(userRepository.save(AppUser.builder()
                .email(email)
                .passwordHash("")
                .displayName(displayName)
                .role(UserRole.USER)
                .provider(AuthProvider.OIDC)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .lastLoginAt(LocalDateTime.now())
                .build()));
    }

    @Transactional
    public AppUser updateUser(Long id, UserRole role, Boolean enabled, String displayName) {
        AppUser user = requireUser(id);
        boolean losesAdmin = user.getRole() == UserRole.ADMIN
                && ((role != null && role != UserRole.ADMIN) || Boolean.FALSE.equals(enabled));
        if (losesAdmin && countAdmins() <= 1) {
            throw new IllegalArgumentException("Cannot demote or disable the last administrator");
        }
        if (role != null) {
            user.setRole(role);
        }
        if (enabled != null) {
            user.setEnabled(enabled);
        }
        if (displayName != null) {
            user.setDisplayName(displayName);
        }
        return userRepository.save(user);
    }

    @Transactional
    public void resetPassword(Long id, String rawPassword) {
        AppUser user = requireUser(id);
        if (user.getProvider() != AuthProvider.LOCAL) {
            throw new IllegalArgumentException("Only local users have a password");
        }
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        AppUser user = requireUser(id);
        if (user.getRole() == UserRole.ADMIN && countAdmins() <= 1) {
            throw new IllegalArgumentException("Cannot delete the last administrator");
        }
        userRepository.delete(user);
    }

    @Transactional(readOnly = true)
    public long countAdmins() {
        return userRepository.findAll().stream()
                .filter(user -> user.getRole() == UserRole.ADMIN && user.isEnabled())
                .count();
    }

    private AppUser requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No user with id " + id));
    }
}
