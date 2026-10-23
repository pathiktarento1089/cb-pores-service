package com.igot.cb.contentpartner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.PayloadValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/** Runs the real V2 create schema (no protocol / ltiConfiguration) through the real PayloadValidation. */
class ContentPartnerV2CreateSchemaTest {

    private static final String CREATE_SCHEMA = "/payloadValidation/contentProviderValidationV2Create.json";
    private final ObjectMapper mapper = new ObjectMapper();
    private final PayloadValidation validation = new PayloadValidation();

    private ObjectNode partner() {
        ObjectNode node = mapper.createObjectNode();
        node.put("contentPartnerName", "Moodle Provider");
        node.put("description", "Moodle courses");
        node.put("websiteUrl", "https://moodle.example.com");
        node.put("link", "https://moodle.example.com/courses");
        return node;
    }

    private void assertRejected(ObjectNode payload) {
        CustomException ex = assertThrows(CustomException.class, () -> validation.validatePayload(CREATE_SCHEMA, payload));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getHttpStatusCode());
    }

    @Test
    void partnerWithoutProtocolIsValid() {
        assertDoesNotThrow(() -> validation.validatePayload(CREATE_SCHEMA, partner()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SAML", "LTI"})
    void protocolIsNotAllowedAtCreateTime(String protocol) {
        ObjectNode node = partner();
        node.put("protocol", protocol);
        assertRejected(node);
    }

    @Test
    void ltiConfigurationIsNotAllowedAtCreateTime() {
        ObjectNode node = partner();
        node.set("ltiConfiguration", mapper.createObjectNode());
        assertRejected(node);
    }

    @ParameterizedTest
    @ValueSource(strings = {"contentPartnerName", "websiteUrl", "link", "description"})
    void existingRequiredFieldsStillApply(String field) {
        ObjectNode node = partner();
        node.remove(field);
        assertRejected(node);
    }

    @Test
    void invalidUrlIsRejected() {
        ObjectNode node = partner();
        node.put("websiteUrl", "not a url");
        assertRejected(node);
    }

    @Test
    void existingConditionalLimitRulesStillApply() {
        ObjectNode node = partner();
        node.put("isUserWiseLimitEnabled", true);
        assertRejected(node);
        node.put("userWiseLimit", 5);
        assertDoesNotThrow(() -> validation.validatePayload(CREATE_SCHEMA, node));
    }

    @Test
    void unknownFieldIsRejected() {
        ObjectNode node = partner();
        node.put("unknown", "x");
        assertRejected(node);
    }
}
