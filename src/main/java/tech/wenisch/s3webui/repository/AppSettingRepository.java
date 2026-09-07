package tech.wenisch.s3webui.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tech.wenisch.s3webui.entity.AppSetting;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
