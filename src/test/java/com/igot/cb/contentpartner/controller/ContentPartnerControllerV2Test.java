package com.igot.cb.contentpartner.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.contentpartner.service.ContentPartnerServiceV2;
import com.igot.cb.pores.util.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentPartnerControllerV2Test {

    @Mock
    private ContentPartnerServiceV2 partnerService;

    @InjectMocks
    private ContentPartnerControllerV2 controller;

    @Test
    void createDelegatesToServiceAndReturnsItsStatus() {
        JsonNode body = new ObjectMapper().createObjectNode();
        ApiResponse serviceResponse = new ApiResponse();
        serviceResponse.setResponseCode(HttpStatus.OK);
        when(partnerService.createOrUpdate(body)).thenReturn(serviceResponse);

        ResponseEntity<ApiResponse> response = controller.create(body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(serviceResponse, response.getBody());
    }

    @Test
    void clientErrorFromServiceIsPropagated() {
        JsonNode body = new ObjectMapper().createObjectNode();
        ApiResponse serviceResponse = new ApiResponse();
        serviceResponse.setResponseCode(HttpStatus.BAD_REQUEST);
        when(partnerService.createOrUpdate(body)).thenReturn(serviceResponse);

        assertEquals(HttpStatus.BAD_REQUEST, controller.create(body).getStatusCode());
    }

    @Test
    void createAndUpdateRoutesMapToTheSameHandlerUnderV2() throws NoSuchMethodException {
        RequestMapping base = ContentPartnerControllerV2.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/contentpartner/v2"}, base.value());
        PostMapping post = ContentPartnerControllerV2.class.getMethod("create", JsonNode.class)
                .getAnnotation(PostMapping.class);
        assertArrayEquals(new String[]{"/create", "/update"}, post.value());
    }
}
