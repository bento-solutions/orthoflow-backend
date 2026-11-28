# orthoflow-backend

Spring Boot 3 (Java 21) REST API & scheduler powering Orthoflow (`apiortho.crmbento.com`) and patient booking integration for `ortho.crmbento.com`.

## Booking & WhatsApp Integration Architecture

```text
+--------------------+     HTTPS (Turnstile + E.164)     +------------------------------+
|  orthoflow-front   | --------------------------------> |      orthoflow-backend       |
|  ortho.crmbento    |   GET /public/config              |  Spring Boot 3 + PostgreSQL  |
|  (/book, /public)  |   GET /public/booking/availability|                              |
+--------------------+   POST /public/booking/appts      |  1. Match/create Patient     |
        |                                                |  2. Atomic Slot Appointment  |
        | Emergency CTA                                  |  3. Issue /public/register/  |
        | (tel: & wa.me URGENT)                          |     <token> (new patient UI) |
        v                                                |  4. Enqueue Outbox + Retries |
+--------------------+   POST /sessions/:id/messages     |  5. 20:00 Clinic-TZ Cron     |
| crm-whatsapp-bot   | <-------------------------------- |     + 1/2/3 Reply Listener   |
| (Single Baileys    |   (X-API-Key over crm-network)    |  6. URGENT Dashboard Alert   |
|  session on VPS)   | --------------------------------> +------------------------------+
+--------------------+   POST /api/v1/webhooks/whatsapp
                         (HMAC X-Bento-Signature)
```

- **Patient & calendar source of truth**: Orthoflow PostgreSQL tables (`patients`, `appointments`, `practitioners`, `practice_opening_hours`, `practitioner_absences`, `calendar_events`). Doctor target resolves by `ORTHOFLOW_BOOKING_DOCTOR_EMAIL` (`achraf.ouajid@bentosolutions.com`).
- **WhatsApp templates**: Editable plain-text FR/AR/EN templates live in `src/main/resources/whatsapp-templates.yml`.
- **Single Baileys session**: Never start a second Baileys socket with the same WhatsApp credentials; connect `orthoflow-backend` to the existing `crm-whatsapp-bot` HTTP bridge over `crm-backend_crm-network`.

## Local Setup & Tests

1. Copy `.env.example` values as needed. No real WhatsApp credentials are required for tests.
2. Start local test PostgreSQL (e.g., port `5544`, database `orthoflow_test`).
3. Run the full verification suite with JDK 21:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ORTHOFLOW_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5544/orthoflow_test \
ORTHOFLOW_TEST_DB_USER=postgres \
ORTHOFLOW_TEST_DB_PASSWORD=postgres \
mvn -B verify
```

## VPS Deploy Steps (`ortho.crmbento.com`)

1. Ensure the production backend `.env` on the VPS sets:
   - `ORTHOFLOW_BOOKING_DOCTOR_EMAIL=achraf.ouajid@bentosolutions.com`
   - `BAILEYS_PHONE_NUMBER=+212...` (the live Baileys international number)
   - `WHATSAPP_ENABLED=true`
   - `WHATSAPP_BASE_URL=http://crm-whatsapp-bot:3000`
   - `WHATSAPP_SESSION_ID=<live wa_account uuid>`
   - `WHATSAPP_API_KEY`, `WHATSAPP_WEBHOOK_SECRET` (shared HMAC secret with Baileys bridge)
   - `WHATSAPP_INBOUND_FROM=landing_page`
   - `CORS_ALLOWED_ORIGINS=https://ortho.crmbento.com`
   - `BOOKING_CAPTCHA_ENABLED=true`, `BOOKING_CAPTCHA_SITE_KEY`, `BOOKING_CAPTCHA_SECRET_KEY` (if Turnstile is enabled)
2. Attach the `orthoflow-backend` container in `/srv/bento/apps/ortho/docker-compose.yml` to the external Docker network `crm-backend_crm-network` so `http://crm-whatsapp-bot:3000` resolves without exposing Baileys publicly.
3. Point `crm-whatsapp-bot`'s `MIRROR_WEBHOOK_URL` (in `/srv/bento/apps/crm-backend`) to `http://orthoflow-backend:8080/api/v1/webhooks/whatsapp`, then reload the bridge and `ortho` stack (`docker compose up -d --build`).

## End-to-End Verification Checklist

1. **Availability**: `GET /api/v1/public/booking/availability?date=YYYY-MM-DD&days=14` returns only free slots respecting working hours, breaks, existing appointments, and absences for `achraf.ouajid@bentosolutions.com`.
2. **New patient booking**: Submit `/book` with `+212` phone -> verifies E.164 normalization, patient creation, atomic appointment creation (`409` on concurrent double book), personal `/public/register/<token>` CTA shown only once on success screen, and WhatsApp confirmation queued in `message_outbox`.
3. **Existing patient privacy**: Submit `/book` with the same phone again -> response omits `registrationUrl` (`existingPatient: true`), UI hides CTA and states link was sent via WhatsApp only.
4. **Emergency flow**: Tap red `"Contact us immediately"` -> opens `tel:<BAILEYS_PHONE_NUMBER>` and `wa.me/<digits>?text=URGENT...`; sending `URGENT - test` via Baileys webhook surfaces a red alert banner and `URGENT` inbox item in the dashboard without triggering reminder `1/2/3` parsing.
5. **20:00 reminders & replies**: Reminder job runs at `20:00` in `<CLINIC_TIMEZONE>`, includes the registration link only when `registration_completed_at IS NULL`, deduplicates via `reminder:<appointmentId>:<day>`, and handles `1` (confirmed on time), `2` (confirmed late + dashboard flag), and `3` (cancelled + dashboard flag).
