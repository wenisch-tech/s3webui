package tech.wenisch.s3webui.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "s3_credential_grant")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "credential")
@EqualsAndHashCode(exclude = "credential")
public class S3CredentialGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credential_id")
    private S3Credential credential;

    @Enumerated(EnumType.STRING)
    private GrantType grantType;

    /** The e-mail, role or group this grant targets; {@code null} for {@code ALL_AUTHENTICATED}. */
    private String grantValue;
}
