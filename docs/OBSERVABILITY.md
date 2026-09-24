# Observability

What exists:

- `MizanLog` redacts and suppresses debug outside debug builds. It is an engineering log, not the audit chain.
- `StartupTrace` records elapsed time from process start to the first decor view post. Account can show that number. It is one sample on one device, not a benchmark, and it was not measured in this environment.
- Audit rows are the operational record: action, before, after, actor, tenant, trace. They are local.
- Execution phase is the state. The UI does not invent a parallel boolean.

What does not exist:

- No crash reporter.
- No analytics SDK.
- No trace export to the service.
- No fake latency, no fake success counter, no “100%” health.

Health on the home screen observes the network through `ConnectivityManager`. Backend, ERP, and sync stay unknown until a real check exists. A saved token is not treated as a healthy backend. Simulation ERP is degraded, not healthy.
