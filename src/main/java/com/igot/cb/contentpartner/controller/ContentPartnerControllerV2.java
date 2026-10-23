package com.igot.cb.contentpartner.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.contentpartner.service.ContentPartnerServiceV2;
import com.igot.cb.pores.util.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/contentpartner/v2")
public class ContentPartnerControllerV2 {

    private final ContentPartnerServiceV2 partnerService;

    public ContentPartnerControllerV2(ContentPartnerServiceV2 partnerService) {
        this.partnerService = partnerService;
    }

    @PostMapping({"/create", "/update"})
    public ResponseEntity<ApiResponse> create(@RequestBody JsonNode contentPartnerDetails) {
        ApiResponse response = partnerService.createOrUpdate(contentPartnerDetails);
        return new ResponseEntity<>(response, response.getResponseCode());
    }
}
