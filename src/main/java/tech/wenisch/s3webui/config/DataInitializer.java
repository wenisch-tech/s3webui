package tech.wenisch.s3webui.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tech.wenisch.s3webui.entity.AppSetting;
import tech.wenisch.s3webui.entity.UserRole;
import tech.wenisch.s3webui.repository.AppUserRepository;
import tech.wenisch.s3webui.service.AppSettingsService;
import tech.wenisch.s3webui.service.UserService;

/**
 * Seeds the default administrator and the default runtime settings on first start.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final AppUserRepository userRepository;
    private final UserService userService;
    private final AppSettingsService appSettingsService;

    @Value("${app.admin.email:admin@s3webui.local}")
    private String adminEmail;

    @Value("${app.admin.password:admin}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        initDefaultAdmin();
        initDefaultSettings();
    }

    private void initDefaultAdmin() {
        if (userRepository.count() != 0) {
            return;
        }
        log.warn("No users found. Creating default admin user: {}", adminEmail);
        log.warn("*** SECURITY WARNING: Change the default admin password immediately! ***");
        userService.createUser(adminEmail, adminPassword, UserRole.ADMIN);
    }

    private void initDefaultSettings() {
        appSettingsService.seedDefault(AppSetting.ALLOW_USER_SUPPLIED_CREDENTIALS, Boolean.TRUE.toString());
    }
}
