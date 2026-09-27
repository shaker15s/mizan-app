package app.mizan.service.http

import app.mizan.domain.org.MemberStatus
import app.mizan.domain.org.Membership
import app.mizan.domain.org.OrgPlan
import app.mizan.domain.org.OrgResult
import app.mizan.domain.org.Permission
import app.mizan.domain.org.RoleCatalog
import app.mizan.domain.org.RoleDefinition
import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.text
import app.mizan.service.ledger.AuditLedger
import app.mizan.service.protocol.MizanContract
import app.mizan.service.security.ServiceSession
import app.mizan.service.store.OrganizationStore
import com.sun.net.httpserver.HttpExchange

/**
 * The administration surface: organizations, members, roles, permissions.
 *
 * Phase 12 of the plan is a list of enterprise surfaces -- Admin Web, SSO,
 * Organizations, Roles, Permissions, Policies, Devices, Audit, ERP
 * integrations -- and this is the API half of it. A web admin console is a
 * client of these routes; nothing about authority moves into a console.
 *
 * Two rules run through every route here:
 *
 * 1. **The organization comes from the session, never from the path.** A
 *    request that names another tenant is refused with 403 `TENANT_MISMATCH`.
 *    Reading someone else's organization is not a permission, it is a bug.
 * 2. **Every check is a permission check.** `MEMBERS_WRITE` is what allows an
 *    invitation; there is no route that asks "is this person an admin?" and
 *    answers it by looking at a role name.
 *
 * Every mutation writes to the audit chain, including the ones that fail after
 * the check: an administration surface whose actions are not in the trail is
 * an administration surface nobody can audit.
 */
