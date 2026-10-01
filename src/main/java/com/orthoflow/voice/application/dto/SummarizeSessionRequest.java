package com.orthoflow.voice.application.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which staged entries the summary should cover — the ones still included at
 * review. Ids narrow what the server itself recorded for the session; they
 * cannot add anything to it.
 */
@Getter
@Setter
public class SummarizeSessionRequest {

    /**
     * Absent: every clinical entry the session staged. Present and empty:
     * none of them — the dentist excluded everything.
     */
    private List<UUID> auditIds;

    /**
     * Teeth the dentist corrected at review, by audit id. The narrative is
     * written from what the session recorded, so without this a finding moved
     * from 16 to 26 at review would still be described on 16 while being saved
     * on 26. Only the tooth can be changed this way; everything else in the
     * narrative still comes from the recorded command.
     */
    private Map<UUID, String> correctedTeeth;
}
