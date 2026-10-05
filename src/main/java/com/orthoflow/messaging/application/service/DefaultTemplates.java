package com.orthoflow.messaging.application.service;

import com.orthoflow.messaging.domain.model.MessagePurpose;

import java.util.Optional;

/**
 * The wording a clinic gets before it edits anything. Deliberately minimal:
 * no diagnosis, no treatment, no amount owed beyond what the purpose needs —
 * a phone is not a clinical record (Law 09-08). Editing a template in settings
 * overrides these per channel and language.
 */
public final class DefaultTemplates {

    public record Text(String subject, String body) {
    }

    private DefaultTemplates() {
    }

    public static Optional<Text> find(MessagePurpose purpose, String language) {
        String lang = language == null ? "fr" : language;
        return switch (purpose) {
            case APPOINTMENT_REMINDER -> Optional.of(switch (lang) {
                case "ar" -> new Text("تذكير بموعدك", "مرحباً {{patientName}}، نذكّركم بموعدكم يوم {{date}} على الساعة {{time}} في {{clinicName}}. أجيبوا بـ 1 للتأكيد أو 2 للإلغاء.");
                case "en" -> new Text("Appointment reminder", "Hello {{patientName}}, a reminder of your appointment on {{date}} at {{time}} at {{clinicName}}. Reply 1 to confirm or 2 to cancel.");
                default -> new Text("Rappel de rendez-vous", "Bonjour {{patientName}}, rappel de votre rendez-vous le {{date}} à {{time}} au {{clinicName}}. Répondez 1 pour confirmer ou 2 pour annuler.");
            });
            case APPOINTMENT_CONFIRMED -> Optional.of(switch (lang) {
                case "ar" -> new Text("تم تأكيد موعدك", "شكراً {{patientName}}، تم تأكيد موعدكم يوم {{date}} على الساعة {{time}}.");
                case "en" -> new Text("Appointment confirmed", "Thank you {{patientName}}, your appointment on {{date}} at {{time}} is confirmed.");
                default -> new Text("Rendez-vous confirmé", "Merci {{patientName}}, votre rendez-vous du {{date}} à {{time}} est confirmé.");
            });
            case APPOINTMENT_CANCELLED -> Optional.of(switch (lang) {
                case "ar" -> new Text("تم إلغاء موعدك", "{{patientName}}، تم إلغاء موعدكم يوم {{date}} على الساعة {{time}}. اتصلوا بنا لتحديد موعد جديد: {{clinicPhone}}.");
                case "en" -> new Text("Appointment cancelled", "{{patientName}}, your appointment on {{date}} at {{time}} was cancelled. Call us to rebook: {{clinicPhone}}.");
                default -> new Text("Rendez-vous annulé", "{{patientName}}, votre rendez-vous du {{date}} à {{time}} a été annulé. Appelez-nous pour en reprendre un : {{clinicPhone}}.");
            });
            case INSTALMENT_REMINDER -> Optional.of(switch (lang) {
                case "ar" -> new Text("تذكير بالدفع", "مرحباً {{patientName}}، نذكّركم بقسط بقيمة {{amount}} {{currency}} مستحق يوم {{date}}. {{clinicName}}");
                case "en" -> new Text("Payment reminder", "Hello {{patientName}}, a reminder that an instalment of {{amount}} {{currency}} is due on {{date}}. {{clinicName}}");
                default -> new Text("Rappel d'échéance", "Bonjour {{patientName}}, rappel : une échéance de {{amount}} {{currency}} est due le {{date}}. {{clinicName}}");
            });
            case RECALL -> Optional.of(switch (lang) {
                case "ar" -> new Text("حان وقت زيارتك", "مرحباً {{patientName}}، لم نرَكم منذ مدة. اتصلوا بنا لتحديد موعد: {{clinicPhone}}. {{clinicName}}");
                case "en" -> new Text("Time for a check-up", "Hello {{patientName}}, it has been a while. Call us to book a visit: {{clinicPhone}}. {{clinicName}}");
                default -> new Text("C'est le moment de nous revoir", "Bonjour {{patientName}}, nous ne vous avons pas vu depuis un moment. Appelez-nous pour un rendez-vous : {{clinicPhone}}. {{clinicName}}");
            });
            case SURVEY_REQUEST -> Optional.of(switch (lang) {
                case "ar" -> new Text("رأيكم يهمنا", "شكراً على زيارتكم {{patientName}}. شاركونا رأيكم في دقيقة: {{link}}");
                case "en" -> new Text("How was your visit?", "Thank you for visiting, {{patientName}}. Tell us how it went in a minute: {{link}}");
                default -> new Text("Votre avis nous intéresse", "Merci de votre visite, {{patientName}}. Donnez-nous votre avis en une minute : {{link}}");
            });
            case BOOKING_RECEIVED -> Optional.of(switch (lang) {
                case "ar" -> new Text("تم استلام طلبكم", "شكراً {{patientName}}، تم استلام طلب موعدكم وسنؤكده قريباً. {{clinicName}}");
                case "en" -> new Text("Request received", "Thank you {{patientName}}, we received your appointment request and will confirm shortly. {{clinicName}}");
                default -> new Text("Demande reçue", "Merci {{patientName}}, nous avons bien reçu votre demande de rendez-vous et la confirmerons rapidement. {{clinicName}}");
            });
            case BOOKING_CONFIRMED -> Optional.of(switch (lang) {
                case "ar" -> new Text("تم تأكيد موعدك", "{{patientName}}، تم تأكيد موعدكم يوم {{date}} على الساعة {{time}} في {{clinicName}}.");
                case "en" -> new Text("Appointment confirmed", "{{patientName}}, your appointment on {{date}} at {{time}} at {{clinicName}} is confirmed.");
                default -> new Text("Rendez-vous confirmé", "{{patientName}}, votre rendez-vous du {{date}} à {{time}} au {{clinicName}} est confirmé.");
            });
            case BOOKING_DECLINED -> Optional.of(switch (lang) {
                case "ar" -> new Text("تعذر تأكيد الموعد", "{{patientName}}، تعذّر علينا تأكيد الموعد المطلوب. اتصلوا بنا لاقتراح وقت آخر: {{clinicPhone}}.");
                case "en" -> new Text("Appointment not available", "{{patientName}}, we could not confirm the requested time. Please call us for another slot: {{clinicPhone}}.");
                default -> new Text("Créneau indisponible", "{{patientName}}, nous n'avons pas pu confirmer le créneau demandé. Appelez-nous pour en convenir un autre : {{clinicPhone}}.");
            });
            case REGISTRATION_INVITE -> Optional.of(switch (lang) {
                case "ar" -> new Text("سجّلوا بياناتكم", "مرحباً {{patientName}}، املؤوا ملفكم قبل زيارتكم لـ {{clinicName}}: {{link}}");
                case "en" -> new Text("Register before your visit", "Hello {{patientName}}, please fill in your details before your visit to {{clinicName}}: {{link}}");
                default -> new Text("Remplissez votre fiche", "Bonjour {{patientName}}, merci de remplir votre fiche avant votre visite chez {{clinicName}} : {{link}}");
            });
            case TEST -> Optional.of(new Text("Test", "{{clinicName}} — test message / message de test."));
            default -> Optional.empty();
        };
    }
}