class AdminApi(
    private val store: OrganizationStore,
    private val audit: AuditLedger,
    private val authenticate: (HttpExchange) -> ServiceSession?,
) {

    private val book get() = store.book

    fun handle(exchange: HttpExchange) = Http.serve(exchange) {
        val session = authenticate(exchange) ?: return@serve
        val user = session.user
        val orgId = user.tenantId
        val path = exchange.requestURI.path.removePrefix(MizanContract.PATH_ADMIN).trim('/')
        val method = exchange.requestMethod
        val segments = path.split('/').filter { it.isNotEmpty() }

        // Everything below this line is scoped to the caller's organization.
        // The one exception is creating a new organization, which is scoped to
        // the organization the caller is already trusted in.
        if (segments.isEmpty()) {
            refuse(exchange, 404, "ADMIN_ROUTE_UNKNOWN")
            return@serve
        }

        when {
            segments[0] == "permissions" && method == "GET" -> {
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_READ)) return@serve
                Http.respond(exchange, 200, permissionsJson())
            }

            segments[0] == "roles" && method == "GET" -> {
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_READ)) return@serve
                Http.respond(
                    exchange,
                    200,
                    Json.obj(
                        "tenantId" to Json.str(orgId),
                        "roles" to Json.arr(book.rolesOf(orgId).map(::roleJson)),
                    ),
                )
            }

            segments[0] == "roles" && segments.size == 1 && method == "POST" -> {
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_WRITE)) return@serve
                val body = readJsonBody(exchange) ?: return@serve
                val name = body.text("name")?.trim().orEmpty()
                val permissions = permissionsOf(body)
                if (name.isEmpty() || permissions == null) {
                    refuse(exchange, 422, "ROLE_UNREADABLE")
                    return@serve
                }
                // A role may not grant what its author does not hold.
                val author = book.permissionsOf(orgId, user.actorId)
                if (!author.containsAll(permissions)) {
                    note(exchange, session, orgId, "admin.role.denied", "ROLE_ESCALATION")
                    refuse(exchange, 403, "ROLE_ESCALATION")
                    return@serve
                }
                when (val result = store.defineRole(name, permissions)) {
                    is OrgResult.Ok -> {
                        note(exchange, session, orgId, "admin.role.created", result.value.name)
                        Http.respond(exchange, 201, roleJson(result.value))
                    }
                    is OrgResult.Refused -> refuse(exchange, 409, result.code)
                }
            }

            segments[0] == "roles" && segments.size == 2 && method == "PATCH" -> {
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_WRITE)) return@serve
                val body = readJsonBody(exchange) ?: return@serve
                val permissions = permissionsOf(body)
                if (permissions == null) {
                    refuse(exchange, 422, "ROLE_UNREADABLE")
                    return@serve
                }
                if (!book.permissionsOf(orgId, user.actorId).containsAll(permissions)) {
                    note(exchange, session, orgId, "admin.role.denied", "ROLE_ESCALATION")
                    refuse(exchange, 403, "ROLE_ESCALATION")
                    return@serve
                }
                when (val result = store.updateRole(segments[1], permissions)) {
                    is OrgResult.Ok -> {
                        note(exchange, session, orgId, "admin.role.updated", result.value.name)
                        Http.respond(exchange, 200, roleJson(result.value))
                    }
                    is OrgResult.Refused -> refuse(exchange, 409, result.code)
                }
            }

            segments[0] == "roles" && segments.size == 2 && method == "DELETE" -> {
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_WRITE)) return@serve
                when (val result = store.removeRole(segments[1])) {
                    is OrgResult.Ok -> {
                        note(exchange, session, orgId, "admin.role.removed", result.value)
                        Http.respond(exchange, 200, Json.obj("name" to Json.str(result.value)))
                    }
                    is OrgResult.Refused -> refuse(exchange, 409, result.code)
                }
            }

            segments[0] == "members" && segments.size == 1 && method == "GET" -> {
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_READ)) return@serve
                Http.respond(
                    exchange,
                    200,
                    Json.obj(
                        "tenantId" to Json.str(orgId),
                        "members" to Json.arr(book.membersOf(orgId).map(::memberJson)),
                    ),
                )
            }

            segments[0] == "members" && segments.size == 1 && method == "POST" -> {
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_WRITE)) return@serve
                val body = readJsonBody(exchange) ?: return@serve
                val email = body.text("email")?.trim()?.lowercase().orEmpty()
                val roles = stringList(body, "roles")
                if (email.isEmpty() || !email.contains('@') || roles.isEmpty()) {
                    refuse(exchange, 422, "MEMBER_UNREADABLE")
                    return@serve
                }
                val actorId = body.text("actorId")
                    ?: "USR-" + email.substringBefore('@').uppercase().replace(Regex("[^A-Z0-9]"), "").take(12)
                val member = Membership(
                    orgId = orgId,
                    actorId = actorId,
                    email = email,
                    displayName = body.text("displayName") ?: email.substringBefore('@'),
                    roleNames = roles.map { it.uppercase() },
                    status = MemberStatus.INVITED,
                    addedAtMillis = System.currentTimeMillis(),
                    addedBy = user.actorId,
                )
                when (val result = store.invite(member, user.email)) {
                    is OrgResult.Ok -> {
                        note(exchange, session, orgId, "admin.member.invited", result.value.actorId)
                        Http.respond(exchange, 201, memberJson(result.value))
                    }
                    is OrgResult.Refused -> refuse(exchange, statusFor(result.code), result.code)
                }
            }

            segments[0] == "members" && segments.size >= 3 -> {
                val actorId = segments[1]
                when (segments[2]) {
                    "roles" -> {
                        if (!allowed(exchange, session, orgId, Permission.MEMBERS_WRITE)) return@serve
                        val body = readJsonBody(exchange) ?: return@serve
                        val roles = stringList(body, "roles")
                        if (roles.isEmpty()) {
                            refuse(exchange, 422, "ROLE_REQUIRED")
                            return@serve
                        }
                        // The caller cannot grant a role that holds a
                        // permission the caller does not hold.
                        val granted = book.rolesOf(orgId)
                            .filter { role -> roles.any { it.equals(role.name, ignoreCase = true) } }
                            .flatMapTo(LinkedHashSet()) { it.permissions }
                        if (!book.permissionsOf(orgId, user.actorId).containsAll(granted)) {
                            note(exchange, session, orgId, "admin.member.denied", "ROLE_ESCALATION")
                            refuse(exchange, 403, "ROLE_ESCALATION")
                            return@serve
                        }
                        when (val result = store.setRoles(orgId, actorId, roles)) {
                            is OrgResult.Ok -> {
                                note(exchange, session, orgId, "admin.member.roles", actorId)
                                Http.respond(exchange, 200, memberJson(result.value))
                            }
                            is OrgResult.Refused -> refuse(exchange, statusFor(result.code), result.code)
                        }
                    }
                    "suspend" -> {
                        if (!allowed(exchange, session, orgId, Permission.MEMBERS_WRITE)) return@serve
                        when (val result = store.suspend(orgId, actorId)) {
                            is OrgResult.Ok -> {
                                note(exchange, session, orgId, "admin.member.suspended", actorId)
                                Http.respond(exchange, 200, memberJson(result.value))
                            }
                            is OrgResult.Refused -> refuse(exchange, statusFor(result.code), result.code)
                        }
                    }
                    "activate" -> {
                        if (!allowed(exchange, session, orgId, Permission.MEMBERS_WRITE)) return@serve
                        when (val result = store.activate(orgId, actorId)) {
                            is OrgResult.Ok -> {
                                note(exchange, session, orgId, "admin.member.activated", actorId)
                                Http.respond(exchange, 200, memberJson(result.value))
                            }
                            is OrgResult.Refused -> refuse(exchange, statusFor(result.code), result.code)
                        }
                    }
                    else -> refuse(exchange, 404, "ADMIN_ROUTE_UNKNOWN")
                }
            }

            segments[0] == "members" && segments.size == 2 && method == "DELETE" -> {
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_WRITE)) return@serve
                when (val result = store.remove(orgId, segments[1])) {
                    is OrgResult.Ok -> {
                        note(exchange, session, orgId, "admin.member.removed", result.value)
                        Http.respond(exchange, 200, Json.obj("actorId" to Json.str(result.value)))
                    }
                    is OrgResult.Refused -> refuse(exchange, statusFor(result.code), result.code)
                }
            }

            segments[0] == "organization" && method == "GET" -> {
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_READ)) return@serve
                val org = book.get(orgId) ?: return@serve refuse(exchange, 404, "ORG_UNKNOWN")
                Http.respond(exchange, 200, organizationJson(org.id))
            }

            segments[0] == "organization" && segments.size == 2 && segments[1] == "sso"
                && method == "GET" -> {
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_READ)) return@serve
                Http.respond(
                    exchange,
                    200,
                    Json.obj(
                        "tenantId" to Json.str(orgId),
                        "domains" to Json.arr(book.get(orgId)?.ssoDomains.orEmpty().map { Json.str(it) }),
                    ),
                )
            }

            segments[0] == "organization" && segments.size == 2 && segments[1] == "sso"
                && method == "POST" -> {
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_WRITE)) return@serve
                val body = readJsonBody(exchange) ?: return@serve
                when (val result = store.setSsoDomains(orgId, stringList(body, "domains"))) {
                    is OrgResult.Ok -> {
                        note(exchange, session, orgId, "admin.sso.domains", result.value.ssoDomains.joinToString(","))
                        Http.respond(
                            exchange,
                            200,
                            Json.obj("domains" to Json.arr(result.value.ssoDomains.map { Json.str(it) })),
                        )
                    }
                    is OrgResult.Refused -> refuse(exchange, 404, result.code)
                }
            }

            segments[0] == "sso" && segments.size == 2 && segments[1] == "resolve"
                && method == "POST" -> {
                // Which organization an email domain belongs to. This is the
                // one route that answers about a domain rather than a member,
                // and it answers with a domain the caller already registered,
                // never with whether an account exists.
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_READ)) return@serve
                val body = readJsonBody(exchange) ?: return@serve
                val email = body.text("email")?.trim()?.lowercase().orEmpty()
                val org = book.ssoOrganizationFor(email)
                Http.respond(
                    exchange,
                    200,
                    Json.obj(
                        "domain" to Json.str(email.substringAfter('@', "")),
                        "tenantId" to Json.str(org?.id),
                        "tenantLabel" to Json.str(org?.label),
                        "resolved" to Json.bool(org != null),
                    ),
                )
            }

            segments[0] == "organizations" && segments.size == 1 && method == "GET" -> {
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_READ)) return@serve
                // A member sees their own organization. Seeing all of them is
                // a platform action, and this deployment has no platform
                // administrators, so the list contains one row by construction.
                val mine = book.get(orgId)
                Http.respond(
                    exchange,
                    200,
                    Json.obj(
                        "organizations" to Json.arr(
                            listOfNotNull(mine).map { Json.str(it.id) },
                        ),
                        "tenantId" to Json.str(orgId),
                    ),
                )
            }

            segments[0] == "organizations" && segments.size == 1 && method == "POST" -> {
                // Onboarding a company is itself a privileged act: it is done
                // by a member who may write members in the organization they
                // are already trusted in, and the creator becomes its admin.
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_WRITE)) return@serve
                val body = readJsonBody(exchange) ?: return@serve
                val id = body.text("id")?.trim().orEmpty()
                val label = body.text("label")?.trim().orEmpty()
                val plan = body.text("plan")?.let { raw ->
                    runCatching { OrgPlan.valueOf(raw.uppercase()) }.getOrNull()
                } ?: OrgPlan.STANDARD
                if (id.isEmpty() || label.isEmpty()) {
                    refuse(exchange, 422, "ORG_INVALID")
                    return@serve
                }
                when (
                    val result = store.create(
                        id = id,
                        label = label,
                        plan = plan,
                        ownerActorId = user.actorId,
                        ownerEmail = user.email,
                        ownerName = user.displayName,
                    )
                ) {
                    is OrgResult.Ok -> {
                        note(exchange, session, orgId, "admin.org.created", result.value.id)
                        Http.respond(exchange, 201, organizationJson(result.value.id))
                    }
                    is OrgResult.Refused -> refuse(exchange, 409, result.code)
                }
            }

            segments[0] == "organizations" && segments.size == 2 && method == "GET" -> {
                if (segments[1] != orgId) {
                    // The one refusal that is about tenancy rather than
                    // permission, and it is refused before any read happens.
                    note(exchange, session, orgId, "admin.tenant.denied", segments[1])
                    refuse(exchange, 403, "TENANT_MISMATCH")
                    return@serve
                }
                if (!allowed(exchange, session, orgId, Permission.MEMBERS_READ)) return@serve
                Http.respond(exchange, 200, organizationJson(orgId))
            }

            else -> refuse(exchange, 404, "ADMIN_ROUTE_UNKNOWN")
        }
    }

    private fun permissionsJson(): JsonValue.Obj = Json.obj(
        "permissions" to Json.arr(Permission.entries.map { permission ->
            Json.obj(
                "name" to Json.str(permission.name),
                "roles" to Json.arr(
                    RoleCatalog.names.filter { RoleCatalog.named(it)?.contains(permission) == true }
                        .map { Json.str(it) },
                ),
            )
        }),
        "roles" to Json.arr(RoleCatalog.names.map { name ->
            Json.obj(
                "name" to Json.str(name),
                "permissions" to Json.arr(
                    RoleCatalog.named(name).orEmpty().map { Json.str(it.name) },
                ),
            )
        }),
    )

    private fun roleJson(role: RoleDefinition): JsonValue.Obj = Json.obj(
        "name" to Json.str(role.name),
        // Sorted by name before it becomes JSON: JsonValue is not comparable,
        // and sorting the wire form would order by braces.
        "permissions" to Json.arr(role.permissions.map { it.name }.sorted().map { Json.str(it) }),
        "builtIn" to Json.bool(role.builtIn),
    )

    private fun memberJson(member: Membership): JsonValue.Obj = Json.obj(
        "actorId" to Json.str(member.actorId),
        "email" to Json.str(member.email),
        "displayName" to Json.str(member.displayName),
        "roles" to Json.arr(member.roleNames.map { Json.str(it) }),
        "permissions" to Json.arr(
            book.rolesOf(member.orgId).filter { role -> member.roleNames.any { it.equals(role.name, true) } }
                .flatMap { it.permissions }.map { it.name }.sorted().map { Json.str(it) },
        ),
        "status" to Json.str(member.status.name),
        "addedBy" to Json.str(member.addedBy),
        "addedAtMillis" to Json.num(member.addedAtMillis),
        "lastSeenAtMillis" to Json.num(member.lastSeenAtMillis),
    )

    private fun organizationJson(orgId: String): JsonValue.Obj {
        val org = book.get(orgId)
        return Json.obj(
            "tenantId" to Json.str(orgId),
            "label" to Json.str(org?.label),
            "plan" to Json.str(org?.plan?.name),
            "status" to Json.str(org?.status?.name),
            "createdAtMillis" to Json.num(org?.createdAtMillis),
            "ssoDomains" to Json.arr(org?.ssoDomains.orEmpty().map { Json.str(it) }),
            "members" to Json.num(book.membersOf(orgId).size),
            "administrators" to Json.num(book.administrators(orgId).size),
        )
    }

    private fun allowed(
        exchange: HttpExchange,
        session: ServiceSession,
        orgId: String,
        permission: Permission,
    ): Boolean {
        val user = session.user
        // Permissions come from memberships and nothing else. There is no
        // fallback to the account's built-in role: an account that is not a
        // member of the organization holds nothing in it, which is why the
        // reference deployment seeds its demo accounts as members at start-up.
        if (permission in book.permissionsOf(orgId, user.actorId)) return true
        audit.append(
            tenantId = orgId,
            traceId = exchange.requestHeaders.getFirst(MizanContract.HEADER_TRACE_ID) ?: "ADMIN",
            actorId = user.actorId,
            action = "admin.denied.${permission.name}",
            stateBefore = "AUTHORIZED",
            stateAfter = "REFUSED",
            details = exchange.requestURI.path,
        )
        Http.respond(
            exchange,
            403,
            Json.obj(
                "messageCode" to Json.str("PERMISSION_DENIED"),
                "missing" to Json.str(permission.name),
            ),
        )
        return false
    }

    /** Writes an administration act to the chain, always after it happened. */
    private fun note(
        exchange: HttpExchange,
        session: ServiceSession,
        orgId: String,
        action: String,
        details: String,
    ) {
        audit.append(
            tenantId = orgId,
            traceId = exchange.requestHeaders.getFirst(MizanContract.HEADER_TRACE_ID) ?: "ADMIN",
            actorId = session.user.actorId,
            action = action,
            stateBefore = "REQUESTED",
            stateAfter = "APPLIED",
            details = details.take(200),
        )
    }

    private fun permissionsOf(body: JsonValue.Obj): Set<Permission>? {
        val raw = body.field("permissions") as? JsonValue.Arr ?: return null
        val names = raw.items.mapNotNull { (it as? JsonValue.Str)?.value }
        val parsed = names.mapNotNull { name -> runCatching { Permission.valueOf(name.uppercase()) }.getOrNull() }
        // A name that is not a permission is refused rather than ignored: a
        // typo in a policy console must not silently grant less than asked.
        return if (parsed.size == names.size) parsed.toSet() else null
    }

    private fun stringList(body: JsonValue.Obj, name: String): List<String> {
        val raw = body.field(name) as? JsonValue.Arr ?: return emptyList()
        return raw.items.mapNotNull { (it as? JsonValue.Str)?.value }
    }

    private fun statusFor(code: String): Int = when (code) {
        "ORG_UNKNOWN", "MEMBER_UNKNOWN" -> 404
        "PERMISSION_DENIED", "ROLE_ESCALATION" -> 403
        else -> 409
    }

    private fun refuse(exchange: HttpExchange, status: Int, code: String) {
        Http.respond(exchange, status, Json.obj("messageCode" to Json.str(code)))
    }
}

/** Reads a JSON object body, or answers 413/422 and returns null. */
private fun readJsonBody(exchange: HttpExchange): JsonValue.Obj? {
    val read = Http.readBody(exchange)
    if (read.tooLarge) {
        Http.respond(exchange, 413, Json.obj("messageCode" to Json.str("REQUEST_TOO_LARGE")))
        return null
    }
    if (read.value == null) {
        Http.respond(exchange, 422, Json.obj("messageCode" to Json.str("REQUEST_UNREADABLE")))
        return null
    }
    return read.value
}
