package com.igot.cb.contentpartner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.PayloadValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real contentProviderValidationV2Update.json through the real PayloadValidation
 * (the service tests mock PayloadValidation, so they never exercise the schema).
 */
class ContentPartnerV2SchemaTest {

    private static final String V2_SCHEMA = "/payloadValidation/contentProviderValidationV2Update.json";
    private final ObjectMapper mapper = new ObjectMapper();
    private final PayloadValidation validation = new PayloadValidation();

    private ObjectNode basePartner() {
        ObjectNode node = mapper.createObjectNode();
        node.put("contentPartnerName", "Moodle Provider");
        node.put("description", "Moodle courses");
        node.put("websiteUrl", "https://moodle.example.com");
        node.put("link", "https://moodle.example.com/courses");
        return node;
    }

    /**
     * Request contract: only the endpoint URLs are supplied; the four other fields are server-managed.
     */
    private ObjectNode validLti() {
        ObjectNode lti = mapper.createObjectNode();
        lti.put("toolPublicJwksUrl", "https://provider.example.com/.well-known/jwks.json");
        lti.put("oidcLoginInitiationUrl", "https://provider.example.com/login");
        lti.put("redirectLaunchUrl", "https://provider.example.com/launch");
        lti.put("deepLinkingUrl", "https://provider.example.com/deep-link");
        return lti;
    }

    private ObjectNode ltiPartner(ObjectNode lti) {
        ObjectNode node = basePartner();
        node.put("protocol", "LTI");
        node.set("ltiConfiguration", lti);
        return node;
    }

    private void assertRejected(ObjectNode payload) {
        CustomException ex = assertThrows(CustomException.class, () -> validation.validatePayload(V2_SCHEMA, payload));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatusCode());
    }

    @Test
    void payloadWithoutProtocolIsRejectedBecauseProtocolIsRequired() {
        assertRejected(basePartner());
    }

    @Test
    void explicitSamlWithoutLtiConfigurationIsValid() {
        ObjectNode node = basePartner();
        node.put("protocol", "SAML");
        assertFalse(node.has("ltiConfiguration"));
        assertDoesNotThrow(() -> validation.validatePayload(V2_SCHEMA, node));
    }

    @Test
    void samlIgnoresLtiConfigurationContents() {
        ObjectNode empty = basePartner();
        empty.put("protocol", "SAML");
        empty.set("ltiConfiguration", mapper.createObjectNode());
        assertDoesNotThrow(() -> validation.validatePayload(V2_SCHEMA, empty));

        ObjectNode partial = basePartner();
        partial.put("protocol", "SAML");
        ObjectNode lti = mapper.createObjectNode();
        lti.put("redirectLaunchUrl", "https://provider.example.com/launch");
        partial.set("ltiConfiguration", lti);
        assertDoesNotThrow(() -> validation.validatePayload(V2_SCHEMA, partial));
    }

    @Test
    void ltiRequiresLtiConfiguration() {
        ObjectNode node = basePartner();
        node.put("protocol", "LTI");
        assertRejected(node);
    }

    @Test
    void validLtiIsAccepted() {
        assertDoesNotThrow(() -> validation.validatePayload(V2_SCHEMA, ltiPartner(validLti())));
    }

    @Test
    void deepLinkingUrlIsOptional() {
        ObjectNode lti = validLti();
        lti.remove("deepLinkingUrl");
        assertDoesNotThrow(() -> validation.validatePayload(V2_SCHEMA, ltiPartner(lti)));
    }

    @Test
    void existingRequiredPartnerFieldsStillApply() {
        ObjectNode node = ltiPartner(validLti());
        node.remove("link");
        assertRejected(node);
        node = ltiPartner(validLti());
        node.remove("description");
        assertRejected(node);
    }

    @Test
    void serverManagedFieldsAreNotRequiredInTheRequest() {
        ObjectNode lti = validLti();
        assertFalse(lti.has("ltiVersion") || lti.has("toolIssuer") || lti.has("clientId") || lti.has("deploymentId"));
        assertDoesNotThrow(() -> validation.validatePayload(V2_SCHEMA, ltiPartner(lti)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ltiVersion", "toolIssuer", "clientId", "deploymentId"})
    void serverManagedFieldsAreRejectedIfSentInTheRequest(String field) {
        ObjectNode lti = validLti();
        lti.put(field, "value");
        assertRejected(ltiPartner(lti));
    }

    @ParameterizedTest
    @ValueSource(strings = {"toolPublicJwksUrl", "oidcLoginInitiationUrl", "redirectLaunchUrl"})
    void missingRequiredLtiFieldIsRejected(String field) {
        ObjectNode lti = validLti();
        lti.remove(field);
        assertRejected(ltiPartner(lti));
    }

    @Test
    void emptyLtiConfigurationIsRejected() {
        assertRejected(ltiPartner(mapper.createObjectNode()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"toolPublicJwksUrl", "oidcLoginInitiationUrl", "redirectLaunchUrl", "deepLinkingUrl"})
    void invalidLtiUrlIsRejected(String field) {
        ObjectNode lti = validLti();
        lti.put(field, "not a url");
        assertRejected(ltiPartner(lti));
    }

    @Test
    void wrongTypeAndNullLtiValuesAreRejected() {
        ObjectNode numeric = validLti();
        numeric.put("redirectLaunchUrl", 42);
        assertRejected(ltiPartner(numeric));
        ObjectNode nullValue = validLti();
        nullValue.putNull("toolPublicJwksUrl");
        assertRejected(ltiPartner(nullValue));
    }

    @Test
    void unexpectedLtiFieldIsRejected() {
        ObjectNode lti = validLti();
        lti.put("unknown", "x");
        assertRejected(ltiPartner(lti));
    }

    @Test
    void unsupportedOrNullProtocolIsRejected() {
        ObjectNode node = basePartner();
        node.put("protocol", "OAUTH");
        assertRejected(node);
        node.put("protocol", "lti");
        assertRejected(node);
        node.putNull("protocol");
        assertRejected(node);
    }

    @Test
    void ltiConfigurationMustBeAnObject() {
        ObjectNode node = basePartner();
        node.put("protocol", "LTI");
        node.put("ltiConfiguration", "text");
        assertRejected(node);
    }

    @Test
    void v1SchemaStillRejectsProtocolFields() {
        ObjectNode payload = ltiPartner(validLti());
        CustomException ex = assertThrows(CustomException.class,
                () -> validation.validatePayload(Constants.PAYLOAD_VALIDATION_FILE_CONTENT_PROVIDER, payload));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatusCode());
    }
}
