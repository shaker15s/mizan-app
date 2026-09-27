package app.mizan.service

import app.mizan.domain.org.MemberStatus
import app.mizan.domain.org.Membership
import app.mizan.domain.org.OrgPlan
import app.mizan.domain.org.OrgResult
import app.mizan.domain.org.OrganizationBook
import app.mizan.domain.org.Permission
import app.mizan.domain.policy.VersionedPolicy
import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.text
import app.mizan.service.protocol.MizanContract
import app.mizan.service.store.InMemoryRecordLog
import app.mizan.service.store.OrganizationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/**
 * The enterprise surface: organizations, members, roles, permissions.
 *
 * The service shipped four accounts in one tenant, and everything a person was
 * allowed to do was decided by asking their role. That works for a demo and
 * fails for a company, so there is now a vocabulary of permissions, roles that
 * bundle them, and memberships that grant the roles -- all enforced by the
 * routes rather than by the client, and all recorded in the audit chain.
 *
 * What is checked here is the part that has to be true before any of it is
 * useful: a permission the caller does not hold is refused, no one can hand
 * out a permission they do not hold themselves, the last administrator of an
 * organization cannot lock everyone out, another tenant's organization is
 * invisible, and none of it survives a restart by accident.
 */
class AdminApiTest {

    // ---------------------------------------------------------------- model

    private fun member(
        orgId: String,
        actorId: String,
        email: String,
        roles: List<String>,
        status: MemberStatus = MemberStatus.ACTIVE,
    ) = Membership(
        orgId = orgId,
        actorId = actorId,
        email = email,
        displayName = actorId,
        roleNames = roles,
        status = status,
        addedAtMillis = 0L,
        addedBy = "test",
    )

    @Test
    fun aMemberOnlyHoldsWhatTheirRolesGrant() {
        val book = OrganizationBook(now = { 1_000L })
        book.create("org-1", "Al-Amal", owner = member("org-1", "USR-1", "admin@alamal.test", listOf("ORG_ADMIN")))
        book.invite(member("org-1", "USR-2", "rep@alamal.test", listOf("SALES_REP")), invitedBy = "admin@alamal.test")
        book.activate("org-1", "USR-2")

        assertTrue(book.allows("org-1", "USR-2", Permission.OPERATIONS_READ))
        assertFalse("a rep does not approve", book.allows("org-1", "USR-2", Permission.APPROVAL_DECIDE))
        assertFalse("a rep does not manage members", book.allows("org-1", "USR-2", Permission.MEMBERS_WRITE))
        assertTrue(book.allows("org-1", "USR-1", Permission.POLICY_WRITE))
    }

    @Test
    fun anInvitedMemberHoldsNothingUntilTheyAccept() {
        val book = OrganizationBook(now = { 1_000L })
        book.create("org-1", "Al-Amal", owner = member("org-1", "USR-1", "admin@alamal.test", listOf("ORG_ADMIN")))
        val invited = book.invite(
            member("org-1", "USR-3", "new@alamal.test", listOf("SALES_MANAGER"), status = MemberStatus.INVITED),
            invitedBy = "admin@alamal.test",
        )
        assertTrue(invited is OrgResult.Ok)
        // The invitation names a role; the account is not live until it is
        // accepted. Otherwise an invite is a credential nobody controls.
        assertTrue(book.permissionsOf("org-1", "USR-3").isEmpty())
        book.activate("org-1", "USR-3")
        assertTrue(book.allows("org-1", "USR-3", Permission.APPROVAL_DECIDE))
    }

    @Test
    fun aSuspendedMemberHoldsNothingEvenWithTheirRoles() {
        val book = OrganizationBook(now = { 1_000L })
        book.create("org-1", "Al-Amal", owner = member("org-1", "USR-1", "admin@alamal.test", listOf("ORG_ADMIN")))
        book.invite(member("org-1", "USR-2", "rep@alamal.test", listOf("FINANCE_APPROVER")), "admin@alamal.test")
        book.activate("org-1", "USR-2")
        assertTrue(book.allows("org-1", "USR-2", Permission.POLICY_READ))
        book.suspend("org-1", "USR-2")
        assertTrue("a suspended member holds nothing", book.permissionsOf("org-1", "USR-2").isEmpty())
        // The roles are still on the record: suspension is reversible, and
        // erasing the grant would make the audit trail lie about it.
        assertEquals(listOf("FINANCE_APPROVER"), book.member("org-1", "USR-2")?.roleNames)
    }

