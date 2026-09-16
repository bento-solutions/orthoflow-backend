package com.orthoflow.voice.infrastructure.nlu;

import com.orthoflow.voice.application.dto.InterpretRequest;
import com.orthoflow.voice.application.dto.IntentDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the instruction and the question sent to whichever model is
 * configured. Shared by every provider so the two paths cannot drift into
 * behaving differently on the same utterance.
 *
 * <p>The prompt is written around one property: <em>refusing to answer is a
 * correct answer</em>. A model that picks the likelier of two teeth to keep
 * the conversation moving is worse than useless here, because the doctor's
 * hands are busy and the wrong tooth gets a clinical record.
 */
@Component
public class NluPromptBuilder {

    public String systemPrompt(InterpretRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You convert a dentist's spoken utterance into ONE structured command for a dental
                practice management system, or into a clarifying question when the utterance does
                not identify exactly one command and its arguments.

                Respond with a single JSON object and nothing else. No prose, no markdown fence.

                Schema:
                {
                  "intent": string | null,   // exactly one id from the AVAILABLE INTENTS list, or null
                  "entities": object,        // arguments for that intent, using the documented arg names
                  "confidence": number,      // 0.0-1.0, your calibrated confidence in intent AND entities
                  "clarification": string | null  // the question to ask, when intent is null
                }

                Rules, in priority order:
                1. SAFETY OVER HELPFULNESS. If the utterance could plausibly mean two different
                   teeth, patients, or actions, set "intent": null and put a short, specific
                   question in "clarification". Never pick the more likely candidate.
                   "upper molar needs treatment" names no single tooth -> ask which one.
                2. Use ONLY intent ids from the AVAILABLE INTENTS list below. Never invent one.
                   If nothing in the list fits, "intent": null with a clarification saying so.
                3. Use ONLY finding codes from the FINDING VOCABULARY list. Never invent one.
                4. Teeth are identified by two-digit FDI codes. Convert every spoken form:
                   quadrant 1 = upper right, 2 = upper left, 3 = lower left, 4 = lower right
                   (from the patient's perspective, which is the clinical convention);
                   5-8 are the same quadrants in the deciduous/child dentition.
                   Position 1 = central incisor, 2 = lateral incisor, 3 = canine,
                   4 = first premolar, 5 = second premolar, 6 = first molar,
                   7 = second molar, 8 = third molar/wisdom.
                   So "upper right first molar" = 16, "lower left second molar" = 37,
                   "upper left central incisor" = 21.
                   A bare number that is already a valid FDI code ("tooth sixteen") is that code.
                5. One utterance may carry several findings about one tooth
                   ("old crown, recurrent caries underneath, crown needs replacing").
                   Return them all in the entities, never just the last one.
                6. Confidence must reflect the weakest link. If you are sure of the finding but
                   unsure of the tooth, the confidence is low.
                7. Never include patient names, identifiers, or record contents in "clarification"
                   beyond what the utterance itself contained.
                """);

        sb.append("\n\nCONTEXT\n");
        sb.append("- Active module: ").append(orUnknown(request.getModule())).append('\n');
        sb.append("- A patient record is currently open: ").append(request.isPatientContext()).append('\n');
        sb.append("- Dentition: ").append(orUnknown(request.getChartType())).append('\n');
        if (request.getSelectedFdi() != null && !request.getSelectedFdi().isBlank()) {
            sb.append("- Tooth currently selected: FDI ").append(request.getSelectedFdi())
              .append(" (this is what \"that tooth\", \"this one\", \"it\" refer to)\n");
        } else {
            sb.append("- No tooth is selected, so \"that tooth\" cannot be resolved — ask.\n");
        }
        sb.append("- Spoken language: ").append(orUnknown(request.getLocale()))
          .append(" (clinicians here mix French, Arabic and English mid-sentence; "
                  + "interpret whichever appears)\n");

        if (request.getRecentUtterances() != null && !request.getRecentUtterances().isEmpty()) {
            sb.append("\nRECENT UTTERANCES IN THIS CONSULTATION (oldest first) — a correction such as\n")
              .append("\"no, actually crown replacement\" refers back to these:\n");
            for (String utterance : request.getRecentUtterances()) {
                sb.append("- ").append(utterance).append('\n');
            }
        }

        sb.append("\nAVAILABLE INTENTS\n");
        for (IntentDescriptor intent : request.getAvailableIntents()) {
            sb.append("- ").append(intent.id()).append(": ").append(intent.description()).append('\n');
            if (intent.args() != null && !intent.args().isEmpty()) {
                sb.append("    args: ");
                sb.append(intent.args().entrySet().stream()
                        .map(e -> e.getKey() + " (" + e.getValue() + ")")
                        .collect(Collectors.joining(", ")));
                sb.append('\n');
            }
            if (intent.examples() != null && !intent.examples().isEmpty()) {
                for (String example : intent.examples()) {
                    sb.append("    e.g. \"").append(example).append("\"\n");
                }
            }
        }

        List<String> codes = request.getFindingCodes();
        if (codes != null && !codes.isEmpty()) {
            sb.append("\nFINDING VOCABULARY\n");
            sb.append(String.join(", ", codes)).append('\n');
        }

        return sb.toString();
    }

    public String userPrompt(InterpretRequest request) {
        return "Utterance: \"" + request.getTranscript().trim() + "\"";
    }

    private static final Map<String, Object> STRING = Map.of("type", "string");

    /**
     * Both providers ask for the same shape; keeping the schema in one place.
     *
     * <p>{@code entities} has to name every argument a command can take, and
     * that is not tidiness. Structured output emits only the properties a
     * schema declares, so the bare {@code {"type": "object"}} this used to
     * carry came back as {@code {}} on every call — an intent with no tooth
     * and no findings, which downstream reads as "understood, recorded
     * nothing". A command whose argument is missing here is a command the
     * fallback can never complete.
     */
    public Map<String, Object> responseSchema() {
        Map<String, Object> finding = Map.of(
                "type", "object",
                "properties", Map.of(
                        "code", STRING,
                        "surface", STRING,
                        "severity", STRING,
                        "note", STRING),
                "required", List.of("code"));

        Map<String, Object> entities = Map.of(
                "type", "object",
                "properties", Map.ofEntries(
                        // chart.addToothFindings / replaceLastFinding / removeFinding
                        Map.entry("fdi", STRING),
                        Map.entry("findings", Map.of("type", "array", "items", finding)),
                        Map.entry("codes", Map.of("type", "array", "items", STRING)),
                        // clinical.addNote
                        Map.entry("category", STRING),
                        Map.entry("content", STRING),
                        // clinical.addMedicalHistory
                        Map.entry("label", STRING),
                        Map.entry("detail", STRING),
                        // clinical.addAllergy
                        Map.entry("substance", STRING),
                        Map.entry("reaction", STRING),
                        Map.entry("severity", STRING),
                        Map.entry("note", STRING),
                        // schedule.followUp
                        Map.entry("when", STRING)));

        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "intent", Map.of("type", List.of("string", "null")),
                        "entities", entities,
                        "confidence", Map.of("type", "number"),
                        "clarification", Map.of("type", List.of("string", "null"))),
                "required", List.of("intent", "entities", "confidence", "clarification"));
    }

    private String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
