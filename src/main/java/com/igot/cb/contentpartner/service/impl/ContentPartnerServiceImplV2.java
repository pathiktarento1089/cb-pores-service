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
import org.springframework.util.ObjectUtils;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.*;

@Service
@Slf4j
public class ContentPartnerServiceImplV2 implements ContentPartnerServiceV2 {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String LTI_VERSION = "ltiVersion";
    private static final String LTI_TOOL_ISSUER = "toolIssuer";
    private static final String LTI_CLIENT_ID = "clientId";
    private static final String LTI_DEPLOYMENT_ID = "deploymentId";

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
            response.getParams().setErrMsg(e.getMessage());
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(e.getHttpStatusCode() != null ? e.getHttpStatusCode() : HttpStatus.BAD_REQUEST);
            return response;
        } catch (Exception e) {
            response.getParams().setErrMsg(e.getMessage());
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return response;
        }
    }

    public ApiResponse createContentPartner(JsonNode partnerDetails) {
        log.info("ContentPartnerServiceImpl::createContentPartner");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_PARTNER_CREATE);
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        log.info("Payload for validation: {}", partnerDetails);
        String partnerName = partnerDetails.path(Constants.CONTENT_PARTNER_NAME).asText();
        String id;
        String partnerCode;
        if (partnerDetails.hasNonNull(Constants.ID)) {
            id = partnerDetails.path(Constants.ID).asText();
            partnerCode = partnerDetails.path(Constants.APPLICATION_ID).asText("");
            payloadValidation.validatePayload(Constants.CONTENT_PARTNER_FILE_JSON, partnerDetails);
        } else {
            id = UUID.randomUUID().toString();
            partnerCode = generatePartnerCode(partnerName);
            payloadValidation.validatePayload(cbServerProperties.getContentPartnerV2ValidationSchemaPath(), partnerDetails);
        }
        Optional<ContentPartnerEntity> existingByName = entityRepository.findByContentPartnerName(partnerName);
        if (existingByName.isPresent()) {
            response.getParams().setErrMsg(Constants.CONTENT_PARTNER_NAME_ALREADY_PRESENT);
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }
        ObjectNode node = (ObjectNode) partnerDetails;
        node.put(Constants.PARTNERCODE, partnerCode);
        node.put(Constants.ID, id);
        node.put(Constants.IS_ACTIVE, Constants.ACTIVE_STATUS);
        node.put(Constants.TOTAL_COURSES_COUNT, 0);
        node.put(Constants.DRAFT_COURSES_COUNT, 0);
        node.put(Constants.LIVE_COURSES_COUNT, 0);
        node.put(Constants.OVER_ALL_LIMIT, 0);
        node.put(Constants.USER_WISE_LIMIT_ENABLED, Constants.IN_ACTIVE_STATUS);
        node.put(Constants.CONCURRENT_LIMIT_ENABLED, Constants.IN_ACTIVE_STATUS);
        // liscenceType is intentionally left unset here - it is configured later via update and,
        // once set, must not be changed (see updateContentPartner).
        node.put(Constants.LICENCE_CONSUMED_COUNT, 0);
        node.put(Constants.ADD_KARMA_POINT_ENABLED, Constants.IN_ACTIVE_STATUS);
        node.put(Constants.IS_AUTHENTICATE, Constants.IN_ACTIVE_STATUS);
        if (!node.has(Constants.PROVIDER_TIPS)) {
            node.putArray(Constants.PROVIDER_TIPS);
        }
        node.put(Constants.CREATED_ON, String.valueOf(currentTime));
        node.put(Constants.UPDATED_ON, String.valueOf(currentTime));
        node.put(Constants.DOCUMENT_UPLOADED_DATE, partnerDetails.path(Constants.DOCUMENT_UPLOADED_DATE).asText(""));
        node.put(Constants.PROVIDER_TYPE, Constants.EXTERNAL);
        node.put(Constants.IS_TRAINING_INSTITUTE, Constants.ACTIVE_STATUS_FALSE);
        node.remove(Constants.APPLICATION_ID);
        ContentPartnerEntity contentPartnerEntity = new ContentPartnerEntity();
        contentPartnerEntity.setId(id);
        contentPartnerEntity.setCreatedOn(currentTime);
        contentPartnerEntity.setUpdatedOn(currentTime);
        contentPartnerEntity.setIsActive(Constants.ACTIVE_STATUS);
        contentPartnerEntity.setCertificateTemplateUrl(partnerDetails.path(Constants.CERTIFICATE_TEMPLATE_URL).asText(" "));
        addSearchTags(partnerDetails);
        contentPartnerEntity.setData(partnerDetails);
        ContentPartnerEntity saveJsonEntity = entityRepository.save(contentPartnerEntity);
        Map<String, Object> map = objectMapper.convertValue(saveJsonEntity.getData(), MAP_TYPE);
        esUtilService.addDocument(Constants.CONTENT_PROVIDER_INDEX_NAME, Constants.INDEX_TYPE, id, map, cbServerProperties.getElasticContentJsonPath());
        Map<String, Object> result = objectMapper.convertValue(saveJsonEntity, MAP_TYPE);
        cacheService.putCache(saveJsonEntity.getId(), result);
        // Clear search-related Redis cache
        clearSearchCache();
        log.info(Constants.CONTENT_PARTNER_CREATED);
        response.setResult(result);
        response.setResponseCode(HttpStatus.OK);
        return response;
    }

    private ApiResponse updateContentPartner(JsonNode partnerDetails) {
        log.info("ContentPartnerServiceImpl::createContentPartner");
        ApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_PARTNER_UPDATE);
        JsonNode data = partnerDetails.get(Constants.DATA);
        payloadValidation.validatePayload(cbServerProperties.getContentPartnerV2ValidationSchemaPath(), data);
        String partnerName = partnerDetails.path(Constants.DATA).get(Constants.CONTENT_PARTNER_NAME).asText();
        String existingId = partnerDetails.get("id").asText();
        Optional<ContentPartnerEntity> content = entityRepository.findById(existingId);
        if (content.isEmpty()) {
            ProjectUtil.errorResponse(response, Constants.DATA_NOT_PRESENT, HttpStatus.BAD_REQUEST);
            return response;
        }
        ContentPartnerEntity jsonEntity = content.get();
        String updateError = findUpdateError(partnerName, existingId, data, jsonEntity);
        if (updateError != null) {
            ProjectUtil.errorResponse(response, updateError, HttpStatus.BAD_REQUEST);
            return response;
        }
        return saveUpdatedPartner(response, partnerDetails, jsonEntity, existingId);
    }

    /**
     * Business checks run before an update is applied, in this order: name uniqueness, licence type
     * immutability, LTI configuration.
     *
     * @return an error message for a 400 response, or null when the update may proceed
     */
    private String findUpdateError(String partnerName, String existingId, JsonNode data, ContentPartnerEntity jsonEntity) {
        if (entityRepository.findByContentPartnerName(partnerName).filter(entity -> !entity.getId().equals(existingId)).isPresent()) {
            return Constants.CONTENT_PARTNER_NAME_ALREADY_PRESENT;
        }
        if (isLicenceTypeChanged(data, jsonEntity)) {
            return Constants.LICENCE_TYPE_CANNOT_BE_CHANGED;
        }
        if (isLtiProtocol(data)) {
            return applyLtiConfiguration(data, jsonEntity.getData());
        }
        log.info("Protocol is {}, skipping LTI configuration enrichment",
                data.path(Constants.PROTOCOL).asText("not set"));
        return null;
    }

    private boolean isLicenceTypeChanged(JsonNode data, ContentPartnerEntity jsonEntity) {
        String existingLicenceType = jsonEntity.getData().path(Constants.LICENCE_TYPE).asText(null);
        JsonNode incomingLicenceType = data.get(Constants.LICENCE_TYPE);
        return StringUtils.isNotBlank(existingLicenceType) && incomingLicenceType != null && !incomingLicenceType.isNull()
                && !existingLicenceType.equals(incomingLicenceType.asText());
    }

    private ApiResponse saveUpdatedPartner(ApiResponse response, JsonNode partnerDetails,
                                           ContentPartnerEntity jsonEntity, String existingId) {
        String partnerCode = jsonEntity.getData().path(Constants.PARTNERCODE).asText();
        createContentPartnerEntity(jsonEntity, partnerDetails);
        ContentPartnerEntity updateJsonEntity = entityRepository.save(jsonEntity);
        if (ObjectUtils.isEmpty(updateJsonEntity)) {
            return response;
        }
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
        if (jsonMap == null || StringUtils.isBlank((String) jsonMap.get(Constants.PARTNERCODE))) {
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
        ltiConfiguration.set(LTI_VERSION, TextNode.valueOf(requiredLtiSetting(cbServerProperties.getLtiVersion(), "lti.version")));
        ltiConfiguration.set(LTI_TOOL_ISSUER, TextNode.valueOf(requiredLtiSetting(cbServerProperties.getLtiToolIssuer(), "lti.tool.issuer")));
        ltiConfiguration.set(LTI_CLIENT_ID, TextNode.valueOf(requiredLtiSetting(cbServerProperties.getLtiClientId(), "lti.client.id")));
        ltiConfiguration.set(LTI_DEPLOYMENT_ID, TextNode.valueOf(requiredLtiSetting(cbServerProperties.getLtiDeploymentId(), "lti.deployment.id")));
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
        String firstWord = partnerName.trim().split("\\s+")[0].toUpperCase().replaceAll("[^A-Z]", "");
        String partnerCode;
        do {
            StringBuilder randomCode = new StringBuilder(5);
            for (int i = 0; i < 5; i++) {
                randomCode.append(cbServerProperties.getPartnerCodeChar().charAt(secureRandom.nextInt(cbServerProperties.getPartnerCodeChar().length())));
            }
            partnerCode = Constants.APPLICATION_ID_PREFIX + firstWord + "-" + randomCode;
        } while (entityRepository.findByPartnerCode(partnerCode).isPresent());
        return partnerCode;
    }

    private JsonNode addSearchTags(JsonNode formattedData) {
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
        if (formattedData.has("contentPartnerName")) {
            String partnerName = formattedData.get("contentPartnerName").textValue();
            if (StringUtils.isNotBlank(partnerName) && !searchTags.contains(partnerName.toLowerCase())) {
                searchTags.add(partnerName.toLowerCase());
            }
        }

        ArrayNode searchTagsArray = objectMapper.valueToTree(searchTags);
        ((ObjectNode) formattedData).set("searchTags", searchTagsArray);
        return formattedData;
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
        ObjectNode objectNode = (ObjectNode) partnerDetails;
        objectNode.remove(Constants.TRANSFORM_CONTENT_JSON);
        objectNode.remove(Constants.TRANSFORM_PROGRESS_JSON);
        objectNode.remove(Constants.CERTIFICATE_TEMPLATE_URL);
        objectNode.remove(Constants.ID);
        objectNode.remove(Constants.CONTENT_FILE_VALIDATION);
        objectNode.remove(Constants.TRANSFORM_CONTENT_VIA_API);
        objectNode.remove(Constants.TRANSFORM_PROGRESS_VIA_API);
        ObjectNode dataNode = (ObjectNode) objectNode.remove(Constants.DATA);
        dataNode.set(Constants.CREATED_ON, TextNode.valueOf(String.valueOf(jsonEntity.getCreatedOn())));
        dataNode.set(Constants.UPDATED_ON, TextNode.valueOf(String.valueOf(currentTime)));
        objectNode.set(Constants.DOCUMENT_UPLOADED_DATE, TextNode.valueOf(partnerDetails.path(Constants.DOCUMENT_UPLOADED_DATE).asText("")));
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
