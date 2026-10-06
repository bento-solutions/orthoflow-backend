-- Denteam parity F6: the built-in help notes (practice_id NULL), French, English and Arabic.
--
-- Documentation for each screen, written against what the application does. A clinic
-- overrides a note by saving its own; deleting that override restores these. Kept in a
-- migration of its own so editing the wording never touches the schema one.

INSERT INTO help_notes (id, practice_id, page_key, lang, title, body) VALUES
    (gen_random_uuid(), NULL, 'agenda', 'fr', $n$Agenda et salle d'attente$n$, $n$L'agenda affiche les rendez-vous par praticien et par fauteuil. Un praticien ne peut pas être réservé deux fois sur la même plage horaire, et un fauteuil non plus.
À l'arrivée du patient, utilisez Arrivé, puis Installé, puis Terminé : ces horodatages alimentent la salle d'attente et l'analyse du temps médecin.
Un rendez-vous annulé ou non honoré libère son créneau. La liste d'attente permet de reprogrammer un patient quand une place se libère.$n$),
    (gen_random_uuid(), NULL, 'agenda', 'en', $n$Agenda and waiting room$n$, $n$The agenda shows appointments by practitioner and by chair. A practitioner cannot be booked twice in the same time slot, and neither can a chair.
When the patient arrives, use Arrived, then Seated, then Finished: these timestamps feed the waiting room and the doctor-time analysis.
A cancelled or missed appointment frees its slot. The waiting list lets you reschedule a patient when a slot opens.$n$),
    (gen_random_uuid(), NULL, 'agenda', 'ar', $n$الأجندة وقاعة الانتظار$n$, $n$تعرض الأجندة المواعيد حسب الطبيب وحسب الكرسي. لا يمكن حجز الطبيب مرتين في نفس الفترة، ولا الكرسي كذلك.
عند وصول المريض استعمل وصل ثم جلس ثم انتهى: هذه الأوقات تغذي قاعة الانتظار وتحليل وقت الطبيب.
الموعد الملغى أو الفائت يحرر فترته. تتيح لائحة الانتظار إعادة برمجة مريض عندما تتوفر فترة.$n$),
    (gen_random_uuid(), NULL, 'patients', 'fr', $n$Patients$n$, $n$Recherchez un patient par nom, téléphone ou code patient. Le téléphone est comparé sans espaces ni indicatif, donc « 0662… » et « +212 662… » retrouvent la même personne.
Avant de créer une fiche, vérifiez les doublons possibles : deux fiches du même patient se fusionnent depuis la liste des doublons, et la fusion déplace tout l'historique vers la fiche conservée.
Le consentement aux messages se règle par canal (WhatsApp, e-mail). Par défaut un patient n'a consenti à rien et aucun rappel ne lui est envoyé.$n$),
    (gen_random_uuid(), NULL, 'patients', 'en', $n$Patients$n$, $n$Search a patient by name, phone or patient code. Phone numbers are compared without spaces or country code, so "0662…" and "+212 662…" find the same person.
Before creating a file, check for possible duplicates: two files for the same patient are merged from the duplicates list, and the merge moves the whole history to the file that is kept.
Consent to messages is set per channel (WhatsApp, email). By default a patient has consented to nothing and no reminder is sent.$n$),
    (gen_random_uuid(), NULL, 'patients', 'ar', $n$المرضى$n$, $n$ابحث عن مريض بالاسم أو الهاتف أو رمز المريض. تتم مقارنة الهاتف دون فراغات أو مفتاح الدولة، فيجد « 0662… » و « 212+ 662… » نفس الشخص.
قبل إنشاء ملف تحقق من التكرارات المحتملة: يتم دمج ملفين لنفس المريض من لائحة التكرارات، ويحول الدمج كل السجل إلى الملف المحتفظ به.
يضبط القبول بالرسائل لكل قناة (واتساب، بريد إلكتروني). افتراضيا لم يوافق المريض على شيء ولا يرسل له أي تذكير.$n$),
    (gen_random_uuid(), NULL, 'billing', 'fr', $n$Facturation et encaissements$n$, $n$Chaque facture est attribuée à un praticien : celui que vous choisissez, sinon le praticien principal du patient, sinon le médecin connecté. Cette attribution détermine les rétrocessions.
Un encaissement (reçu) peut être réparti sur plusieurs factures ; ce qui n'est pas réparti reste en avoir et peut être appliqué plus tard. Un chèque postdaté reste En attente jusqu'à son encaissement ; un chèque rejeté annule son règlement.
Un reçu annulé n'est jamais supprimé : il reste visible, marqué annulé, avec son motif. La note d'honoraires se génère en PDF depuis la facture.$n$),
    (gen_random_uuid(), NULL, 'billing', 'en', $n$Billing and payments$n$, $n$Every invoice belongs to a practitioner: the one you choose, otherwise the patient's primary practitioner, otherwise the signed-in doctor. That attribution decides retrocessions.
A payment (receipt) can be split across several invoices; whatever is not allocated stays as credit and can be applied later. A post-dated cheque stays Pending until it is cashed; a rejected cheque reverses its payment.
A voided receipt is never deleted: it stays visible, marked void, with its reason. The fee note is generated as a PDF from the invoice.$n$),
    (gen_random_uuid(), NULL, 'billing', 'ar', $n$الفوترة والمقبوضات$n$, $n$كل فاتورة تنسب إلى طبيب: الذي تختاره، وإلا الطبيب الرئيسي للمريض، وإلا الطبيب المتصل. هذا الإسناد هو ما يحدد الاستردادات.
يمكن توزيع المقبوض (الوصل) على عدة فواتير؛ وما لم يوزع يبقى رصيدا يمكن تطبيقه لاحقا. يبقى الشيك المؤجل قيد الانتظار حتى صرفه؛ والشيك المرفوض يلغي تسديده.
الوصل الملغى لا يحذف أبدا: يبقى ظاهرا ومعلما بأنه ملغى مع سببه. تولد مذكرة الأتعاب بصيغة PDF من الفاتورة.$n$),
    (gen_random_uuid(), NULL, 'finance', 'fr', $n$Finance et clôture de caisse$n$, $n$Le tableau de bord compare la production (facturé) aux encaissements (reçus) et montre les dépenses, les rétrocessions et le résultat de la période.
La clôture de caisse compare, par mode de paiement, ce que les reçus du jour annoncent à ce que vous avez compté. Le montant attendu est calculé par le système, jamais saisi ; une journée clôturée est définitive.
Les dépenses se saisissent à la main, depuis une facture fournisseur validée, ou à la réception d'un travail de laboratoire. Une dépense récurrente génère une copie à chaque échéance.$n$),
    (gen_random_uuid(), NULL, 'finance', 'en', $n$Finance and cash closing$n$, $n$The dashboard compares production (invoiced) with collections (received) and shows expenses, retrocessions and the result for the period.
Cash closing compares, per payment method, what the day's receipts say with what you counted. The expected amount is calculated by the system, never typed in; a closed day is final.
Expenses are entered by hand, from a validated supplier invoice, or when a lab job is received. A recurring expense generates a copy at each due date.$n$),
    (gen_random_uuid(), NULL, 'finance', 'ar', $n$المالية وإقفال الصندوق$n$, $n$تقارن لوحة القيادة الإنتاج (المفوتر) بالمقبوضات (الأوصال) وتعرض المصاريف والاستردادات ونتيجة الفترة.
يقارن إقفال الصندوق، لكل وسيلة أداء، ما تقوله أوصال اليوم بما عددته. المبلغ المنتظر يحسبه النظام ولا يدخل يدويا؛ واليوم المقفل نهائي.
تدخل المصاريف يدويا أو من فاتورة مورد مصادق عليها أو عند استلام عمل من المختبر. المصروف المتكرر يولد نسخة عند كل استحقاق.$n$),
    (gen_random_uuid(), NULL, 'retrocessions', 'fr', $n$Rétrocessions$n$, $n$Une règle fixe comment un collaborateur est payé : un pourcentage de l'encaissé ou du produit, éventuellement un autre pourcentage par catégorie de soin, une déduction des frais de laboratoire et un montant fixe mensuel. Un changement de conditions est une nouvelle règle avec sa date d'effet.
La simulation calcule ce qui serait dû pour n'importe quelle période, filtrable par mode de paiement et statut de facture. Les factures sans praticien ne comptent pour personne : attribuez-les pour qu'elles comptent.
Valider crée un relevé figé, calculé par le serveur, avec son PDF ; il ne se modifie plus. On peut l'annuler tant qu'aucun versement n'est enregistré. Les avances déjà versées sont déduites, la plus ancienne d'abord.
Un praticien sans droit de gestion ne voit que ses propres chiffres.$n$),
    (gen_random_uuid(), NULL, 'retrocessions', 'en', $n$Retrocessions$n$, $n$A rule sets how a collaborator is paid: a percentage of what was collected or produced, optionally a different percentage per treatment category, a deduction for lab fees and a fixed monthly amount. A change of terms is a new rule with its own effective date.
The simulation works out what would be owed for any period, filterable by payment method and invoice status. Invoices with no practitioner count for nobody: assign them so they count.
Validating creates a frozen statement, calculated by the server, with its PDF; it can no longer be edited. It can be voided as long as no payout has been recorded. Advances already paid are deducted, oldest first.
A practitioner without management rights sees only their own figures.$n$),
    (gen_random_uuid(), NULL, 'retrocessions', 'ar', $n$الاستردادات$n$, $n$تحدد القاعدة كيف يؤدى للمتعاون: نسبة من المقبوض أو من المنتج، وربما نسبة مختلفة لكل فئة علاج، وخصم لمصاريف المختبر ومبلغ ثابت شهري. تغيير الشروط هو قاعدة جديدة بتاريخ سريان خاص بها.
تحسب المحاكاة ما سيكون مستحقا لأي فترة، مع إمكانية التصفية حسب وسيلة الأداء وحالة الفاتورة. الفواتير بلا طبيب لا تحتسب لأحد: أسندها لكي تحتسب.
تؤدي المصادقة إلى كشف مجمد يحسبه الخادم مع ملف PDF، ولا يمكن تعديله بعد ذلك. يمكن إلغاؤه ما لم يسجل أي أداء. تخصم الدفعات المقدمة، الأقدم أولا.
الطبيب بلا صلاحية التسيير لا يرى إلا أرقامه الخاصة.$n$),
    (gen_random_uuid(), NULL, 'analytics', 'fr', $n$Analyses$n$, $n$Activité par acte : séances, chiffre d'affaires, coût des matières et marge par acte, catégorie et praticien, avec export Excel.
Temps médecin : durée moyenne et médiane au fauteuil, par praticien et par acte, de Installé à Terminé. Les rendez-vous sans heure de fin, ou dont la durée est irréaliste, sont exclus et comptés dans le panneau Qualité des données : corrigez-les pour obtenir des chiffres fiables.
Compte de produits et charges (CPC) : par jour, semaine, mois ou année, sur base encaissée (par défaut) ou facturée, avec ou sans les rétrocessions simulées. Les dotations, les éléments financiers et l'impôt ne sont pas suivis.
Objectifs : l'assistant calcule le chiffre d'affaires mensuel à atteindre pour couvrir les charges fixes et vos besoins personnels après les coûts variables, puis suit chaque mois.$n$),
    (gen_random_uuid(), NULL, 'analytics', 'en', $n$Analytics$n$, $n$Procedure activity: sessions, revenue, material cost and margin by procedure, category and practitioner, with Excel export.
Doctor time: average and median chair time by practitioner and procedure, from Seated to Finished. Appointments with no end time, or an implausible duration, are left out and counted in the Data quality panel: fix them to get reliable figures.
Income statement (CPC): by day, week, month or year, on a collected basis (default) or invoiced basis, with or without the simulated retrocessions. Depreciation, financial items and tax are not tracked.
Goals: the wizard works out the monthly revenue needed to cover fixed costs and your personal needs after variable costs, then tracks each month.$n$),
    (gen_random_uuid(), NULL, 'analytics', 'ar', $n$التحليلات$n$, $n$نشاط الإجراءات: الحصص ورقم المعاملات وكلفة المواد والهامش حسب الإجراء والفئة والطبيب، مع تصدير إلى إكسل.
وقت الطبيب: المدة المتوسطة والوسيطة على الكرسي حسب الطبيب والإجراء، من جلس إلى انتهى. تستبعد المواعيد بلا وقت نهاية أو بمدة غير معقولة وتحصى في لوحة جودة البيانات: صححها لتحصل على أرقام موثوقة.
حساب المنتوجات والتكاليف (CPC): حسب اليوم أو الأسبوع أو الشهر أو السنة، على أساس المقبوض (افتراضيا) أو المفوتر، مع الاستردادات المحاكاة أو بدونها. الاستهلاكات والعناصر المالية والضريبة غير متتبعة.
الأهداف: يحسب المعالج رقم المعاملات الشهري اللازم لتغطية التكاليف الثابتة وحاجياتك الشخصية بعد التكاليف المتغيرة، ثم يتابع كل شهر.$n$),
    (gen_random_uuid(), NULL, 'lab-orders', 'fr', $n$Travaux de laboratoire$n$, $n$Un bon de laboratoire suit un travail (gouttière, contention, couronne…) : Envoyé, En cours, Reçu, Posé, ou À refaire. Le bon se génère en PDF pour accompagner la pièce.
La réception du travail enregistre automatiquement le coût du laboratoire en dépense, une seule fois, et prévient l'équipe pour confirmer la pose. Ce coût peut aussi être déduit des rétrocessions du praticien concerné si sa règle le prévoit.$n$),
    (gen_random_uuid(), NULL, 'lab-orders', 'en', $n$Lab orders$n$, $n$A lab order tracks a piece of work (aligner, retainer, crown…): Sent, In progress, Received, Fitted or Remake. The order is generated as a PDF to go with the piece.
Receiving the work automatically records the lab's cost as an expense, once, and tells the team so the fitting can be confirmed. That cost can also be deducted from the practitioner's retrocessions if their rule says so.$n$),
    (gen_random_uuid(), NULL, 'lab-orders', 'ar', $n$أعمال المختبر$n$, $n$يتتبع أمر المختبر عملا (جبيرة، مثبت، تاج…): أرسل، قيد الإنجاز، استلم، ركب أو إعادة. يولد الأمر بصيغة PDF لمرافقة القطعة.
يسجل استلام العمل تلقائيا كلفة المختبر كمصروف مرة واحدة ويخبر الفريق لتأكيد التركيب. يمكن أيضا خصم هذه الكلفة من استردادات الطبيب المعني إذا نصت قاعدته على ذلك.$n$),
    (gen_random_uuid(), NULL, 'stock', 'fr', $n$Stock$n$, $n$Le stock affiche les alertes de stock bas et de péremption proche, avec l'historique complet des mouvements. Les commandes fournisseurs, bons de livraison et factures fournisseurs alimentent le stock et les dépenses.
Pour un inventaire, ouvrez une session de comptage et scannez le code-barres de chaque article avec l'appareil photo : le code correspond à la référence (SKU) de l'article.$n$),
    (gen_random_uuid(), NULL, 'stock', 'en', $n$Stock$n$, $n$Stock shows low-stock and near-expiry alerts, with the full history of movements. Purchase orders, delivery notes and supplier invoices feed stock and expenses.
For a stock count, open a count session and scan each item's barcode with the camera: the code is the item's reference (SKU).$n$),
    (gen_random_uuid(), NULL, 'stock', 'ar', $n$المخزون$n$, $n$يعرض المخزون تنبيهات نقص المخزون وقرب انتهاء الصلاحية مع السجل الكامل للحركات. تغذي طلبيات الموردين وسندات التسليم وفواتير الموردين المخزون والمصاريف.
للجرد افتح جلسة عد وامسح الرمز الشريطي لكل مادة بالكاميرا: الرمز هو مرجع المادة (SKU).$n$),
    (gen_random_uuid(), NULL, 'sterilization', 'fr', $n$Stérilisation$n$, $n$Chaque plateau, instrument, pièce à main ou kit d'endodontie a une étiquette QR qui ne contient aucune donnée patient. Imprimez les étiquettes depuis la liste du matériel.
Le cycle est : Prêt, Utilisé, À nettoyer, Traité, puis Prêt de nouveau. Un nouveau matériel est considéré comme sale tant qu'un cycle n'a pas été validé : seul un matériel Prêt peut servir sur un patient. À l'utilisation, le patient et le rendez-vous sont enregistrés pour la traçabilité.
Un cycle d'autoclave enregistre la machine, le numéro de cycle, le programme et l'opérateur. Un contrôle Réussi libère le matériel ; un contrôle Échoué le remet à retraiter et ouvre l'onglet Exposition, qui liste les patients sur lesquels ce matériel a servi depuis.
Pièces à main : lubrifiez après le nettoyage et avant l'autoclave. Fichiers d'endodontie : chaque fichier a un nombre d'utilisations maximal ; un kit contenant un fichier arrivé à sa limite ne peut plus servir tant que ce fichier n'est pas jeté et remplacé.$n$),
    (gen_random_uuid(), NULL, 'sterilization', 'en', $n$Sterilization$n$, $n$Every tray, instrument, handpiece or endo kit has a QR label that carries no patient data. Print labels from the equipment list.
The cycle is: Ready, Used, Dirty, Processed, then Ready again. New equipment counts as dirty until a cycle has been validated: only Ready equipment may be used on a patient. When it is used, the patient and appointment are recorded for traceability.
An autoclave cycle records the machine, cycle number, program and operator. A Passed control releases the equipment; a Failed control sends it back for reprocessing and opens the Exposure tab, which lists the patients the equipment has been used on since.
Handpieces: lubricate after cleaning and before the autoclave. Endo files: each file has a maximum number of uses; a kit holding a file at its limit cannot be used until that file is discarded and replaced.$n$),
    (gen_random_uuid(), NULL, 'sterilization', 'ar', $n$التعقيم$n$, $n$لكل صينية أو أداة أو قبضة أو علبة علاج العصب ملصق QR لا يحمل أي معطيات عن المريض. اطبع الملصقات من لائحة المعدات.
الدورة هي: جاهز، مستعمل، متسخ، معالج، ثم جاهز من جديد. تعتبر المعدة الجديدة متسخة إلى أن تصادق دورة عليها: لا تستعمل على المريض إلا معدة جاهزة. عند الاستعمال يسجل المريض والموعد من أجل التتبع.
تسجل دورة الأوتوكلاف الجهاز ورقم الدورة والبرنامج والمشغل. المراقبة الناجحة تحرر المعدة؛ والمراقبة الفاشلة تعيدها للمعالجة وتفتح تبويب التعرض الذي يعرض المرضى الذين استعملت عليهم المعدة منذ ذلك الحين.
القبضات: زيتها بعد التنظيف وقبل الأوتوكلاف. مبارد العصب: لكل مبرد عدد أقصى من الاستعمالات؛ ولا تستعمل علبة تحوي مبردا بلغ حده حتى يرمى ويستبدل.$n$),
    (gen_random_uuid(), NULL, 'settings', 'fr', $n$Paramètres$n$, $n$Le profil du cabinet (raison sociale, ICE, IF, patente, RIB, adresse, logo) est enregistré sur le serveur et figure sur tous les documents imprimés, depuis n'importe quel poste.
Les horaires d'ouverture par jour pilotent l'agenda et la réservation en ligne. Les permissions se règlent par rôle : un assistant ne voit pas les totaux financiers par défaut.
Les messages (rappels, enquêtes) ne partent que si le canal est activé et que le patient y a consenti. La réservation en ligne et l'assistant d'aide sont désactivés par défaut.$n$),
    (gen_random_uuid(), NULL, 'settings', 'en', $n$Settings$n$, $n$The practice profile (legal name, ICE, IF, patente, RIB, address, logo) is stored on the server and appears on every printed document, from any workstation.
Opening hours per day drive the agenda and online booking. Permissions are set per role: an assistant does not see financial totals by default.
Messages (reminders, surveys) are only sent if the channel is enabled and the patient has consented. Online booking and the help assistant are off by default.$n$),
    (gen_random_uuid(), NULL, 'settings', 'ar', $n$الإعدادات$n$, $n$يحفظ ملف العيادة (الاسم القانوني، ICE، IF، الرخصة، RIB، العنوان، الشعار) على الخادم ويظهر في كل وثيقة مطبوعة من أي محطة عمل.
تحدد ساعات العمل لكل يوم الأجندة والحجز عبر الإنترنت. تضبط الصلاحيات حسب الدور: لا يرى المساعد المجاميع المالية افتراضيا.
لا ترسل الرسائل (التذكيرات، الاستبيانات) إلا إذا فعلت القناة ووافق المريض. الحجز عبر الإنترنت ومساعد المساعدة معطلان افتراضيا.$n$);
