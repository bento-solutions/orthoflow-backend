package com.orthoflow.platform.events;

import com.orthoflow.common.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class LiveEventsController {

    private final LiveEvents liveEvents;
    private final CurrentUserProvider currentUser;

    /**
     * Authenticated like every route. The browser's EventSource cannot send an
     * Authorization header, so the frontend reads this stream with fetch; no
     * token ever rides in the URL.
     */
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return liveEvents.subscribe(currentUser.requirePracticeId());
    }
}
