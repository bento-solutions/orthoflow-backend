package com.orthoflow.help.application.service;

/** Turns a prompt into an answer. A port so the retrieval and the limits are testable without a vendor. */
public interface HelpAnswerer {

    boolean available();

    /** @throws RuntimeException when the vendor call fails; the caller degrades to listing the notes */
    String answer(String system, String question);
}
