package com.igot.cb.contentpartner.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.contentpartner.entity.ContentPartnerEntity;
import com.igot.cb.contentpartner.repository.ContentPartnerRepository;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.PayloadValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContentPartnerServiceImplV2Test {

    private static final String V2_SCHEMA = "/payloadValidation/contentProviderValidationV2Update.json";
    private static final String V2_CREATE_SCHEMA = "/payloadValidation/contentProviderValidationV2Create.json";
    private static final String ES_PATH = "/EsFieldsmapping/cpEsRequiredFieldsJsonFilePath.json";
    private static final String LTI_VERSION = "1.3";
    private static final String LTI_ISSUER = "https://tool.example.com";
    private static final String LTI_CLIENT_ID = "server-client-id";
    private static final String LTI_DEPLOYMENT_ID = "server-deployment-id";

    @Mock
    private PayloadValidation payloadValidation;
    @Mock
    private ContentPartnerRepository entityRepository;
    @Mock
    private EsUtilService esUtilService;
    @Mock
    private CacheService cacheService;
    @Mock
    private RedisTemplate<String, SearchResult> redisTemplate;
    @Mock
    private CbServerProperties cbServerProperties;
    @Mock
    private SecureRandom secureRandom;

    private final ObjectMapper mapper = new ObjectMapper();
    private ContentPartnerServiceImplV2 service;

    @BeforeEach
    void setUp() {
        service = new ContentPartnerServiceImplV2(payloadValidation, entityRepository, mapper, esUtilService,
                cacheService, redisTemplate, cbServerProperties, secureRandom);
        lenient().when(cbServerProperties.getContentPartnerV2ValidationSchemaPath()).thenReturn(V2_SCHEMA);
        lenient().when(cbServerProperties.getContentPartnerV2CreateValidationSchemaPath()).thenReturn(V2_CREATE_SCHEMA);
        lenient().when(cbServerProperties.getElasticContentJsonPath()).thenReturn(ES_PATH);
        lenient().when(cbServerProperties.getContentPartnerSearchCacheKey()).thenReturn(Constants.JWT_SECRET_KEY);
        lenient().when(cbServerProperties.getLtiVersion()).thenReturn(LTI_VERSION);
        lenient().when(cbServerProperties.getLtiToolIssuer()).thenReturn(LTI_ISSUER);
        lenient().when(cbServerProperties.getLtiClientId()).thenReturn(LTI_CLIENT_ID);
        lenient().when(cbServerProperties.getLtiDeploymentId()).thenReturn(LTI_DEPLOYMENT_ID);
        lenient().when(cbServerProperties.getPartnerCodeChar()).thenReturn("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        lenient().when(secureRandom.nextInt(anyInt())).thenReturn(0);
        lenient().when(entityRepository.findByPartnerCode(anyString())).thenReturn(Optional.empty());
        lenient().when(entityRepository.findByContentPartnerName(anyString())).thenReturn(Optional.empty());
        lenient().when(entityRepository.save(any(ContentPartnerEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------- helpers ----------

    /**
     * What a client sends: only the endpoint URLs.
     */
    private ObjectNode ltiConfig() {
        ObjectNode lti = mapper.createObjectNode();
        lti.put("toolPublicJwksUrl", "https://provider.example.com/.well-known/jwks.json");
        lti.put("oidcLoginInitiationUrl", "https://provider.example.com/login");
        lti.put("redirectLaunchUrl", "https://provider.example.com/launch");
        lti.put("deepLinkingUrl", "https://provider.example.com/deep-link");
        return lti;
    }

    /**
     * What must be stored: the request URLs plus the four server-managed values.
     */
    private ObjectNode completedLtiConfig() {
        ObjectNode lti = ltiConfig();
        lti.put("ltiVersion", LTI_VERSION);
        lti.put("toolIssuer", LTI_ISSUER);
        lti.put("clientId", LTI_CLIENT_ID);
        lti.put("deploymentId", LTI_DEPLOYMENT_ID);
        return lti;
    }

    private ObjectNode createRequest(String name) {
        ObjectNode node = mapper.createObjectNode();
        node.put(Constants.CONTENT_PARTNER_NAME, name);
        node.put("websiteUrl", "https://example.com");
        return node;
    }

    private ObjectNode updateRequest(ObjectNode data) {
        ObjectNode request = mapper.createObjectNode();
        request.put(Constants.ID, "id-123");
        request.set(Constants.DATA, data);
        return request;
    }

    private ContentPartnerEntity storedPartner(ObjectNode data) {
        ContentPartnerEntity entity = new ContentPartnerEntity();
        entity.setId("id-123");
        entity.setCreatedOn(new Timestamp(System.currentTimeMillis() - 10_000));
        entity.setData(data);
        return entity;
    }

    private ObjectNode storedLegacyData() {
        ObjectNode data = mapper.createObjectNode();
        data.put(Constants.PARTNERCODE, "IGOT-OLD-ABCDE");
        data.put("websiteUrl", "https://old.example.com");
        data.put("description", "old description");
        return data;
    }

    private ObjectNode storedLtiData() {
        ObjectNode data = storedLegacyData();
        data.put(Constants.PROTOCOL, "LTI");
        data.set(Constants.LTI_CONFIGURATION, completedLtiConfig());
        return data;
    }

    private ContentPartnerEntity captureSaved() {
        ArgumentCaptor<ContentPartnerEntity> captor = ArgumentCaptor.forClass(ContentPartnerEntity.class);
        verify(entityRepository).save(captor.capture());
        return captor.getValue();
    }

    private ApiResponse update(ContentPartnerEntity stored, ObjectNode data) {
        when(entityRepository.findById("id-123")).thenReturn(Optional.of(stored));
        return service.createOrUpdate(updateRequest(data));
    }

    // ---------- create ----------

    @Test
    void create_validatesAgainstTheV2CreateSchema() {
        service.createOrUpdate(createRequest("Lti Partner"));

        verify(payloadValidation).validatePayload(eq(V2_CREATE_SCHEMA), any(JsonNode.class));
        verify(payloadValidation, never()).validatePayload(eq(V2_SCHEMA), any(JsonNode.class));
    }

    @Test
    void create_legacyRequestWithoutProtocolIsStillAccepted() {
        ApiResponse response = service.createOrUpdate(createRequest("Legacy Partner"));

        assertEquals(HttpStatus.OK, response.getResponseCode());
        JsonNode data = captureSaved().getData();
        assertFalse(data.has(Constants.PROTOCOL));
        assertFalse(data.has(Constants.LTI_CONFIGURATION));
    }

    @Test
    void create_serverDefaultsOverrideClientValues() {
        ObjectNode request = createRequest("Lti Partner");
        request.put(Constants.PROVIDER_TYPE, "internal");
        request.put(Constants.IS_ACTIVE, false);

        service.createOrUpdate(request);

        JsonNode data = captureSaved().getData();
        assertEquals(Constants.EXTERNAL, data.path(Constants.PROVIDER_TYPE).asText());
        assertTrue(data.path(Constants.IS_ACTIVE).asBoolean());
        assertEquals(0, data.path(Constants.TOTAL_COURSES_COUNT).asInt());
        assertTrue(data.path(Constants.PROVIDER_TIPS).isArray());
        assertTrue(data.path(Constants.SEARCHTAGS).toString().contains("lti partner"));
    }

    @Test
    void create_duplicateNameReturnsBadRequestAndDoesNotPersist() {
        when(entityRepository.findByContentPartnerName("Lti Partner"))
                .thenReturn(Optional.of(new ContentPartnerEntity()));

        ApiResponse response = service.createOrUpdate(createRequest("Lti Partner"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.CONTENT_PARTNER_NAME_ALREADY_PRESENT, response.getParams().getErrMsg());
        verify(entityRepository, never()).save(any());
        verify(esUtilService, never()).addDocument(anyString(), anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    void create_schemaFailureReturnsBadRequestAndDoesNotPersist() {
        doThrow(new CustomException("Validation Error", "bad ltiConfiguration", HttpStatus.BAD_REQUEST))
                .when(payloadValidation).validatePayload(anyString(), any());

        ApiResponse response = service.createOrUpdate(createRequest("Lti Partner"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("bad ltiConfiguration", response.getParams().getErrMsg());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        verify(entityRepository, never()).save(any());
    }

    @Test
    void create_indexesAndCachesLikeV1() {
        ApiResponse response = service.createOrUpdate(createRequest("Lti Partner"));

        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(esUtilService).addDocument(eq(Constants.CONTENT_PROVIDER_INDEX_NAME), eq(Constants.INDEX_TYPE),
                anyString(), anyMap(), eq(ES_PATH));
        verify(cacheService).putCache(anyString(), any());
    }

    // ---------- update ----------

    @Test
    void update_addsLtiConfigurationToLegacyPartnerAndKeepsOtherFields() {
        ObjectNode data = createRequest("Partner");
        data.put(Constants.PROTOCOL, "LTI");
        data.set(Constants.LTI_CONFIGURATION, ltiConfig());

        ApiResponse response = update(storedPartner(storedLegacyData()), data);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        JsonNode saved = captureSaved().getData();
        assertEquals("LTI", saved.path(Constants.PROTOCOL).asText());
        assertEquals(completedLtiConfig(), saved.path(Constants.LTI_CONFIGURATION));
        assertEquals("IGOT-OLD-ABCDE", saved.path(Constants.PARTNERCODE).asText());
        assertEquals("old description", saved.path("description").asText());
        assertEquals("https://example.com", saved.path("websiteUrl").asText());
    }

    @Test
    void update_validatesTheDataFragmentAgainstV2Schema() {
        ObjectNode data = createRequest("Partner");

        update(storedPartner(storedLegacyData()), data);

        verify(payloadValidation).validatePayload(V2_SCHEMA, data);
    }

    @Test
    void update_omittedProtocolAndConfigurationArePreserved() {
        ApiResponse response = update(storedPartner(storedLtiData()), createRequest("Renamed Partner"));

        assertEquals(HttpStatus.OK, response.getResponseCode());
        JsonNode saved = captureSaved().getData();
        assertEquals("Renamed Partner", saved.path(Constants.CONTENT_PARTNER_NAME).asText());
        assertEquals("LTI", saved.path(Constants.PROTOCOL).asText());
        assertEquals(completedLtiConfig(), saved.path(Constants.LTI_CONFIGURATION));
    }

    @Test
    void update_ltiConfigurationIsReplacedNotMerged() {
        ObjectNode replacement = ltiConfig();
        replacement.put("redirectLaunchUrl", "https://provider.example.com/new-launch");
        replacement.remove("deepLinkingUrl");
        ObjectNode data = createRequest("Partner");
        data.put(Constants.PROTOCOL, "LTI");
        data.set(Constants.LTI_CONFIGURATION, replacement);

        ApiResponse response = update(storedPartner(storedLtiData()), data);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        JsonNode saved = captureSaved().getData().path(Constants.LTI_CONFIGURATION);
        assertEquals("https://provider.example.com/new-launch", saved.path("redirectLaunchUrl").asText());
        assertFalse(saved.has("deepLinkingUrl"));
        assertEquals(LTI_CLIENT_ID, saved.path("clientId").asText());
    }

    @Test
    void update_legacyPartnerWithoutProtocolStillUpdates() {
        ApiResponse response = update(storedPartner(storedLegacyData()), createRequest("Partner"));

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertFalse(captureSaved().getData().has(Constants.PROTOCOL));
    }

    @Test
    void update_unknownIdReturnsBadRequest() {
        when(entityRepository.findById("id-123")).thenReturn(Optional.empty());

        ApiResponse response = service.createOrUpdate(updateRequest(createRequest("Partner")));

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.DATA_NOT_PRESENT, response.getParams().getErrMsg());
        verify(entityRepository, never()).save(any());
    }

    @Test
    void update_nameUsedByAnotherPartnerIsRejected() {
        ContentPartnerEntity other = new ContentPartnerEntity();
        other.setId("another-id");
        when(entityRepository.findByContentPartnerName("Partner")).thenReturn(Optional.of(other));

        ApiResponse response = update(storedPartner(storedLegacyData()), createRequest("Partner"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.CONTENT_PARTNER_NAME_ALREADY_PRESENT, response.getParams().getErrMsg());
        verify(entityRepository, never()).save(any());
    }

    @Test
    void update_licenceTypeCannotBeChanged() {
        ObjectNode stored = storedLtiData();
        stored.put(Constants.LICENCE_TYPE, Constants.LICENCE_TYPE_USER);
        ObjectNode data = createRequest("Partner");
        data.put(Constants.LICENCE_TYPE, Constants.LICENCE_TYPE_COURSE);

        ApiResponse response = update(storedPartner(stored), data);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.LICENCE_TYPE_CANNOT_BE_CHANGED, response.getParams().getErrMsg());
        verify(entityRepository, never()).save(any());
    }

    @Test
    void update_schemaFailureReturnsReturnsBadRequestAndDoesNotPersist() {
        doThrow(new CustomException("Validation Error", "bad ltiConfiguration", HttpStatus.BAD_REQUEST))
                .when(payloadValidation).validatePayload(anyString(), any());

        ApiResponse response = service.createOrUpdate(updateRequest(createRequest("Partner")));

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("bad ltiConfiguration", response.getParams().getErrMsg());
        verify(entityRepository, never()).save(any());
    }

    @Test
    void update_reindexesAndClearsCachesLikeV1() {
        update(storedPartner(storedLtiData()), createRequest("Partner"));

        verify(esUtilService).updateDocument(eq(Constants.CONTENT_PROVIDER_INDEX_NAME), eq(Constants.INDEX_TYPE),
                eq("id-123"), anyMap(), eq(ES_PATH));
        verify(cacheService).deleteCache("IGOT-OLD-ABCDE");
        verify(cacheService).deleteCache("id-123");
    }

    // ---------- server-managed LTI values ----------

    @Test
    void create_neverAppliesLtiConfiguration() {
        service.createOrUpdate(createRequest("Lti Partner"));

        assertFalse(captureSaved().getData().has(Constants.LTI_CONFIGURATION));
        verify(payloadValidation, times(1)).validatePayload(eq(V2_CREATE_SCHEMA), any(JsonNode.class));
        verify(cbServerProperties, never()).getLtiClientId();
    }

    @Test
    void update_requestCannotOverrideServerManagedValues() {
        ObjectNode lti = ltiConfig();
        lti.put("clientId", "attacker-client");
        lti.put("toolIssuer", "https://attacker.example.com");
        ObjectNode data = createRequest("Partner");
        data.put(Constants.PROTOCOL, "LTI");
        data.set(Constants.LTI_CONFIGURATION, lti);

        ApiResponse response = update(storedPartner(storedLegacyData()), data);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(completedLtiConfig(), captureSaved().getData().path(Constants.LTI_CONFIGURATION));
    }

    @Test
    void update_urlsOnlyConfigurationForStoredLtiPartnerIsCompletedFromConfiguration() {
        ObjectNode data = createRequest("Partner");
        data.put(Constants.PROTOCOL, "LTI");
        data.set(Constants.LTI_CONFIGURATION, ltiConfig());

        ApiResponse response = update(storedPartner(storedLtiData()), data);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(completedLtiConfig(), captureSaved().getData().path(Constants.LTI_CONFIGURATION));
    }

    @Test
    void update_ltiWithNoConfigurationAnywhereReturnsBadRequest() {
        ObjectNode data = createRequest("Partner");
        data.put(Constants.PROTOCOL, "LTI");

        ApiResponse response = update(storedPartner(storedLegacyData()), data);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.ERR_LTI_CONFIGURATION_REQUIRED, response.getParams().getErrMsg());
        verify(entityRepository, never()).save(any());
    }

    @Test
    void update_missingServerConfigurationFailsWithoutPersisting() {
        when(cbServerProperties.getLtiDeploymentId()).thenReturn(" ");
        ObjectNode data = createRequest("Partner");
        data.put(Constants.PROTOCOL, "LTI");
        data.set(Constants.LTI_CONFIGURATION, ltiConfig());

        ApiResponse response = update(storedPartner(storedLegacyData()), data);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("LTI server configuration is missing: lti.deployment.id", response.getParams().getErrMsg());
        verify(entityRepository, never()).save(any());
    }

    @Test
    void update_samlProtocolDoesNotInjectLtiConfiguration() {
        ObjectNode data = createRequest("Partner");
        data.put(Constants.PROTOCOL, "SAML");

        ApiResponse response = update(storedPartner(storedLegacyData()), data);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        JsonNode saved = captureSaved().getData();
        assertEquals("SAML", saved.path(Constants.PROTOCOL).asText());
        assertFalse(saved.has(Constants.LTI_CONFIGURATION));
    }

    @Test
    void update_switchingFromLtiToSamlKeepsStoredConfigurationAsToday() {
        ObjectNode data = createRequest("Partner");
        data.put(Constants.PROTOCOL, "SAML");

        update(storedPartner(storedLtiData()), data);

        JsonNode saved = captureSaved().getData();
        assertEquals("SAML", saved.path(Constants.PROTOCOL).asText());
        assertEquals(completedLtiConfig(), saved.path(Constants.LTI_CONFIGURATION));
    }

    @Test
    void update_nonLtiRequestDoesNotReadLtiConfiguration() {
        ObjectNode data = createRequest("Partner");
        data.put(Constants.PROTOCOL, "SAML");

        ApiResponse response = update(storedPartner(storedLegacyData()), data);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(cbServerProperties, never()).getLtiVersion();
        verify(cbServerProperties, never()).getLtiToolIssuer();
        verify(cbServerProperties, never()).getLtiClientId();
        verify(cbServerProperties, never()).getLtiDeploymentId();
    }

    // ---------- end to end with the real V2 schema ----------

    private ContentPartnerServiceImplV2 serviceWithRealSchemaValidation() {
        return new ContentPartnerServiceImplV2(new PayloadValidation(), entityRepository, mapper, esUtilService,
                cacheService, redisTemplate, cbServerProperties, secureRandom);
    }

    private ObjectNode realSchemaRequest(String name) {
        ObjectNode request = createRequest(name);
        request.put("description", "Some description");
        request.put("link", "https://example.com/courses");
        return request;
    }

    @Test
    void realSchema_createWithoutProtocolIsAccepted() {
        ApiResponse response = serviceWithRealSchemaValidation().createOrUpdate(realSchemaRequest("Plain Partner"));

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertFalse(captureSaved().getData().has(Constants.PROTOCOL));
    }

    @Test
    void realSchema_createRejectsProtocolAndLtiConfiguration() {
        ObjectNode request = realSchemaRequest("Lti Partner");
        request.put(Constants.PROTOCOL, "LTI");
        request.set(Constants.LTI_CONFIGURATION, ltiConfig());

        ApiResponse response = serviceWithRealSchemaValidation().createOrUpdate(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertTrue(response.getParams().getErrMsg().contains("$.protocol: is not defined in the schema"));
        verify(entityRepository, never()).save(any());
    }

    @Test
    void realSchema_ltiUpdateIsAcceptedAndCompletedFromConfiguration() {
        ObjectNode data = realSchemaRequest("Partner");
        data.put(Constants.PROTOCOL, "LTI");
        data.set(Constants.LTI_CONFIGURATION, ltiConfig());
        when(entityRepository.findById("id-123")).thenReturn(Optional.of(storedPartner(storedLegacyData())));

        ApiResponse response = serviceWithRealSchemaValidation().createOrUpdate(updateRequest(data));

        assertEquals(HttpStatus.OK, response.getResponseCode());
        JsonNode lti = captureSaved().getData().path(Constants.LTI_CONFIGURATION);
        assertEquals(completedLtiConfig(), lti);
        assertEquals(8, lti.size());
    }

    @Test
    void realSchema_updateWithoutProtocolIsRejectedBecauseProtocolIsRequired() {
        ObjectNode data = realSchemaRequest("Partner");
        data.set(Constants.LTI_CONFIGURATION, ltiConfig());

        ApiResponse response = serviceWithRealSchemaValidation().createOrUpdate(updateRequest(data));

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertTrue(response.getParams().getErrMsg().contains("$.protocol: is missing but it is required"));
        verify(entityRepository, never()).save(any());
    }

    @Test
    void realSchema_requestCannotSupplyServerManagedValues() {
        ObjectNode lti = ltiConfig();
        lti.put("clientId", "attacker-client");
        ObjectNode request = realSchemaRequest("Lti Partner");
        request.put(Constants.PROTOCOL, "LTI");
        request.set(Constants.LTI_CONFIGURATION, lti);

        ApiResponse response = serviceWithRealSchemaValidation().createOrUpdate(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        verify(entityRepository, never()).save(any());
    }

    @Test
    void realSchema_missingLtiEndpointIsRejectedWithoutPersisting() {
        ObjectNode lti = ltiConfig();
        lti.remove("redirectLaunchUrl");
        ObjectNode data = realSchemaRequest("Partner");
        data.put(Constants.PROTOCOL, "LTI");
        data.set(Constants.LTI_CONFIGURATION, lti);

        ApiResponse response = serviceWithRealSchemaValidation().createOrUpdate(updateRequest(data));

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertTrue(response.getParams().getErrMsg().contains("$.ltiConfiguration.redirectLaunchUrl: is missing"));
        verify(entityRepository, never()).save(any());
    }

    @Test
    void realSchema_samlUpdateContinuesThroughExistingFlow() {
        ObjectNode data = realSchemaRequest("Partner");
        data.put(Constants.PROTOCOL, "SAML");
        when(entityRepository.findById("id-123")).thenReturn(Optional.of(storedPartner(storedLegacyData())));

        ApiResponse response = serviceWithRealSchemaValidation().createOrUpdate(updateRequest(data));

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals("SAML", captureSaved().getData().path(Constants.PROTOCOL).asText());
    }

    @Test
    void update_serverManagedValuesAreLoadedFromConfiguration() {
        ObjectNode data = createRequest("Partner");
        data.put(Constants.PROTOCOL, "LTI");
        data.set(Constants.LTI_CONFIGURATION, ltiConfig());

        update(storedPartner(storedLegacyData()), data);

        JsonNode lti = captureSaved().getData().path(Constants.LTI_CONFIGURATION);
        assertEquals(LTI_VERSION, lti.path("ltiVersion").asText());
        assertEquals(LTI_ISSUER, lti.path("toolIssuer").asText());
        assertEquals(LTI_CLIENT_ID, lti.path("clientId").asText());
        assertEquals(LTI_DEPLOYMENT_ID, lti.path("deploymentId").asText());
    }

    @Test
    void update_nullServerConfigurationFailsWithoutPersisting() {
        when(cbServerProperties.getLtiToolIssuer()).thenReturn(null);
        ObjectNode data = createRequest("Partner");
        data.put(Constants.PROTOCOL, "LTI");
        data.set(Constants.LTI_CONFIGURATION, ltiConfig());

        ApiResponse response = update(storedPartner(storedLegacyData()), data);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("LTI server configuration is missing: lti.tool.issuer", response.getParams().getErrMsg());
        verify(entityRepository, never()).save(any());
    }

    @Test
    void update_nonLtiProtocolWithEmptyLtiConfigurationIsNotEnrichedOrRejected() {
        ObjectNode data = createRequest("Partner");
        data.put(Constants.PROTOCOL, "SAML");
        data.set(Constants.LTI_CONFIGURATION, mapper.createObjectNode());

        ApiResponse response = update(storedPartner(storedLegacyData()), data);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(0, captureSaved().getData().path(Constants.LTI_CONFIGURATION).size());
        verify(cbServerProperties, never()).getLtiClientId();
    }

    @Test
    void realSchema_samlUpdateWithEmptyLtiConfigurationIsAccepted() {
        ObjectNode data = realSchemaRequest("Partner");
        data.put(Constants.PROTOCOL, "SAML");
        data.set(Constants.LTI_CONFIGURATION, mapper.createObjectNode());
        when(entityRepository.findById("id-123")).thenReturn(Optional.of(storedPartner(storedLegacyData())));

        ApiResponse response = serviceWithRealSchemaValidation().createOrUpdate(updateRequest(data));

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertFalse(captureSaved().getData().path(Constants.LTI_CONFIGURATION).has("clientId"));
    }

    @Test
    void realSchema_ltiUpdateWithoutLtiConfigurationReturnsBadRequest() {
        ObjectNode data = realSchemaRequest("Partner");
        data.put(Constants.PROTOCOL, "LTI");

        ApiResponse response = serviceWithRealSchemaValidation().createOrUpdate(updateRequest(data));

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertTrue(response.getParams().getErrMsg().contains("ltiConfiguration: is missing but it is required"));
        verify(entityRepository, never()).save(any());
    }

    // ---------- search tags ----------

    private JsonNode savedSearchTags(ObjectNode request) {
        service.createOrUpdate(request);
        return captureSaved().getData().path(Constants.SEARCHTAGS);
    }

    @Test
    void searchTags_keepExistingTextTagsAndAddLowerCasedName() {
        ObjectNode request = createRequest("Lti Partner");
        request.putArray(Constants.SEARCHTAGS).add("existing").add("").add(5);

        JsonNode tags = savedSearchTags(request);

        assertEquals(2, tags.size());
        assertEquals("existing", tags.get(0).asText());
        assertEquals("lti partner", tags.get(1).asText());
    }

    @Test
    void searchTags_doNotDuplicateNameAlreadyPresent() {
        ObjectNode request = createRequest("Lti Partner");
        request.putArray(Constants.SEARCHTAGS).add("lti partner");

        JsonNode tags = savedSearchTags(request);

        assertEquals(1, tags.size());
    }

    @Test
    void searchTags_blankNameIsNotAdded() {
        ObjectNode request = createRequest("   ");

        assertEquals(0, savedSearchTags(request).size());
    }

    @Test
    void searchTags_nonArrayValueIsIgnored() {
        ObjectNode request = createRequest("Lti Partner");
        request.put(Constants.SEARCHTAGS, "not-an-array");

        JsonNode tags = savedSearchTags(request);

        assertEquals(1, tags.size());
        assertEquals("lti partner", tags.get(0).asText());
    }

    @Test
    void searchTags_missingNameAddsNothing() {
        ObjectNode request = mapper.createObjectNode();
        request.put("websiteUrl", "https://example.com");

        assertEquals(0, savedSearchTags(request).size());
    }

    @Test
    void update_searchCacheKeyIsSignedWithTheSameKeyAsV1() {
        update(storedPartner(storedLtiData()), createRequest("Partner"));

        ArgumentCaptor<String> cacheKey = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).delete(cacheKey.capture());
        // verifies with the V1 constant, so a different signing key would throw here
        com.auth0.jwt.JWT.require(com.auth0.jwt.algorithms.Algorithm.HMAC256(Constants.JWT_SECRET_KEY))
                .build().verify(cacheKey.getValue());
    }

    // ---------- create: ordering and unchanged outputs ----------

    @Test
    void create_invalidPayloadMakesNoRepositoryCalls() {
        ObjectNode request = mapper.createObjectNode();
        request.put(Constants.CONTENT_PARTNER_NAME, "Invalid Partner");

        ApiResponse response = serviceWithRealSchemaValidation().createOrUpdate(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        verify(entityRepository, never()).findByPartnerCode(anyString());
        verify(entityRepository, never()).findByContentPartnerName(anyString());
        verify(entityRepository, never()).save(any());
        verifyNoInteractions(esUtilService, cacheService);
    }

    @Test
    void realSchema_createRejectsApplicationId() {
        ObjectNode request = realSchemaRequest("Partner With Application Id");
        request.put(Constants.APPLICATION_ID, "APP-1");

        ApiResponse response = serviceWithRealSchemaValidation().createOrUpdate(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertTrue(response.getParams().getErrMsg().contains("$.applicationId: is not defined in the schema"));
        verify(entityRepository, never()).save(any());
    }

    @Test
    void create_duplicateNameDoesNotGeneratePartnerCode() {
        when(entityRepository.findByContentPartnerName("Existing Partner"))
                .thenReturn(Optional.of(storedPartner(storedLegacyData())));

        ApiResponse response = service.createOrUpdate(createRequest("Existing Partner"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.CONTENT_PARTNER_NAME_ALREADY_PRESENT, response.getParams().getErrMsg());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        verify(entityRepository, never()).findByPartnerCode(anyString());
        verify(entityRepository, never()).save(any());
    }

    @Test
    void create_persistsExactServerManagedValuesAndEntityColumns() {
        ObjectNode request = createRequest("Exact Partner");

        service.createOrUpdate(request);

        ContentPartnerEntity saved = captureSaved();
        JsonNode data = saved.getData();
        String timestamp = String.valueOf(saved.getCreatedOn());
        assertEquals(saved.getId(), data.path(Constants.ID).asText());
        assertTrue(data.path(Constants.PARTNERCODE).asText().startsWith(Constants.APPLICATION_ID_PREFIX + "EXACT-"));
        assertTrue(data.path(Constants.IS_ACTIVE).asBoolean());
        assertEquals(timestamp, data.path(Constants.CREATED_ON).asText());
        assertEquals(timestamp, data.path(Constants.UPDATED_ON).asText());
        assertEquals("", data.path(Constants.DOCUMENT_UPLOADED_DATE).asText());
        assertEquals(Constants.EXTERNAL, data.path(Constants.PROVIDER_TYPE).asText());
        assertEquals(0, data.path(Constants.PROVIDER_TIPS).size());
        assertEquals("exact partner", data.path(Constants.SEARCHTAGS).get(0).asText());
        assertFalse(data.has(Constants.APPLICATION_ID));
        assertEquals(saved.getCreatedOn(), saved.getUpdatedOn());
        assertTrue(saved.getIsActive());
        assertEquals(" ", saved.getCertificateTemplateUrl());
    }

    @Test
    void create_sideEffectsRunInOrderAndCacheEqualsResponse() {
        ApiResponse response = service.createOrUpdate(createRequest("Ordered Partner"));

        org.mockito.InOrder inOrder = inOrder(entityRepository, esUtilService, cacheService, redisTemplate);
        inOrder.verify(entityRepository).save(any(ContentPartnerEntity.class));
        inOrder.verify(esUtilService).addDocument(eq(Constants.CONTENT_PROVIDER_INDEX_NAME), eq(Constants.INDEX_TYPE),
                anyString(), anyMap(), eq(ES_PATH));
        inOrder.verify(cacheService).putCache(anyString(), any());
        inOrder.verify(redisTemplate).delete(anyString());
        String savedId = captureSaved().getId();
        ArgumentCaptor<Object> cached = ArgumentCaptor.forClass(Object.class);
        verify(cacheService).putCache(eq(savedId), cached.capture());
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertSame(cached.getValue(), response.getResult());
    }

    // ---------- update: duplicate-name query is skipped only when the name is unchanged ----------

    private ObjectNode storedDataWithName(String name) {
        ObjectNode data = storedLegacyData();
        data.put(Constants.CONTENT_PARTNER_NAME, name);
        return data;
    }

    private ContentPartnerEntity partnerOwnedBy(String id) {
        ContentPartnerEntity other = new ContentPartnerEntity();
        other.setId(id);
        return other;
    }

    @Test
    void update_unchangedNameSkipsDuplicateNameQuery() {
        ObjectNode data = createRequest("Partner");
        data.put("description", "updated description");

        ApiResponse response = update(storedPartner(storedDataWithName("Partner")), data);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(entityRepository, never()).findByContentPartnerName(anyString());
        verify(entityRepository).save(any(ContentPartnerEntity.class));
    }

    @Test
    void update_changedNameRunsDuplicateNameQuery() {
        ApiResponse response = update(storedPartner(storedDataWithName("Old Name")), createRequest("New Name"));

        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(entityRepository).findByContentPartnerName("New Name");
        verify(entityRepository).save(any(ContentPartnerEntity.class));
    }

    @Test
    void update_changedNameOwnedByAnotherPartnerIsRejected() {
        when(entityRepository.findByContentPartnerName("Taken Name")).thenReturn(Optional.of(partnerOwnedBy("another-id")));

        ApiResponse response = update(storedPartner(storedDataWithName("Old Name")), createRequest("Taken Name"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.CONTENT_PARTNER_NAME_ALREADY_PRESENT, response.getParams().getErrMsg());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        verify(entityRepository).findByContentPartnerName("Taken Name");
        verify(entityRepository, never()).save(any());
    }

    @Test
    void update_changedNameToUnusedNameSucceeds() {
        when(entityRepository.findByContentPartnerName("Unused Name")).thenReturn(Optional.empty());

        ApiResponse response = update(storedPartner(storedDataWithName("Old Name")), createRequest("Unused Name"));

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals("Unused Name", captureSaved().getData().path(Constants.CONTENT_PARTNER_NAME).asText());
    }

    @Test
    void update_nameDifferingOnlyByCaseIsTreatedAsChanged() {
        when(entityRepository.findByContentPartnerName("partner")).thenReturn(Optional.of(partnerOwnedBy("another-id")));

        ApiResponse response = update(storedPartner(storedDataWithName("Partner")), createRequest("partner"));

        verify(entityRepository).findByContentPartnerName("partner");
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.CONTENT_PARTNER_NAME_ALREADY_PRESENT, response.getParams().getErrMsg());
    }

    @Test
    void update_nameDifferingByTrailingSpaceIsTreatedAsChanged() {
        update(storedPartner(storedDataWithName("Partner")), createRequest("Partner "));

        verify(entityRepository).findByContentPartnerName("Partner ");
    }

    @Test
    void update_missingStoredNameStillRunsDuplicateNameQuery() {
        when(entityRepository.findByContentPartnerName("Partner")).thenReturn(Optional.of(partnerOwnedBy("another-id")));

        ApiResponse response = update(storedPartner(storedLegacyData()), createRequest("Partner"));

        verify(entityRepository).findByContentPartnerName("Partner");
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.CONTENT_PARTNER_NAME_ALREADY_PRESENT, response.getParams().getErrMsg());
    }

    @Test
    void update_nonTextStoredNameStillRunsDuplicateNameQuery() {
        ObjectNode stored = storedLegacyData();
        stored.put(Constants.CONTENT_PARTNER_NAME, 123);

        update(storedPartner(stored), createRequest("123"));

        verify(entityRepository).findByContentPartnerName("123");
    }

    @Test
    void update_unchangedNameStillAppliesLicenceCheck() {
        ObjectNode stored = storedDataWithName("Partner");
        stored.put(Constants.LICENCE_TYPE, Constants.LICENCE_TYPE_USER);
        ObjectNode data = createRequest("Partner");
        data.put(Constants.LICENCE_TYPE, Constants.LICENCE_TYPE_COURSE);

        ApiResponse response = update(storedPartner(stored), data);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.LICENCE_TYPE_CANNOT_BE_CHANGED, response.getParams().getErrMsg());
        verify(entityRepository, never()).findByContentPartnerName(anyString());
    }

    @Test
    void update_unchangedNameStillAppliesLtiConfiguration() {
        ObjectNode data = createRequest("Partner");
        data.put(Constants.PROTOCOL, "LTI");
        data.set(Constants.LTI_CONFIGURATION, ltiConfig());

        ApiResponse response = update(storedPartner(storedDataWithName("Partner")), data);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(completedLtiConfig(), captureSaved().getData().path(Constants.LTI_CONFIGURATION));
        verify(entityRepository, never()).findByContentPartnerName(anyString());
    }
}
