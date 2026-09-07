package tech.wenisch.s3webui.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tech.wenisch.s3webui.service.CryptoService;

/**
 * Encrypts a column with {@link CryptoService} so secrets never hit the database in the clear.
 *
 * <p>Hibernate may instantiate converters outside the Spring context, so the service is held in a
 * static field populated by Spring on startup rather than injected per instance.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static CryptoService cryptoService;

    @Autowired
    void setCryptoService(@Lazy CryptoService service) {
        EncryptedStringConverter.cryptoService = service;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : crypto().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : crypto().decrypt(dbData);
    }

    private CryptoService crypto() {
        if (cryptoService == null) {
            throw new IllegalStateException("EncryptedStringConverter used before the Spring context was ready");
        }
        return cryptoService;
    }
}
