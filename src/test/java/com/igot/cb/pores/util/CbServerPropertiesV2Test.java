package com.igot.cb.pores.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.env.MockEnvironment;

/**
 * Covers the Content Partner V2 / LTI settings in CbServerProperties: accessors, the defaults declared
 * in the @Value expressions, overrides, and the values shipped in application.properties.
 */
class CbServerPropertiesV2Test {

    private String valueExpression(String fieldName) throws NoSuchFieldException {
        Field field = CbServerProperties.class.getDeclaredField(fieldName);
        return field.getAnnotation(Value.class).value();
    }

    private String resolve(String fieldName, MockEnvironment environment) throws NoSuchFieldException {
        return environment.resolvePlaceholders(valueExpression(fieldName));
    }

    private Properties applicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties must be on the classpath");
            properties.load(in);
        }
        return properties;
    }

    @Test
    void accessorsStoreAndReturnV2AndLtiSettings() {
        CbServerProperties properties = new CbServerProperties();

        properties.setContentPartnerV2ValidationSchemaPath("/schema.json");
        properties.setLtiVersion("1.3");
        properties.setLtiToolIssuer("https://issuer.example.com");
        properties.setLtiClientId("client");
        properties.setLtiDeploymentId("deployment");
        properties.setContentPartnerSearchCacheKey("cache-key");

        assertEquals("/schema.json", properties.getContentPartnerV2ValidationSchemaPath());
        assertEquals("1.3", properties.getLtiVersion());
        assertEquals("https://issuer.example.com", properties.getLtiToolIssuer());
        assertEquals("client", properties.getLtiClientId());
        assertEquals("deployment", properties.getLtiDeploymentId());
        assertEquals("cache-key", properties.getContentPartnerSearchCacheKey());
    }

    @Test
    void defaultsApplyWhenPropertiesAreAbsent() throws NoSuchFieldException {
        MockEnvironment empty = new MockEnvironment();

        assertEquals("1.3", resolve("ltiVersion", empty));
        assertEquals("", resolve("ltiToolIssuer", empty));
        assertEquals("", resolve("ltiClientId", empty));
        assertEquals("", resolve("ltiDeploymentId", empty));
        assertEquals(Constants.JWT_SECRET_KEY, resolve("contentPartnerSearchCacheKey", empty));
    }

    @Test
    void configuredPropertiesOverrideTheDefaults() throws NoSuchFieldException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("content.partner.v2.validation.schema.path", "/custom.json")
                .withProperty("lti.version", "1.4")
                .withProperty("lti.tool.issuer", "https://issuer.example.com")
                .withProperty("lti.client.id", "client-1")
                .withProperty("lti.deployment.id", "deployment-1")
                .withProperty("content.partner.search.cache.key", "other-key");

        assertEquals("/custom.json", resolve("contentPartnerV2ValidationSchemaPath", environment));
        assertEquals("1.4", resolve("ltiVersion", environment));
        assertEquals("https://issuer.example.com", resolve("ltiToolIssuer", environment));
        assertEquals("client-1", resolve("ltiClientId", environment));
        assertEquals("deployment-1", resolve("ltiDeploymentId", environment));
        assertEquals("other-key", resolve("contentPartnerSearchCacheKey", environment));
    }

    @Test
    void schemaPathPropertyHasNoDefaultSoItMustBeConfigured() throws NoSuchFieldException {
        assertEquals("${content.partner.v2.validation.schema.path}", valueExpression("contentPartnerV2ValidationSchemaPath"));
    }

    @Test
    void applicationPropertiesPointToAnExistingV2Schema() throws IOException {
        String schemaPath = applicationProperties().getProperty("content.partner.v2.validation.schema.path");

        assertNotNull(schemaPath);
        assertNotNull(getClass().getResource(schemaPath), "V2 schema not found on classpath: " + schemaPath);
    }

    @Test
    void applicationPropertiesKeepV2CacheKeyEqualToV1() throws IOException {
        Properties properties = applicationProperties();

        assertEquals(Constants.JWT_SECRET_KEY, properties.getProperty("content.partner.search.cache.key"));
        assertEquals("1.3", properties.getProperty("lti.version"));
    }
}
