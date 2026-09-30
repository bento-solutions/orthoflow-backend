package com.orthoflow.voice.application.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * Which staged entries the summary should cover — the ones still included at
 * review. Ids narrow what the server itself recorded for the session; they
 * cannot add anything to it.
 */
@Getter
@Setter
public class SummarizeSessionRequest {

    /** Null or empty: every clinical entry the session staged. */
    private List<UUID> auditIds;
}
