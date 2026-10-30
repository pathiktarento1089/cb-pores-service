package com.igot.cb.contentpartner.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.igot.cb.contentpartner.entity.ContentPartnerEntity;
import com.igot.cb.contentpartner.repository.ContentPartnerRepository;
import com.igot.cb.contentpartner.service.ContentPartnerServiceV2;
import com.igot.cb.playlist.util.ProjectUtil;
import com.igot.cb.pores.cache.CacheService;
import com.igot.cb.pores.elasticsearch.dto.SearchCriteria;
import com.igot.cb.pores.elasticsearch.dto.SearchResult;
import com.igot.cb.pores.elasticsearch.service.EsUtilService;
import com.igot.cb.pores.exceptions.CustomException;
import com.igot.cb.pores.util.ApiResponse;
import com.igot.cb.pores.util.CbServerProperties;
import com.igot.cb.pores.util.Constants;
import com.igot.cb.pores.util.PayloadValidation;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.*;

@Service
@Slf4j
public class ContentPartnerServiceImplV2 implements ContentPartnerServiceV2 {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final PayloadValidation payloadValidation;
    private final ContentPartnerRepository entityRepository;
    private final ObjectMapper objectMapper;
    private final EsUtilService esUtilService;
    private final CacheService cacheService;
    private final RedisTemplate<String, SearchResult> redisTemplate;
    private final CbServerProperties cbServerProperties;
    private final SecureRandom secureRandom;

    public ContentPartnerServiceImplV2(
            PayloadValidation payloadValidation,
            ContentPartnerRepository entityRepository,
            ObjectMapper objectMapper,
            EsUtilService esUtilService,
            CacheService cacheService,
            RedisTemplate<String, SearchResult> redisTemplate,
            CbServerProperties cbServerProperties,
            SecureRandom secureRandom) {
        this.payloadValidation = payloadValidation;
        this.entityRepository = entityRepository;
        this.objectMapper = objectMapper;
        this.esUtilService = esUtilService;
        this.cacheService = cacheService;
        this.redisTemplate = redisTemplate;
        this.cbServerProperties = cbServerProperties;
        this.secureRandom = secureRandom;
    }


    @Override
    public ApiResponse createOrUpdate(JsonNode partnerDetails) {
        log.info("ContentPartnerServiceImpl::createOrUpdate:inside");
        ApiResponse response = new ApiResponse();
        try {
            if (partnerDetails.get(Constants.ID) == null) {
                response = createContentPartner(partnerDetails);
            } else {
                response = updateContentPartner(partnerDetails);
            }
            return response;
        } catch (CustomException e) {
            ProjectUtil.errorResponse(response, e.getMessage(),
                    e.getHttpStatusCode() != null ? e.getHttpStatusCode() : HttpStatus.BAD_REQUEST);
            return response;
        } catch (Exception e) {
            ProjectUtil.errorResponse(response, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
            return response;
        }
    }

    public ApiResponse createContentPartner(JsonNode partnerDetails) {
        log.info("ContentPartnerServiceImpl::createContentPartner");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_PARTNER_CREATE);
        payloadValidation.validatePayload(cbServerProperties.getContentPartnerV2CreateValidationSchemaPath(), partnerDetails);
        String partnerName = partnerDetails.path(Constants.CONTENT_PARTNER_NAME).asText();
        if (entityRepository.findByContentPartnerName(partnerName).isPresent()) {
            ProjectUtil.errorResponse(response, Constants.CONTENT_PARTNER_NAME_ALREADY_PRESENT, HttpStatus.BAD_REQUEST);
            return response;
        }
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        String id = UUID.randomUUID().toString();
        ObjectNode data = buildNewPartnerData(partnerDetails, id, generatePartnerCode(partnerName), currentTime);
        ContentPartnerEntity contentPartnerEntity = new ContentPartnerEntity();
        contentPartnerEntity.setId(id);
        contentPartnerEntity.setCreatedOn(currentTime);
        contentPartnerEntity.setUpdatedOn(currentTime);
        contentPartnerEntity.setIsActive(Constants.ACTIVE_STATUS);
        contentPartnerEntity.setCertificateTemplateUrl(partnerDetails.path(Constants.CERTIFICATE_TEMPLATE_URL).asText(" "));
        contentPartnerEntity.setData(data);
        ContentPartnerEntity saveJsonEntity = entityRepository.save(contentPartnerEntity);
        response.setResult(indexAndCacheNewPartner(saveJsonEntity));
        response.setResponseCode(HttpStatus.OK);
        return response;
    }

