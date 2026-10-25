package io.github.kaulinmindiola.warden.iam.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores an {@link EmailAddress} as its normalized text. */
@Converter
public class EmailAddressConverter implements AttributeConverter<EmailAddress, String> {

    @Override
    public String convertToDatabaseColumn(EmailAddress attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public EmailAddress convertToEntityAttribute(String column) {
        return column == null ? null : new EmailAddress(column);
    }
}
