package dev.floor42.server.api;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A list of a request body holds no null: a null item is a wrong body (400, see {@code ErrorMappers}), never a crash
 * further in. On every list component of a request record.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD})
@JacksonAnnotationsInside
@JsonSetter(contentNulls = Nulls.FAIL)
@interface NoNullItems {}
