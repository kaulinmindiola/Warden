package io.github.kaulinmindiola.warden.iam.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores a {@link PasswordHash} as its encoded text. */
@Converter
public class PasswordHashConverter implements AttributeConverter<PasswordHash, String> {

    @Override
    public String convertToDatabaseColumn(PasswordHash attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public PasswordHash convertToEntityAttribute(String column) {
        return column == null ? null : new PasswordHash(column);
    }
}
