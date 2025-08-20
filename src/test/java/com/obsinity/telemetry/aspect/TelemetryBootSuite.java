package com.obsinity.telemetry.aspect;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Test meta-annotation for Obsinity SDK suites that need a fresh Spring context (and thus a fresh
 * TelemetryEventHandlerScanner + handler registry) per test class.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * @TelemetryBootSuite(classes = MySuite.Config.class)
 * class MySuite {
 *   @TestConfiguration
 *   static class Config {
 *     // define scanner + handlerGroups + dispatch bus + test-only receivers here
 *   }
 * }
 * }</pre>
 *
 * <p>Notes: - Builds a dedicated context for each annotated test class. - Marks the context as dirty AFTER_CLASS so
 * subsequent suites get a clean scan. - Defaults web environment to NONE and sets
 * spring.main.web-application-type=none.
 */
@Target(ElementType.TYPE)
@Retention(RUNTIME)
@Documented
@Inherited
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public @interface TelemetryBootSuite {

	/**
	 * Shortcut to {@link SpringBootTest#classes()} so each suite can provide its own {@code @TestConfiguration} with
	 * receivers to be discovered by the scanner.
	 */
	@AliasFor(annotation = SpringBootTest.class, attribute = "classes")
	Class<?>[] classes() default {};

	/**
	 * Shortcut to {@link SpringBootTest#properties()} with a sensible default to avoid spinning up a web stack in unit
	 * tests.
	 */
	@AliasFor(annotation = SpringBootTest.class, attribute = "properties")
	String[] properties() default {"spring.main.web-application-type=none"};

	/** Shortcut to {@link SpringBootTest#webEnvironment()} (defaults to NONE). */
	@AliasFor(annotation = SpringBootTest.class, attribute = "webEnvironment")
	SpringBootTest.WebEnvironment webEnvironment() default SpringBootTest.WebEnvironment.NONE;
}