    @Test
    fun theLastAdministratorCannotBeSuspendedRemovedOrDemoted() {
        val book = OrganizationBook(now = { 1_000L })
        book.create("org-1", "Al-Amal", owner = member("org-1", "USR-1", "admin@alamal.test", listOf("ORG_ADMIN")))

        assertTrue(book.suspend("org-1", "USR-1") is OrgResult.Refused)
        assertTrue(book.remove("org-1", "USR-1") is OrgResult.Refused)
        // With a second administrator the first may leave, which is the
        // difference between a rule and a deadlock.
        book.invite(member("org-1", "USR-2", "second@alamal.test", listOf("ORG_ADMIN")), "admin@alamal.test")
        book.activate("org-1", "USR-2")
        assertEquals(2, book.administrators("org-1").size)
        val removed = book.remove("org-1", "USR-1")
        assertTrue(removed is OrgResult.Ok)
        assertEquals("USR-2", book.administrators("org-1").single().actorId)
        assertTrue("the last one is still protected", book.remove("org-1", "USR-2") is OrgResult.Refused)
    }

    @Test
    fun anInviterCannotGrantAPermissionTheyDoNotHold() {
        val book = OrganizationBook(now = { 1_000L })
        book.create("org-1", "Al-Amal", owner = member("org-1", "USR-1", "admin@alamal.test", listOf("ORG_ADMIN")))
        book.invite(member("org-1", "USR-M", "mgr@alamal.test", listOf("SALES_MANAGER")), "admin@alamal.test")
        book.activate("org-1", "USR-M")

        val escalation = book.invite(
            member("org-1", "USR-X", "friend@alamal.test", listOf("ORG_ADMIN")),
            invitedBy = "mgr@alamal.test",
        )
        assertTrue(escalation is OrgResult.Refused)
        assertEquals("ROLE_ESCALATION", (escalation as OrgResult.Refused).code)
        // The same manager can invite the role they do hold, so the rule
        // blocks escalation and not invitations.
        assertTrue(
            book.invite(
                member("org-1", "USR-R", "rep@alamal.test", listOf("SALES_REP")),
                invitedBy = "mgr@alamal.test",
            ) is OrgResult.Ok,
        )
    }

    @Test
    fun aRoleThatDoesNotExistGrantsNothing() {
        val book = OrganizationBook(now = { 1_000L })
        book.create("org-1", "Al-Amal", owner = member("org-1", "USR-1", "admin@alamal.test", listOf("ORG_ADMIN")))
        val invented = book.invite(
            member("org-1", "USR-9", "ghost@alamal.test", listOf("SUPERUSER")),
            invitedBy = "admin@alamal.test",
        )
        assertTrue(invented is OrgResult.Refused)
        assertEquals("ROLE_UNKNOWN", (invented as OrgResult.Refused).code)
    }

    @Test
    fun aSuspendedOrganizationGrantsNothingToAnyone() {
        val book = OrganizationBook(now = { 1_000L })
        book.create("org-1", "Al-Amal", owner = member("org-1", "USR-1", "admin@alamal.test", listOf("ORG_ADMIN")))
        assertTrue(book.allows("org-1", "USR-1", Permission.MEMBERS_WRITE))
        book.suspendOrganization("org-1")
        assertTrue("a suspended tenant is closed, not read-only", book.permissionsOf("org-1", "USR-1").isEmpty())
        book.resumeOrganization("org-1")
        assertTrue(book.allows("org-1", "USR-1", Permission.MEMBERS_WRITE))
    }

    @Test
    fun anSsoDomainResolvesToItsOrganizationAndNothingElseDoes() {
        val book = OrganizationBook(now = { 1_000L })
        book.create("org-1", "Al-Amal", owner = member("org-1", "USR-1", "admin@alamal.test", listOf("ORG_ADMIN")))
        book.setSsoDomains("org-1", listOf("Alamal.test", "not a domain", "@bad.test"))
        assertEquals("org-1", book.ssoOrganizationFor("someone@alamal.test")?.id)
        assertEquals("an unrelated personal address is nobody's", null, book.ssoOrganizationFor("someone@gmail.com"))
        // The cleaning is part of the rule: a domain that is not a domain is
        // dropped rather than stored, because a stored one becomes a claim.
        assertEquals(listOf("alamal.test"), book.get("org-1")?.ssoDomains)
        book.suspendOrganization("org-1")
        assertEquals("a suspended organization claims no domains", null, book.ssoOrganizationFor("someone@alamal.test"))
    }

