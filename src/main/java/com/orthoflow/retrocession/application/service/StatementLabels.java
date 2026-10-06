package com.orthoflow.retrocession.application.service;

import java.util.Map;

/** The words on a printed retrocession statement, in the three languages the clinic documents come in. */
final class StatementLabels {

    private StatementLabels() {
    }

    static Map<String, String> of(String lang) {
        return switch (lang) {
            case "en" -> Map.ofEntries(
                    Map.entry("title", "RETROCESSION STATEMENT"), Map.entry("practitioner", "Practitioner"), Map.entry("period", "Period"),
                    Map.entry("validated", "Validated on"), Map.entry("date", "Date"), Map.entry("reference", "Invoice"),
                    Map.entry("patient", "Patient code"), Map.entry("what", "Category / item"), Map.entry("base", "Base"),
                    Map.entry("rate", "Rate"), Map.entry("amount", "Amount"), Map.entry("totalBase", "Total base"),
                    Map.entry("variable", "Percentage due"), Map.entry("lab", "Lab fees deducted"), Map.entry("fixed", "Fixed amount"),
                    Map.entry("adjustment", "Adjustment"), Map.entry("gross", "Total due"), Map.entry("advances", "Advances deducted"),
                    Map.entry("net", "Net payable"), Map.entry("paid", "Paid so far"), Map.entry("voided", "VOID"),
                    Map.entry("signature", "Signature of the practitioner"), Map.entry("clinicSignature", "Clinic stamp and signature"),
                    Map.entry("notes", "Notes"));
            case "ar" -> Map.ofEntries(
                    Map.entry("title", "كشف الاستردادات"), Map.entry("practitioner", "الطبيب"), Map.entry("period", "الفترة"),
                    Map.entry("validated", "تاريخ المصادقة"), Map.entry("date", "التاريخ"), Map.entry("reference", "الفاتورة"),
                    Map.entry("patient", "رمز المريض"), Map.entry("what", "الفئة / البند"), Map.entry("base", "الأساس"),
                    Map.entry("rate", "النسبة"), Map.entry("amount", "المبلغ"), Map.entry("totalBase", "مجموع الأساس"),
                    Map.entry("variable", "النسبة المستحقة"), Map.entry("lab", "مصاريف المختبر المخصومة"), Map.entry("fixed", "المبلغ الثابت"),
                    Map.entry("adjustment", "تسوية"), Map.entry("gross", "المبلغ المستحق"), Map.entry("advances", "الدفعات المقدمة المخصومة"),
                    Map.entry("net", "الصافي المستحق"), Map.entry("paid", "المدفوع إلى الآن"), Map.entry("voided", "ملغى"),
                    Map.entry("signature", "توقيع الطبيب"), Map.entry("clinicSignature", "ختم وتوقيع العيادة"),
                    Map.entry("notes", "ملاحظات"));
            default -> Map.ofEntries(
                    Map.entry("title", "RELEVÉ DE RÉTROCESSION"), Map.entry("practitioner", "Praticien"), Map.entry("period", "Période"),
                    Map.entry("validated", "Validé le"), Map.entry("date", "Date"), Map.entry("reference", "Facture"),
                    Map.entry("patient", "Code patient"), Map.entry("what", "Catégorie / acte"), Map.entry("base", "Base"),
                    Map.entry("rate", "Taux"), Map.entry("amount", "Montant"), Map.entry("totalBase", "Total des bases"),
                    Map.entry("variable", "Pourcentage dû"), Map.entry("lab", "Frais de laboratoire déduits"), Map.entry("fixed", "Montant fixe"),
                    Map.entry("adjustment", "Ajustement"), Map.entry("gross", "Total dû"), Map.entry("advances", "Avances déduites"),
                    Map.entry("net", "Net à payer"), Map.entry("paid", "Déjà réglé"), Map.entry("voided", "ANNULÉ"),
                    Map.entry("signature", "Signature du praticien"), Map.entry("clinicSignature", "Cachet et signature du cabinet"),
                    Map.entry("notes", "Observations"));
        };
    }
}
