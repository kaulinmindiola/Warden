package io.github.kaulinmindiola.warden.iam.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores a {@link TokenHash} as its 32 raw bytes ({@code bytea}). */
@Converter
public class TokenHashConverter implements AttributeConverter<TokenHash, byte[]> {

    @Override
    public byte[] convertToDatabaseColumn(TokenHash attribute) {
        return attribute == null ? null : attribute.bytes();
    }

    @Override
    public TokenHash convertToEntityAttribute(byte[] column) {
        return column == null ? null : TokenHash.of(column);
    }
}
