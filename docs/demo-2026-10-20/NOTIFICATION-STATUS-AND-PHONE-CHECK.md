# SMS notification backend and physical check

The backend already implements opt-in subscriptions, automated smoke/board notifications, checked wording or fixed-template fallback, recipient selection, rate/daily limits, an HTTP Android SMS gateway adapter, masked public records and contact purge. It is not an autonomous AI sending arbitrary messages. Human-only decisions remain human.

Verified backend evidence: the prior integrated1419-test run included123 alert tests in12 suites, including15 adapter tests,5 real-adapter-flow tests using a LOCAL MOCK gateway and the gateway-off refusal test. The current final-source full build records refreshed counts separately. The real HTTP rehearsal produced a LOG_ONLY board notification suppressed as NO_SUBSCRIBERS. No physical SMS has been sent or received as evidence.

## Sending and receiving phones

The implemented gateway runs on one Android phone with an active SMS-capable SIM, connected to the host on local Wi-Fi. It sends ordinary carrier SMS. The current subscription validator accepts Indian (+91) mobile numbers. A consenting recipient may use Android or iPhone and receives it in their usual messaging app; the recipient does not need the gateway app or stall Wi-Fi to receive carrier SMS. This is not iMessage, WhatsApp or mobile push. It uses the sending SIM's SMS service.

Official integration source, checked20 October2026: https://github.com/capcom6/android-sms-gateway#local-server . Its Local Server API matches the implemented POST /message, Basic authentication and textMessage/phoneNumbers payload. Phone app setup needs its SEND_SMS permission and displayed Local Server address/credentials. No third-party cloud account is needed for this local mode.

## Current behavior and limits

Default SMS mode is LOG_ONLY. No SMS credentials or REAL gateway configuration were enabled by this work. The configured adapter retries failed requests twice with5-second per-attempt timeouts. The default daily cap is80, and SMOKE_WARNING is limited to one per area per10 wall minutes. Consent and area selection are required; full numbers stay in the contact table and are purged, never exported or given to the wording model.

The optional external wording model is not configured in the verified demo. Checked templates therefore work without a live LLM. AI wording, if enabled later, may only reword approved facts and must pass the existing checks.

A gateway2xx acknowledgment is treated as accepted/SENT in the present backend record. It does NOT prove carrier delivery or reading by the recipient. Phone verification must include observing the actual incoming SMS; delivery/read webhooks are not implemented by this adapter.

## Physical steps when an Android gateway is available

1. On the sending Android, install the official gateway app, grant SEND_SMS, turn on Local Server and connect it to the same local Wi-Fi as the host.
2. Keep its URL/username/password local. Enter them in the ignored SMS configuration using the committed sms.example.json shape; do not paste credentials or phone numbers into chat or commit them. Leave LOG_ONLY until a deliberate phone test is requested.
3. A test recipient opts in through /alerts/ with consent and an area. Use the presenter TEST command only for the deliberate send. Then verify the actual text in the recipient's normal messaging app, once on Android and once on iPhone if both are available.
4. Record only masked recipient labels, notification/receipt IDs, gateway mode and observed-receipt result. A SENT backend record alone is insufficient. Return to LOG_ONLY after the test and purge the test contacts.

Windows pack verification is independent of SMS. Use the refreshed qualification02 bundle when Lane A reports it ready; the qualification01 bundle is superseded. The remaining phone/device details depend on which hardware the owner has available. The owner has now authorized one test to their offered number. No actual send has been attempted; gateway setup and recipient registration remain for the owner-requested steps at the end. The full number is not stored in these artifacts.
