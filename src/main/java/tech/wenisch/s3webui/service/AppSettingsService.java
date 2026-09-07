package tech.wenisch.s3webui.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.s3webui.entity.AppSetting;
import tech.wenisch.s3webui.repository.AppSettingRepository;

/**
 * Runtime settings an administrator can change from the settings panel.
 */
@Service
@RequiredArgsConstructor
public class AppSettingsService {

    private final AppSettingRepository settingRepository;

    @Transactional(readOnly = true)
    public boolean isUserSuppliedCredentialsAllowed() {
        return getBoolean(AppSetting.ALLOW_USER_SUPPLIED_CREDENTIALS, true);
    }

    @Transactional
    public void setUserSuppliedCredentialsAllowed(boolean allowed) {
        setValue(AppSetting.ALLOW_USER_SUPPLIED_CREDENTIALS, Boolean.toString(allowed));
    }

    @Transactional(readOnly = true)
    public boolean getBoolean(String key, boolean defaultValue) {
        return settingRepository.findById(key)
                .map(AppSetting::getSettingValue)
                .map(Boolean::parseBoolean)
                .orElse(defaultValue);
    }

    @Transactional
    public void setValue(String key, String value) {
        settingRepository.save(AppSetting.builder().settingKey(key).settingValue(value).build());
    }

    /** Writes a value only when the key is absent, so restarts do not clobber admin changes. */
    @Transactional
    public void seedDefault(String key, String value) {
        if (!settingRepository.existsById(key)) {
            setValue(key, value);
        }
    }
}
