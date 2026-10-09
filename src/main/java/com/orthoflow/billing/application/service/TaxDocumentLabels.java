package com.orthoflow.billing.application.service;

import com.orthoflow.billing.domain.model.TaxDocument;

import java.util.HashMap;
import java.util.Map;

/** The words on a fee note or care form, in each of the clinic's three languages. */
final class TaxDocumentLabels {

    private TaxDocumentLabels() {
    }

    static Map<String, String> of(String lang, TaxDocument.Kind kind) {
        Map<String, String> l = new HashMap<>();
        switch (lang) {
            case "en" -> {
                l.put("title", kind == TaxDocument.Kind.FEE_NOTE ? "FEE NOTE" : "CARE FORM");
                put(l, "duplicate", "DUPLICATE", "date", "Date", "patient", "Patient", "file", "File", "practitioner", "Practitioner",
                        "invoiceRef", "Invoice", "designation", "Description", "quantity", "Qty", "unitPrice", "Unit price",
                        "discount", "Discount", "amount", "Amount", "subtotal", "Subtotal", "tax", "VAT", "total", "Total",
                        "paid", "Paid", "balance", "Balance due", "words", "This note is closed at the sum of",
                        "patientSignature", "Patient's signature", "practitionerSignature", "Practitioner's stamp and signature",
                        "insurer", "Insurer", "insuranceNumber", "Membership number", "birthDate", "Date of birth",
                        "insured", "Insured person", "actCode", "Act code", "coefficient", "Rating");
            }
            case "ar" -> {
                l.put("title", kind == TaxDocument.Kind.FEE_NOTE ? "مذكرة أتعاب" : "ورقة العلاج");
                put(l, "duplicate", "نسخة", "date", "التاريخ", "patient", "المريض", "file", "ملف", "practitioner", "الطبيب",
                        "invoiceRef", "الفاتورة", "designation", "البيان", "quantity", "الكمية", "unitPrice", "الثمن",
                        "discount", "التخفيض", "amount", "المبلغ", "subtotal", "المجموع الجزئي", "tax", "الضريبة", "total", "المجموع",
                        "paid", "المؤدى", "balance", "الباقي", "words", "",
                        "patientSignature", "توقيع المريض", "practitionerSignature", "خاتم وتوقيع الطبيب",
                        "insurer", "الهيئة", "insuranceNumber", "رقم الانخراط", "birthDate", "تاريخ الازدياد",
                        "insured", "المؤمَّن", "actCode", "رمز العمل", "coefficient", "التسعيرة");
            }
            default -> {
                l.put("title", kind == TaxDocument.Kind.FEE_NOTE ? "NOTE D'HONORAIRES" : "FEUILLE DE SOINS");
                put(l, "duplicate", "DUPLICATA", "date", "Date", "patient", "Patient", "file", "Dossier", "practitioner", "Praticien",
                        "invoiceRef", "Facture", "designation", "Désignation", "quantity", "Qté", "unitPrice", "Prix unitaire",
                        "discount", "Remise", "amount", "Montant", "subtotal", "Sous-total", "tax", "TVA", "total", "Total",
                        "paid", "Réglé", "balance", "Reste dû", "words", "Arrêtée la présente note à la somme de",
                        "patientSignature", "Signature du patient", "practitionerSignature", "Cachet et signature du praticien",
                        "insurer", "Organisme assureur", "insuranceNumber", "N° d'immatriculation", "birthDate", "Date de naissance",
                        "insured", "Assuré(e)", "actCode", "Code acte", "coefficient", "Cotation");
            }
        }
        return l;
    }

    private static void put(Map<String, String> m, String... kv) {
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
    }
}
