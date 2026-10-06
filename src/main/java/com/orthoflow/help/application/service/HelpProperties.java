package com.orthoflow.help.application.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * "Ask OrthoFlow" answers questions from the help notes with a language model.
 *
 * <p>Off by default, and that is deliberate. What is sent is the question, which a
 * person types and might use to name a patient, plus the help notes, which are
 * documentation. Whoever enables it is accepting that questions leave the clinic for
 * the model vendor, which is a data-protection decision (Law 09-08) and not one the
 * code should make on their behalf; the screen warns people not to type patient names.
 */
@Component
@ConfigurationProperties(prefix = "orthoflow.help.ask")
@Getter
@Setter
public class HelpProperties {

    private boolean enabled = false;
    private String apiKey = "";
    /** A small, fast model: this is reading a few paragraphs and answering in a few sentences. */
    private String model = "claude-haiku-4-5-20251001";
    private int maxOutputTokens = 500;
    private int timeoutMs = 15000;
    private int maxQuestionChars = 500;
    private int questionsPerHour = 20;
    private int maxNotes = 5;
}
