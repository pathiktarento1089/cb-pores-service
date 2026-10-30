package com.igot.cb.pores.util;

import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
public class CbServerProperties {

  @Value("${default.content.properties}")
  private String defaultContentProperties;

  @Value("${content-service-host}")
  private String contentHost;

  @Value("${content-read-endpoint}")
  private String contentReadEndPoint;

  @Value("${content-read-endpoint-fields}")
  private String contentReadEndPointFields;

  @Value("${redis.insights.index}")
  private int redisInsightIndex;

//  @Value("#{${playlist.redis.key.mapping}}")
//  private Map<String, String> PlayListRedisKeyMapping;

  @Value("${search.result.redis.ttl}")
  private long searchResultRedisTtl;

  @Value("${jwt.secret.key}")
  private String jwtSecretKey;

  @Value("${sb.api.key}")
  private String sbApiKey;

  @Value("${learner.service.url}")
  private String learnerServiceUrl;

  @Value("${sb.org.search.path}")
  private String orgSearchPath;

  @Value("${elastic.required.field.demand.json.path}")
  private String elasticDemandJsonPath;

  @Value("${elastic.required.field.content.json.path}")
  private String elasticContentJsonPath;

  @Value("${elastic.required.field.content.partner.json.path}")
  private String elasticContentPartnerJsonPath;


  @Value("${elastic.required.field.interest.json.path}")
  private String elasticInterestJsonPath;

  @Value("${elastic.required.field.bookmark.json.path}")
  private String elasticBookmarkJsonPath;

  @Value("${bookmark.duplicate.not.allowed.category}")
  private String bookmarkDuplicateNotAllowedCategory;

  @Value("${elastic.required.field.cios.json.path}")
  private String elasticCiosJsonPath;

  @Value("${non.text.fields}")
  private List<String> nonTextFields;

  @Value("${partner.code.characters}")
  private String partnerCodeChar;

  @Value("${partner.code.max.attempts:20}")
  private int partnerCodeMaxAttempts;

  @Value("${partner.code.length:5}")
  private int partnerCodeLength;

  @Value("${content.partner.v2.update.validation.schema.path}")
  private String contentPartnerV2ValidationSchemaPath;

  @Value("${content.partner.v2.create.validation.schema.path}")
  private String contentPartnerV2CreateValidationSchemaPath;

  @Value("${lti.version:1.3}")
  private String ltiVersion;

  @Value("${lti.tool.issuer:}")
  private String ltiToolIssuer;

  @Value("${lti.client.id:}")
  private String ltiClientId;

  @Value("${lti.deployment.id:}")
  private String ltiDeploymentId;

  @Value("${content.partner.search.cache.key:demand_search_result}")
  private String contentPartnerSearchCacheKey;

  public List<String> getBookmarkDuplicateNotAllowedCategory() {
    return Arrays.asList(bookmarkDuplicateNotAllowedCategory.split(","));
  }

  public void setBookmarkDuplicateNotAllowedCategory(String bookmarkDuplicateNotAllowedCategory) {
    this.bookmarkDuplicateNotAllowedCategory = bookmarkDuplicateNotAllowedCategory;
  }
  @Value("${notify.service.host}")
  private String notifyServiceHost;

  @Value("${notify.service.path.async}")
  private String notificationAsyncPath;

  @Value("${kafka.topic.demand.request}")
  private String demandRequestKafkaTopic;

  @Value("${kafka.topic.content.partner.registration}")
  private String contentPartnerRegistrationTopic;

  @Value("${notification.support.mail}")
  private String supportEmail;

  @Value("${demand.request.notification.template}")
  private String demandRequestTemplate;

  @Value("${learner.service.url}")
  private String sbUrl;

  @Value("${sunbird.user.search.endpoint}")
  private String userSearchEndPoint;

  @Value("${lms.user.read.path}")
  private String userReadEndPoint;

  @Value("${announcement.default.search.pageSize}")
  private int announcementDefaultSearchPageSize;

  @Value("${sb.search.service.host}")
  private String sbSearchServiceHost;

  @Value("${sb.composite.v4.search}")
  private String sbCompositeV4Search;

  @Value("${playlist_course_facets}")
  private String[] courseCategoryFacet;

  @Value("${elastic.required.field.designation.json.path}")
  private String elasticDesignationJsonPath;

  @Value("${odcs.framework.name}")
  private String odcsDesignationFramework;

  @Value("${odcs.designation.category.name}")
  private String odcsDesignationCategory;

  @Value("${knowledge.mv.service}")
  private String knowledgeMS;

  @Value("${odcs.term.create}")
  private String odcsTermCrete;

  @Value("${odcs.category.fields}")
  private String odcsFields;

  @Value("${odcs.designation.term.read}")
  private String odcsDesignationTermRead;

  public List<String> getOdcsFields() {
    return Arrays.asList(odcsFields.split(",", -1));
  }
  
  @Value("${elastic.required.field.compArea.json.path}")
  private String elasticCompJsonPath;

  @Value("${odcs.competency.theme.category.name}")
  private String odcsCompetencyThemeCategory;

  @Value("${odcs.competency.sub.theme.category.name}")
  private String odcsCompetencySubThemeCategory;

  @Value("${odcs.framework.create}")
  private String odcsFrameworkCreate;

  @Value("${odcs.framework.read}")
  private String odcsFrameworkRead;

  @Value("${framework.copy}")
  private String frameworkCopy;

  @Value("${framework.publish}")
  private String frameworkPublish;

  @Value("${kafka.topic.framework.create}")
  private String topicFrameworkCreate;

  @Value("${search.string.max.regex.length}")
  private int searchStringMaxRegexLength;

  @Value("${cios.content.service.host}")
  private String ciosContentServiceHost;

    @Value("${cios.content.service.update.api}")
    private String ciosContentServiceUpdateApiUrl;

    @Value("${cios.content.service.search.api}")
    private String ciosContentServiceSearchApiUrl;

    @Value("${org.update.endpoint}")
    private String orgUpdateEndpoint;

    @Value("${org.hierarchy.required.roles}")
    private String orgHierarchyCreateRoles;

    @Value("${kafka.topic.content.partner.delete}")
    private String contentPartnerDeleteTopic;

    @Value("${kafka.topic.content.partner.activate}")
    private String contentPartnerActivateTopic;

    @Value("${search.fields.with.boost}")
    private String searchFieldsWithBoost;

    @Value("${search.fields.with.boost.designation:searchTags:5.0}")
    private String designationSearchFieldsWithBoost;

    @Value("${search.designation.exact.match.boost:100}")
    private float designationExactMatchBoost;

    @Value("${max.demand.create.byuser:100}")
    private int maxDemandCreateByUser;

    @Value("${max.demand.create.byuser.ttl:3600}")
    private int maxDemandCreateByUserTtl;

    @Value("${ext.course.compatibility.level}")
    private int extCourseCompatibilityLevel;

    public List<String> getOrgHierarchyCreateRequiredRoles() {
      return Arrays.asList(orgHierarchyCreateRoles.split(","));
    }

}