    /**
     * Adds the server-managed values to the validated create request, in place, and returns it.
     * The same node is persisted as the partner's JSON data.
     */
    private ObjectNode buildNewPartnerData(JsonNode partnerDetails, String id, String partnerCode, Timestamp currentTime) {
        ObjectNode node = (ObjectNode) partnerDetails;
        String currentTimeText = String.valueOf(currentTime);
        node.put(Constants.PARTNERCODE, partnerCode);
        node.put(Constants.ID, id);
        node.put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS);
        node.put(Constants.TOTAL_COURSES_COUNT, 0);
        node.put(Constants.DRAFT_COURSES_COUNT, 0);
        node.put(Constants.LIVE_COURSES_COUNT, 0);
        node.put(Constants.OVER_ALL_LIMIT, 0);
        node.put(Constants.USER_WISE_LIMIT_ENABLED, Constants.IN_ACTIVE_STATUS);
        node.put(Constants.CONCURRENT_LIMIT_ENABLED, Constants.IN_ACTIVE_STATUS);
        node.put(Constants.LICENCE_CONSUMED_COUNT, 0);
        node.put(Constants.ADD_KARMA_POINT_ENABLED, Constants.IN_ACTIVE_STATUS);
        node.put(Constants.IS_AUTHENTICATE, Constants.IN_ACTIVE_STATUS);
        if (!node.has(Constants.PROVIDER_TIPS)) {
            node.putArray(Constants.PROVIDER_TIPS);
        }
        node.put(Constants.CREATED_ON, currentTimeText);
        node.put(Constants.UPDATED_ON, currentTimeText);
        node.put(Constants.DOCUMENT_UPLOADED_DATE, partnerDetails.path(Constants.DOCUMENT_UPLOADED_DATE).asText(""));
        node.put(Constants.PROVIDER_TYPE, Constants.EXTERNAL);
        node.put(Constants.IS_TRAINING_INSTITUTE, Constants.ACTIVE_STATUS_FALSE);
        addSearchTags(node);
        return node;
    }

    /**
     * Indexes the saved partner in Elasticsearch, caches it and clears the search cache, in that order.
     *
     * @return the entity-shaped map used both for the cache value and the API response
     */
    private Map<String, Object> indexAndCacheNewPartner(ContentPartnerEntity saveJsonEntity) {
        Map<String, Object> map = objectMapper.convertValue(saveJsonEntity.getData(), MAP_TYPE);
        esUtilService.addDocument(Constants.CONTENT_PROVIDER_INDEX_NAME, Constants.INDEX_TYPE, saveJsonEntity.getId(), map, cbServerProperties.getElasticContentJsonPath());
        Map<String, Object> result = objectMapper.convertValue(saveJsonEntity, MAP_TYPE);
        cacheService.putCache(saveJsonEntity.getId(), result);
        // Clear search-related Redis cache
        clearSearchCache();
        log.info(Constants.CONTENT_PARTNER_CREATED);
        return result;
    }

    private ApiResponse updateContentPartner(JsonNode partnerDetails) {
        log.info("ContentPartnerServiceImpl::updateContentPartner");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_PARTNER_UPDATE);
        JsonNode data = partnerDetails.get(Constants.DATA);
        payloadValidation.validatePayload(cbServerProperties.getContentPartnerV2ValidationSchemaPath(), data);
        String partnerName = data.get(Constants.CONTENT_PARTNER_NAME).asText();
        String existingId = partnerDetails.get(Constants.ID).asText();
        Optional<ContentPartnerEntity> content = entityRepository.findById(existingId);
        if (content.isEmpty()) {
            ProjectUtil.errorResponse(response, Constants.DATA_NOT_PRESENT, HttpStatus.BAD_REQUEST);
            return response;
        }
        ContentPartnerEntity jsonEntity = content.get();
        String ruleViolationMessage = checkUpdateRulesAndApplyLtiConfig(partnerName, existingId, data, jsonEntity);
        if (ruleViolationMessage != null) {
            ProjectUtil.errorResponse(response, ruleViolationMessage, HttpStatus.BAD_REQUEST);
            return response;
        }
        return saveUpdatedPartner(response, partnerDetails, jsonEntity, existingId);
    }

    /**
     * Business checks run before an update is applied, in this order: name uniqueness, licence type
     * immutability, LTI configuration, partner code immutability. For an LTI request this also completes the request's
     * {@code ltiConfiguration} with the server-managed values (it modifies {@code data} in place).
     *
     * @return an error message for a 400 response, or null when the update may proceed
     */
    private String checkUpdateRulesAndApplyLtiConfig(String partnerName, String existingId, JsonNode data, ContentPartnerEntity jsonEntity) {
        JsonNode existingData = jsonEntity.getData();
        if (isNameUsedByAnotherPartner(partnerName, existingId, existingData)) {
            return Constants.CONTENT_PARTNER_NAME_ALREADY_PRESENT;
        }
        if (isLicenceTypeChanged(data, existingData)) {
            return Constants.LICENCE_TYPE_CANNOT_BE_CHANGED;
        }
        if (isLtiProtocol(data)) {
            String ltiViolation = applyLtiConfiguration(data, existingData);
            if (ltiViolation != null) {
                return ltiViolation;
            }
        } else {
            log.info("Protocol is {}, skipping LTI configuration enrichment",
                    data.path(Constants.PROTOCOL).asText("not set"));
        }
        return isPartnerCodeChanged(data, existingData) ? Constants.CONTENT_PARTNER_CODE_CANNOT_BE_CHANGED : null;
    }

    /**
     * A request that omits partnerCode keeps the stored one (missing fields are copied from the stored data). A
     * request that carries one must equal the stored value exactly (case-sensitive). A stored value that is
     * missing or blank (legacy data) imposes no restriction.
     */
    private boolean isPartnerCodeChanged(JsonNode data, JsonNode existingData) {
        String storedPartnerCode = existingData.path(Constants.PARTNERCODE).asText(null);
        return StringUtils.isNotBlank(storedPartnerCode) && data.hasNonNull(Constants.PARTNERCODE)
                && !storedPartnerCode.equals(data.get(Constants.PARTNERCODE).asText());
    }

    /**
     * The duplicate-name query is skipped only when the stored name is a text value exactly equal to the incoming
     * name (same comparison as the query: case-sensitive, no trimming). A missing or non-text stored name falls
     * back to the query, so the duplicate check is never bypassed by accident.
     */
    private boolean isNameUsedByAnotherPartner(String partnerName, String existingId, JsonNode existingData) {
        JsonNode storedName = existingData == null ? null : existingData.get(Constants.CONTENT_PARTNER_NAME);
        if (storedName != null && storedName.isTextual() && partnerName.equals(storedName.asText())) {
            return false;
        }
        return entityRepository.findByContentPartnerName(partnerName)
                .filter(entity -> !entity.getId().equals(existingId)).isPresent();
    }

    private boolean isLicenceTypeChanged(JsonNode data, JsonNode existingData) {
        String existingLicenceType = existingData.path(Constants.LICENCE_TYPE).asText(null);
        return StringUtils.isNotBlank(existingLicenceType) && data.hasNonNull(Constants.LICENCE_TYPE)
                && !existingLicenceType.equals(data.get(Constants.LICENCE_TYPE).asText());
    }

    private ApiResponse saveUpdatedPartner(ApiResponse response, JsonNode partnerDetails,
                                           ContentPartnerEntity jsonEntity, String existingId) {
        String partnerCode = jsonEntity.getData().path(Constants.PARTNERCODE).asText();
        createContentPartnerEntity(jsonEntity, partnerDetails);
        ContentPartnerEntity updateJsonEntity = entityRepository.save(jsonEntity);
        Map<String, Object> jsonMap = objectMapper.convertValue(updateJsonEntity.getData(), MAP_TYPE);
        esUtilService.updateDocument(Constants.CONTENT_PROVIDER_INDEX_NAME, Constants.INDEX_TYPE, existingId, jsonMap, cbServerProperties.getElasticContentJsonPath());
        Map<String, Object> result = objectMapper.convertValue(updateJsonEntity, MAP_TYPE);
        clearUpdatedPartnerCaches(jsonMap, partnerCode, updateJsonEntity.getId());
        log.info(Constants.UPDATED_CONTENT_PARTNER);
        response.setResult(result);
        response.setResponseCode(HttpStatus.OK);
        return response;
    }

    private void clearUpdatedPartnerCaches(Map<String, Object> jsonMap, String previousPartnerCode, String id) {
        if (StringUtils.isBlank((String) jsonMap.get(Constants.PARTNERCODE))) {
            return;
        }
        log.info(Constants.CONTENT_PARTNER_UPDATE_CACHE_DELETE, jsonMap.get(Constants.PARTNERCODE));
        if (StringUtils.isNotBlank(previousPartnerCode)) {
            cacheService.deleteCache(previousPartnerCode);
        }
        cacheService.deleteCache(id);
        // Clear search-related Redis cache
        clearSearchCache();
    }

    /**
     * Called only when the request's protocol is LTI. Completes the request's ltiConfiguration with the server-managed
     * values (version, issuer, client id, deployment id) read from application configuration. Request-supplied
     * values for those four fields are always replaced.
     *
     * @param incoming the incoming partner data (modified in place when LTI)
     * @param existing the stored partner data, or null
     * @return an error message when an LTI request has no configuration at all, otherwise null
     * @throws IllegalStateException when the server-managed LTI values are not configured
     */
    private String applyLtiConfiguration(JsonNode incoming, JsonNode existing) {
        JsonNode requested = incoming.get(Constants.LTI_CONFIGURATION);
        if (requested == null || requested.isNull()) {
            return existing != null && existing.hasNonNull(Constants.LTI_CONFIGURATION)
                    ? null : Constants.ERR_LTI_CONFIGURATION_REQUIRED;
        }
        ObjectNode ltiConfiguration = (ObjectNode) requested;
        ltiConfiguration.set(Constants.LTI_VERSION, TextNode.valueOf(requiredLtiSetting(cbServerProperties.getLtiVersion(), "lti.version")));
        ltiConfiguration.set(Constants.LTI_TOOL_ISSUER, TextNode.valueOf(requiredLtiSetting(cbServerProperties.getLtiToolIssuer(), "lti.tool.issuer")));
        ltiConfiguration.set(Constants.LTI_CLIENT_ID, TextNode.valueOf(requiredLtiSetting(cbServerProperties.getLtiClientId(), "lti.client.id")));
        ltiConfiguration.set(Constants.LTI_DEPLOYMENT_ID, TextNode.valueOf(requiredLtiSetting(cbServerProperties.getLtiDeploymentId(), "lti.deployment.id")));
        return null;
    }

    private String requiredLtiSetting(String value, String propertyName) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException("LTI server configuration is missing: " + propertyName);
        }
        return value;
    }

    private boolean isLtiProtocol(JsonNode partnerData) {
        return Constants.PROTOCOL_LTI.equals(partnerData.path(Constants.PROTOCOL).asText());
    }

    private String generatePartnerCode(String partnerName) {
        String firstWord = partnerName.trim().split("\\s+")[0].toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        String characters = cbServerProperties.getPartnerCodeChar();
        int maxAttempts = cbServerProperties.getPartnerCodeMaxAttempts();
        int codeLength = cbServerProperties.getPartnerCodeLength();
        if (StringUtils.isBlank(characters) || maxAttempts < 1 || codeLength < 1) {
            throw new IllegalStateException("Partner code generation is not configured");
        }
        if (firstWord.isEmpty()) {
            throw new IllegalStateException("Unable to derive a partner code prefix from the partner name");
        }
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            StringBuilder randomCode = new StringBuilder(codeLength);
            for (int i = 0; i < codeLength; i++) {
                randomCode.append(characters.charAt(secureRandom.nextInt(characters.length())));
            }
            String partnerCode = Constants.APPLICATION_ID_PREFIX + firstWord + "-" + randomCode;
            if (entityRepository.findByPartnerCode(partnerCode).isEmpty()) {
                return partnerCode;
            }
        }
        throw new IllegalStateException("Unable to generate a unique partner code");
    }

    private void addSearchTags(ObjectNode formattedData) {
        List<String> searchTags = new ArrayList<>();

        // Preserve existing searchTags if present
        if (formattedData.has(Constants.SEARCHTAGS) && formattedData.get(Constants.SEARCHTAGS).isArray()) {
            ArrayNode existingSearchTags = (ArrayNode) formattedData.get(Constants.SEARCHTAGS);
            existingSearchTags.forEach(tag -> {
                if (tag.isTextual() && !tag.asText().isEmpty()) {
                    searchTags.add(tag.asText());
                }
            });
        }

        // Add contentPartnerName to searchTags
        if (formattedData.has(Constants.CONTENT_PARTNER_NAME)) {
            String partnerName = formattedData.get(Constants.CONTENT_PARTNER_NAME).textValue();
            if (StringUtils.isNotBlank(partnerName) && !searchTags.contains(partnerName.toLowerCase())) {
                searchTags.add(partnerName.toLowerCase());
            }
        }

        ArrayNode searchTagsArray = objectMapper.valueToTree(searchTags);
        formattedData.set(Constants.SEARCHTAGS, searchTagsArray);
    }

    /**
     * Clear search-related Redis cache entry for content partner search.
     * Clears the specific search pattern used in the UI.
     */
    private void clearSearchCache() {
        try {
            String cacheKey = generateRedisJwtTokenKey(buildCommonSearchPatterns());
            if (StringUtils.isNotBlank(cacheKey)) {
                Boolean deleted = redisTemplate.delete(cacheKey);
                if (Boolean.TRUE.equals(deleted)) {
                    log.info("Cleared search cache entry from Redis after partner status change. Key: {}", cacheKey);
                } else {
                    log.debug("No search cache entry found to delete for key: {}", cacheKey);
                }
            }
        } catch (Exception e) {
            log.error("Error clearing search cache from Redis: {}", e.getMessage(), e);
        }
    }

    private String generateRedisJwtTokenKey(Object requestPayload) {
        if (requestPayload != null) {
            try {
                String reqJsonString = objectMapper.writeValueAsString(requestPayload);
                return JWT.create()
                        .withClaim(Constants.REQUEST_PAYLOAD, reqJsonString)
                        .sign(Algorithm.HMAC256(cbServerProperties.getContentPartnerSearchCacheKey()));
            } catch (JsonProcessingException e) {
                log.error("Error occurred while converting json object to json string", e);
            }
        }
        return "";
    }

    /**
     * Build common search patterns that are typically used and need cache invalidation.
     * IMPORTANT: Must include all fields (including nulls) to match exact JWT token.
     */
    private SearchCriteria buildCommonSearchPatterns() {
        SearchCriteria searchCriteria = new SearchCriteria();
        Map<String, Object> filterMap1 = new HashMap<>();
        filterMap1.put(Constants.PROVIDER_TYPE, Collections.singletonList(Constants.EXTERNAL));
        searchCriteria.setFilterCriteriaMap((HashMap<String, Object>) filterMap1);
        searchCriteria.setRequestedFields(null);
        searchCriteria.setPageNumber(0);
        searchCriteria.setPageSize(20);
        searchCriteria.setOrderBy(Constants.UPDATED_ON);
        searchCriteria.setOrderDirection(Constants.DESC);
        searchCriteria.setSearchString(null);
        searchCriteria.setFacets(Collections.singletonList(Constants.CONTENT_PARTNER_NAME));
        searchCriteria.setQuery(null);
        searchCriteria.setStartsWith(null);
        searchCriteria.setStartsWithField(null);
        return searchCriteria;
    }

    private void createContentPartnerEntity(ContentPartnerEntity jsonEntity, JsonNode partnerDetails) {
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        jsonEntity.setUpdatedOn(currentTime);
        jsonEntity.setIsActive(Constants.ACTIVE_STATUS);
        jsonEntity.setTrasformContentJson(partnerDetails.get(Constants.TRANSFORM_CONTENT_JSON));
        jsonEntity.setTransformProgressJson(partnerDetails.get(Constants.TRANSFORM_PROGRESS_JSON));
        jsonEntity.setCertificateTemplateUrl(partnerDetails.path(Constants.CERTIFICATE_TEMPLATE_URL).asText(" "));
        jsonEntity.setServiceRegistryDetails(partnerDetails.get(Constants.SERVICE_REGISTRY_DETAILS));
        jsonEntity.setContentFileValidation(partnerDetails.get(Constants.CONTENT_FILE_VALIDATION));
        jsonEntity.setTransformContentViaApi(partnerDetails.get(Constants.TRANSFORM_CONTENT_VIA_API));
        jsonEntity.setTransformProgressViaApi(partnerDetails.get(Constants.TRANSFORM_PROGRESS_VIA_API));
        // Only the nested data object is persisted as the JSON data; the outer request is not used afterwards.
        ObjectNode dataNode = (ObjectNode) ((ObjectNode) partnerDetails).remove(Constants.DATA);
        dataNode.set(Constants.CREATED_ON, TextNode.valueOf(String.valueOf(jsonEntity.getCreatedOn())));
        dataNode.set(Constants.UPDATED_ON, TextNode.valueOf(String.valueOf(currentTime)));
        dataNode.set(Constants.IS_ACTIVE, BooleanNode.valueOf(Constants.ACTIVE_STATUS));
        updateOtherDetailsWithDefaultValue(dataNode, jsonEntity);
        addSearchTags(dataNode);
        jsonEntity.setData(dataNode);
    }

    private void updateOtherDetailsWithDefaultValue(ObjectNode dataNode, ContentPartnerEntity content) {
        ObjectNode existingData = (ObjectNode) content.getData();

        existingData.fieldNames().forEachRemaining(field -> {
            if (dataNode.path(field).isMissingNode()) {
                dataNode.set(field, existingData.get(field));
            }
        });
    }
}