    @Test
    fun aCustomRoleIsAPermissionBundleAndTheBuiltInsRefuseToBeEdited() {
        val book = OrganizationBook(now = { 1_000L })
        val created = book.defineRole("BRANCH_LEAD", setOf(Permission.OPERATIONS_READ, Permission.APPROVAL_DECIDE))
        assertTrue(created is OrgResult.Ok)
        val updated = book.updateRole("branch_lead", setOf(Permission.OPERATIONS_READ, Permission.AUDIT_READ))
        assertEquals(
            setOf(Permission.OPERATIONS_READ, Permission.AUDIT_READ),
            (updated as OrgResult.Ok).value.permissions,
        )
        // The built-ins are the vocabulary the authority already speaks; a
        // deployment can add to it and cannot redefine what AUDITOR means.
        assertTrue(book.updateRole("AUDITOR", setOf(Permission.POLICY_WRITE)) is OrgResult.Refused)
        assertTrue(book.removeRole("SALES_REP") is OrgResult.Refused)
    }

    // ------------------------------------------------------------- durability

    @Test
    fun theOrganizationOutlivesTheProcessThatCreatedIt() {
        val log = InMemoryRecordLog()
        OrganizationStore(log, clock = { 1_000L }).let { first ->
            first.create(
                id = "org-alamal",
                label = "Al-Amal Trading",
                plan = OrgPlan.ENTERPRISE,
                ownerActorId = "USR-1",
                ownerEmail = "admin@alamal.test",
                ownerName = "Admin",
            )
            first.invite(member("org-alamal", "USR-2", "rep@alamal.test", listOf("SALES_REP")), "admin@alamal.test")
            first.setSsoDomains("org-alamal", listOf("alamal.test"))
        }
        // A new store over the same log is a restarted process.
        val restarted = OrganizationStore(log, clock = { 2_000L })
        assertEquals("Al-Amal Trading", restarted.book.get("org-alamal")?.label)
        assertEquals(2, restarted.book.membersOf("org-alamal").size)
        assertEquals(listOf("alamal.test"), restarted.book.get("org-alamal")?.ssoDomains)
        assertTrue(restarted.book.allows("org-alamal", "USR-1", Permission.MEMBERS_WRITE))
        assertFalse(restarted.book.allows("org-alamal", "USR-2", Permission.MEMBERS_WRITE))
    }

    @Test
    fun aRemovedMemberDoesNotComeBackOnRestart() {
        val log = InMemoryRecordLog()
        OrganizationStore(log, clock = { 1_000L }).let { first ->
            first.create("org-1", "Al-Amal", OrgPlan.STANDARD, "USR-1", "admin@alamal.test", "Admin")
            first.invite(member("org-1", "USR-2", "rep@alamal.test", listOf("SALES_REP")), "admin@alamal.test")
            assertTrue(first.remove("org-1", "USR-2") is OrgResult.Ok)
        }
        val restarted = OrganizationStore(log, clock = { 2_000L })
        assertEquals(null, restarted.book.member("org-1", "USR-2"))
        // The replay is chronological, so the removal wins over the invite
        // that came before it. A log replayed as a set of last-writes is how
        // a removed account comes back from the dead.
        assertTrue(restarted.book.membersOf("org-1").none { it.actorId == "USR-2" })
    }

