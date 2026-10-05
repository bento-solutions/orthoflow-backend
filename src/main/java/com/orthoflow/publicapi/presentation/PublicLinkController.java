package com.orthoflow.publicapi.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.publicapi.application.service.PublicLinkService;
import com.orthoflow.publicapi.domain.model.PublicLinkPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Staff side: show or rotate the clinic-wide booking and registration links. */
@RestController
@RequestMapping("/public-links")
@RequiredArgsConstructor
public class PublicLinkController {

    private final PublicLinkService service;
    private final CurrentUserProvider currentUser;

    @Value("${app.frontend-url:http://localhost:4200}")
    private String frontendUrl;

    @GetMapping("/shared/{purpose}")
    public Map<String, String> shared(@PathVariable PublicLinkPurpose purpose) {
        return Map.of("url", url(purpose, service.sharedToken(currentUser.requirePracticeId(), purpose)));
    }

    @PostMapping("/shared/{purpose}/rotate")
    public Map<String, String> rotate(@PathVariable PublicLinkPurpose purpose) {
        return Map.of("url", url(purpose, service.rotateShared(currentUser.requirePracticeId(), purpose, currentUser.requireUserId())));
    }

    private String url(PublicLinkPurpose purpose, String token) {
        String segment = switch (purpose) {
            case BOOKING -> "book";
            case REGISTRATION -> "register";
            case SURVEY -> "survey";
        };
        return frontendUrl + "/public/" + segment + "/" + token;
    }
}
