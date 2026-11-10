package com.mindcare.domain.supervision.entity;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
public enum SupervisionStatus { PENDING("pending"), DRAFT("draft"), SUBMITTED("submitted"), CANCELLED("cancelled");
 private final String value; SupervisionStatus(String value){this.value=value;}
 @Converter(autoApply=true) public static class DbConverter implements AttributeConverter<SupervisionStatus,String>{ public String convertToDatabaseColumn(SupervisionStatus value){return value==null?null:value.value;} public SupervisionStatus convertToEntityAttribute(String value){return Arrays.stream(values()).filter(v->v.value.equals(value)).findFirst().orElseThrow();} }
}