    @Test
    fun aRoleChangeSurvivesARestartAndSoDoesItsRefusalState() {
        val log = InMemoryRecordLog()
        OrganizationStore(log, clock = { 1_000L }).let { first ->
            first.create("org-1", "Al-Amal", OrgPlan.STANDARD, "USR-1", "admin@alamal.test", "Admin")
            first.invite(member("org-1", "USR-2", "mgr@alamal.test", listOf("SALES_MANAGER")), "admin@alamal.test")
            first.activate("org-1", "USR-2")
            assertTrue(first.defineRole("BRANCH_LEAD", setOf(Permission.AUDIT_READ)) is OrgResult.Ok)
            assertTrue(first.setRoles("org-1", "USR-2", listOf("BRANCH_LEAD")) is OrgResult.Ok)
        }
        val restarted = OrganizationStore(log, clock = { 2_000L })
        assertNotNull(restarted.book.rolesOf("org-1").firstOrNull { it.name == "BRANCH_LEAD" })
        assertTrue(restarted.book.allows("org-1", "USR-2", Permission.AUDIT_READ))
        assertFalse(restarted.book.allows("org-1", "USR-2", Permission.APPROVAL_DECIDE))
    }

    // ------------------------------------------------------------------ HTTP

    class Reply(val status: Int, val body: String) {
        fun field(name: String): String? = Json.parseOrNull(body)?.asObject()?.text(name)
        fun number(name: String): Long? =
            (Json.parseOrNull(body)?.asObject()?.field(name) as? JsonValue.Num)?.raw?.toLongOrNull()
        fun bool(name: String): Boolean? =
            (Json.parseOrNull(body)?.asObject()?.field(name) as? JsonValue.Bool)?.value
        fun array(name: String): List<JsonValue> =
            (Json.parseOrNull(body)?.asObject()?.field(name) as? JsonValue.Arr)?.items ?: emptyList()
        fun names(name: String): List<String> = array(name).mapNotNull { (it as? JsonValue.Str)?.value }
        fun rows(name: String): List<JsonValue.Obj> = array(name).mapNotNull { it.asObject() }
    }

    /**
     * The routes over one running service.
     *
     * It is the real service on a real socket: an authorization rule that
     * exists only in a unit test of the model is a rule that no request
     * obeys.
     */
    class Http2 {
        private val client = HttpClient.newHttpClient()
        private lateinit var service: MizanService
        private lateinit var base: String
        private lateinit var directory: java.nio.file.Path

        fun start() {
            directory = Files.createTempDirectory("mizan-admin-")
            directory.toFile().deleteOnExit()
            service = MizanService(
                ServiceConfig(
                    storeDirectory = directory,
                    signingSecret = "admin-api-test-key",
                    versionedPolicy = VersionedPolicy.demoV12,
                    // A real key ring, so the audit rows written by the admin
                    // routes are sealed the way a deployment signs them.
                    receiptKeyPair = app.mizan.domain.receipt.AuthorityKeyPair.generate("admin-test-key"),
                ),
            )
            val port = service.start(port = 0)
            base = "http://127.0.0.1:$port"
        }

        fun stop() = service.stop()

        fun token(email: String, password: String): String {
            val reply = send("/v1/sessions", "POST", """{"email":"$email","password":"$password"}""")
            assertEquals(reply.body, 200, reply.status)
            return reply.field("token") ?: error("no token: ${reply.body}")
        }

        fun send(path: String, method: String, body: String? = null, token: String? = null): Reply {
            val builder = HttpRequest.newBuilder(URI(base + path))
            token?.let { builder.header(MizanContract.HEADER_AUTHORIZATION, "Bearer $it") }
            if (body != null) {
                builder.header("Content-Type", "application/json")
                builder.method(method, HttpRequest.BodyPublishers.ofString(body))
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            return Reply(response.statusCode(), response.body())
        }
    }

    @Test
    fun theAdminRoutesRefuseAPermissionTheCallerDoesNotHold() {
        val http = Http2()
        http.start()
        try {
            val rep = http.token("rep@mizan.test", "rep-demo-password")
            val manager = http.token("manager@mizan.test", "manager-demo-password")
            val auditor = http.token("auditor@mizan.test", "auditor-demo-password")
            val admin = http.token("finance@mizan.test", "finance-demo-password")

            // A rep may not read the member list, a manager may, and only a
            // caller with the write permission may invite.
            assertEquals(403, http.send("/v1/admin/members", "GET", token = rep).status)
            assertEquals(200, http.send("/v1/admin/members", "GET", token = manager).status)
            assertEquals("no session is 401, not 403", 401, http.send("/v1/admin/members", "GET").status)
            assertEquals("an auditor reads, and does not write", 403, http.send(
                "/v1/admin/members",
                "POST",
                """{"email":"someone@alamal.test","roles":["SALES_REP"]}""",
                auditor,
            ).status)
            assertEquals(
                "PERMISSION_DENIED",
                http.send("/v1/admin/members", "POST", """{"email":"x@y.test"}""", rep).field("messageCode"),
            )
            assertEquals(200, http.send("/v1/admin/permissions", "GET", token = auditor).status)
            assertEquals(
                "the permission vocabulary is the code's",
                Permission.entries.size,
                http.send("/v1/admin/permissions", "GET", token = admin).array("permissions").size,
            )
        } finally {
            http.stop()
        }
    }

