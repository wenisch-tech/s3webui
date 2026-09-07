package tech.wenisch.s3webui.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_setting")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppSetting {

    /** Whether users may type in their own S3 credentials instead of picking a stored key. */
    public static final String ALLOW_USER_SUPPLIED_CREDENTIALS = "allowUserSuppliedCredentials";

    @Id
    @Column(name = "setting_key")
    private String settingKey;

    @Column(name = "setting_value")
    private String settingValue;
}
