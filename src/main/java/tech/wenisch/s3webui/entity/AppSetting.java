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

    /** Whether a user may view the secret for a stored S3 key they are allowed to use. */
    public static final String ALLOW_USERS_TO_REVEAL_KEYS = "allowUsersToRevealKeys";

    @Id
    @Column(name = "setting_key")
    private String settingKey;

    @Column(name = "setting_value")
    private String settingValue;
}
