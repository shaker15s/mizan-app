# MIZAN

Android client for a governed agentic ERP. The phone prepares a request, shows the rule, and waits. It does not authorize an ERP write by itself.

- **Demo** (`app.mizan.demo`) is a labeled simulation. Record ids start with `SIM-`. Nothing in that flavor is an ERP record.
- **Staging and production** call a MIZAN service over HTTPS. If that URL is missing, writes are refused. The app does not talk to Odoo.

The audit of the previous prototype is `docs/ARCHITECTURE_AUDIT.md`. What this tree actually does is `docs/ENGINEERING_REPORT.md`.

This checkout has no Gradle wrapper and was not assembled here. A JDK 17 and Android SDK are required before `assemble` or tests can be claimed.
