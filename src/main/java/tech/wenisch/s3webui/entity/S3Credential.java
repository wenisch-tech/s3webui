package tech.wenisch.s3webui.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A named S3 connection an administrator configured, handed out to users through
 * {@link S3CredentialGrant}s.
 */
@Entity
@Table(name = "s3_credential")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class S3Credential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    private String endpointUrl;

    private String region;

    private String accessKey;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2048)
    private String secretKey;

    @Builder.Default
    private boolean insecureSkipTlsVerify = false;

    @Builder.Default
    private boolean enabled = true;

    private LocalDateTime createdAt;

    private String createdBy;

    @OneToMany(mappedBy = "credential", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<S3CredentialGrant> grants = new ArrayList<>();
}
