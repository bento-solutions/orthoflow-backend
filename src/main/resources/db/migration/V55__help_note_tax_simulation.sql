-- The built-in "analytics" help note gains a paragraph on the income-tax simulator.
-- Only the built-in notes (practice_id NULL) change; a clinic's own override is left as written.
UPDATE help_notes SET body = body || E'\n' || $n$Simulateur IR : aucun taux n'est intégré. Saisissez le barème de la loi pour l'année (tranches, déduction par personne à charge) et sa source ; l'application ne fait que le calcul et affiche la source à côté du résultat. C'est une simulation, pas un conseil fiscal.$n$
 WHERE practice_id IS NULL AND page_key = 'analytics' AND lang = 'fr';
UPDATE help_notes SET body = body || E'\n' || $n$Income tax simulator: no rates are built in. Enter the schedule from the law for the year (bands, deduction per dependent) and its source; the application only does the arithmetic and shows the source beside the result. It is a simulation, not tax advice.$n$
 WHERE practice_id IS NULL AND page_key = 'analytics' AND lang = 'en';
UPDATE help_notes SET body = body || E'\n' || $n$محاكي الضريبة على الدخل: لا توجد أي نسب مضمّنة. أدخل جدول القانون للسنة (الشرائح، الخصم عن كل شخص في الكفالة) ومصدره؛ يقتصر التطبيق على الحساب ويعرض المصدر بجانب النتيجة. إنها محاكاة وليست نصيحة ضريبية.$n$
 WHERE practice_id IS NULL AND page_key = 'analytics' AND lang = 'ar';
