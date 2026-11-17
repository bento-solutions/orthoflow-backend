package com.orthoflow.help.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.help.application.dto.GuideDtos.GuideProgress;
import com.orthoflow.help.application.dto.GuideDtos.SaveGuideProgress;
import com.orthoflow.help.application.service.GuideProgressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The signed-in user's own progress through the first-login guide. Anyone signed in
 * ({@code /me/**} in SecurityConfig); the user is always the one in the token.
 */
@RestController
@RequestMapping("/me/guides")
@RequiredArgsConstructor
public class GuideController {

    private final GuideProgressService guides;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public List<GuideProgress> mine() {
        return guides.mine(currentUser.requireUserId());
    }

    @PutMapping("/{key}")
    public GuideProgress save(@PathVariable String key, @Valid @RequestBody SaveGuideProgress body) {
        return guides.save(currentUser.requireUserId(), GuideProgressService.key(key), body);
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable String key) {
        guides.reset(currentUser.requireUserId(), GuideProgressService.key(key));
    }
}