    @Test
    fun anInvitationThroughTheApiIsRecordedInTheTrail() {
        val http = Http2()
        http.start()
        try {
            val finance = http.token("finance@mizan.test", "finance-demo-password")
            val invited = http.send(
                "/v1/admin/members",
                "POST",
                """{"email":"lead@alamal.test","displayName":"Branch Lead","roles":["BRANCH_LEAD"]}""",
                finance,
            )
            // The role does not exist yet, so the invitation is refused by
            // name rather than by accident.
            assertEquals(409, invited.status)
            assertEquals("ROLE_UNKNOWN", invited.field("messageCode"))

            val created = http.send(
                "/v1/admin/roles",
                "POST",
                """{"name":"BRANCH_LEAD","permissions":["OPERATIONS_READ","AUDIT_READ"]}""",
                finance,
            )
            assertEquals(201, created.status)
            assertEquals("BRANCH_LEAD", created.field("name"))

            val second = http.send(
                "/v1/admin/members",
                "POST",
                """{"email":"lead@alamal.test","displayName":"Branch Lead","roles":["BRANCH_LEAD"]}""",
                finance,
            )
            assertEquals(second.body, 201, second.status)
            assertEquals("INVITED", second.field("status"))

            // It appears in the member list with the permissions of its role,
            // and the administration act is in the audit chain.
            val listed = http.send("/v1/admin/members", "GET", token = finance)
            val emails = listed.rows("members").mapNotNull { it.text("email") }
            assertTrue("the invited member is listed: $emails", emails.contains("lead@alamal.test"))
            val trail = http.send("/v1/audit", "GET", token = finance)
            val actions = trail.rows("events").mapNotNull { it.text("action") }
            assertTrue("the invitation is in the trail: $actions", actions.contains("admin.member.invited"))
        } finally {
            http.stop()
        }
    }

    @Test
    fun anotherOrganizationsRecordIsRefusedBeforeAnyReadHappens() {
        val http = Http2()
        http.start()
        try {
            val finance = http.token("finance@mizan.test", "finance-demo-password")
            val mine = http.send("/v1/admin/organizations/sim-alamal", "GET", token = finance)
            assertEquals(mine.body, 200, mine.status)
            assertEquals("sim-alamal", mine.field("tenantId"))

            val theirs = http.send("/v1/admin/organizations/sim-other", "GET", token = finance)
            assertEquals(403, theirs.status)
            assertEquals("TENANT_MISMATCH", theirs.field("messageCode"))
            // The refusal is recorded too: an attempt to read another
            // tenant's organization is exactly what an audit trail is for.
            val trail = http.send("/v1/audit", "GET", token = finance)
            val actions = trail.rows("events").mapNotNull { it.text("action") }
            assertTrue("the attempt is in the trail: $actions", actions.contains("admin.tenant.denied"))
        } finally {
            http.stop()
        }
    }

    @Test
    fun anOrganizationCanBeCreatedThroughTheApi() {
        val http = Http2()
        http.start()
        try {
            val finance = http.token("finance@mizan.test", "finance-demo-password")
            val created = http.send(
                "/v1/admin/organizations",
                "POST",
                """{"id":"org-nile","label":"Nile Foods","plan":"ENTERPRISE"}""",
                finance,
            )
            assertEquals(created.body, 201, created.status)
            assertEquals("ENTERPRISE", created.field("plan"))
            assertEquals("the creator administers it", 1L, created.number("administrators"))
            assertEquals(1L, created.number("members"))

        } finally {
            http.stop()
        }
    }

