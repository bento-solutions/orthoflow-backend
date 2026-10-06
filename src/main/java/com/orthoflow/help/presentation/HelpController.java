package com.orthoflow.help.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.help.application.dto.HelpDtos.*;
import com.orthoflow.help.application.service.AskService;
import com.orthoflow.help.application.service.HelpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Help notes per screen and the assistant that answers from them. Anyone signed in can
 * read and ask; changing a note is SETTINGS_MANAGE (SecurityConfig).
 */
@RestController
@RequestMapping("/help")
@RequiredArgsConstructor
public class HelpController {

    private final HelpService help;
    private final AskService ask;
    private final CurrentUserProvider currentUser;

    @GetMapping("/notes")
    public List<NoteView> notes(@RequestParam(defaultValue = "fr") String lang) {
        return help.all(currentUser.requirePracticeId(), lang);
    }

    @GetMapping("/notes/{pageKey}")
    public NoteView note(@PathVariable String pageKey, @RequestParam(defaultValue = "fr") String lang) {
        return help.get(currentUser.requirePracticeId(), pageKey, lang);
    }

    @PutMapping("/notes/{pageKey}")
    public NoteView save(@PathVariable String pageKey, @RequestParam String lang, @Valid @RequestBody SaveNote body) {
        return help.save(currentUser.requirePracticeId(), currentUser.requireUserId(), pageKey, lang, body.title(), body.body());
    }

    @DeleteMapping("/notes/{pageKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable String pageKey, @RequestParam String lang) {
        help.reset(currentUser.requirePracticeId(), pageKey, lang);
    }

    @PostMapping("/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        return ask.ask(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }
}
