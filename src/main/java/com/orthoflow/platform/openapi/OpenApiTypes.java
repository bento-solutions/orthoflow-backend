package com.orthoflow.platform.openapi;

import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;

import java.time.LocalTime;

/**
 * Teaches the OpenAPI generator how this API really writes a few types.
 *
 * Jackson writes a {@code LocalTime} as the string {@code "08:00:00"}, but springdoc, left alone,
 * describes it as an object with {@code hour}, {@code minute}, {@code second} and {@code nano}. The
 * frontend's types are generated from that document, so the wrong description became a type no
 * request could satisfy. The mapping below is global so a new time field cannot reintroduce it.
 */
@Configuration
public class OpenApiTypes {

    static {
        SpringDocUtils.getConfig().replaceWithSchema(LocalTime.class,
                new StringSchema().format("time").example("08:00:00").description("A time of day, HH:mm or HH:mm:ss"));
    }
}