    @Test
    fun ssoDomainsMapToTheCallersOwnOrganizationAndNowhereElse() {
        val http = Http2()
        http.start()
        try {
            val finance = http.token("finance@mizan.test", "finance-demo-password")
            // Domain configuration is about the caller's own organization:
            // there is no route that points another tenant at a domain.
            val domains = http.send(
                "/v1/admin/organization/sso",
                "POST",
                """{"domains":["Alamal.test","bad","@nope.test"]}""",
                finance,
            )
            assertEquals(domains.body, 200, domains.status)
            // Stored lowercased and cleaned, so "Alamal.Test" in a token and
            // "alamal.test" here are the same rule, and a fragment that is
            // not a domain is dropped rather than stored.
            assertEquals(listOf("alamal.test"), domains.names("domains"))

            val resolved = http.send(
                "/v1/admin/sso/resolve",
                "POST",
                """{"email":"someone@alamal.test"}""",
                finance,
            )
            assertEquals("sim-alamal", resolved.field("tenantId"))
            assertTrue(resolved.bool("resolved") == true)

            val unknown = http.send(
                "/v1/admin/sso/resolve",
                "POST",
                """{"email":"someone@gmail.com"}""",
                finance,
            )
            assertEquals(null, unknown.field("tenantId"))
            assertFalse(unknown.bool("resolved") == true)
        } finally {
            http.stop()
        }
    }

    @Test
    fun theApiRefusesToHandOutAPermissionTheCallerLacks() {
        val http = Http2()
        http.start()
        try {
            val finance = http.token("finance@mizan.test", "finance-demo-password")
            val manager = http.token("manager@mizan.test", "manager-demo-password")

            // The escalation rule is about what the caller holds *now*, so
            // the manager is first given a bundle that can write members but
            // not touch policy -- then asks for the policy permission.
            val branchAdmin = http.send(
                "/v1/admin/roles",
                "POST",
                """{"name":"BRANCH_ADMIN","permissions":["MEMBERS_READ","MEMBERS_WRITE"]}""",
                finance,
            )
            assertEquals(branchAdmin.body, 201, branchAdmin.status)
            val assigned = http.send(
                "/v1/admin/members/USR-MGR/roles",
                "POST",
                """{"roles":["BRANCH_ADMIN"]}""",
                finance,
            )
            assertEquals(assigned.body, 200, assigned.status)

            val escalation = http.send(
                "/v1/admin/roles",
                "POST",
                """{"name":"POLICY_EDITOR","permissions":["POLICY_WRITE"]}""",
                manager,
            )
            assertEquals(escalation.body, 403, escalation.status)
            assertEquals("ROLE_ESCALATION", escalation.field("messageCode"))

            // And a role that names something that is not a permission is
            // refused rather than silently granted as written.
            val nonsense = http.send(
                "/v1/admin/roles",
                "POST",
                """{"name":"SUPERUSER","permissions":["EVERYTHING"]}""",
                finance,
            )
            assertEquals(422, nonsense.status)
            assertEquals("ROLE_UNREADABLE", nonsense.field("messageCode"))
        } finally {
            http.stop()
        }
    }

    @Test
    fun aRestartedServiceStillHonoursTheOrganizationsItStored() {
        val directory = Files.createTempDirectory("mizan-admin-restart-")
        directory.toFile().deleteOnExit()
        val config = ServiceConfig(
            storeDirectory = directory,
            signingSecret = "admin-restart-key",
            versionedPolicy = VersionedPolicy.demoV12,
        )
        val first = MizanService(config)
        first.start(port = 0)
        try {
            val finance = first.sessions
            assertNotNull(finance)
            val created = first.organizations.create(
                id = "org-nile",
                label = "Nile Foods",
                plan = OrgPlan.ENTERPRISE,
                ownerActorId = "USR-FIN",
                ownerEmail = "finance@mizan.test",
                ownerName = "Noha Adel",
            )
            assertTrue(created is OrgResult.Ok)
            first.organizations.setSsoDomains("org-nile", listOf("nile.test"))
        } finally {
            first.stop()
        }
        val second = MizanService(config)
        second.start(port = 0)
        try {
            assertEquals("Nile Foods", second.organizations.book.get("org-nile")?.label)
            assertEquals("org-nile", second.organizations.book.ssoOrganizationFor("a@nile.test")?.id)
        } finally {
            second.stop()
        }
    }
}
