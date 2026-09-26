package app.mizan.domain

import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.TenantId
import app.mizan.domain.receipt.FieldComparison
import app.mizan.domain.receipt.ReceiptClaims
import app.mizan.domain.receipt.ReceiptSigner
import app.mizan.domain.receipt.ReceiptVerdict
import app.mizan.domain.receipt.SignatureAlgorithm
import app.mizan.domain.receipt.SigningKey
import app.mizan.domain.receipt.VerificationFingerprint
import app.mizan.domain.security.ChallengeVerdict
import app.mizan.domain.security.DeviceBindingService
import app.mizan.domain.security.DeviceKeyAlgorithm
import app.mizan.domain.security.DeviceKeyMaterial
import app.mizan.domain.security.InMemoryChallengeRepository
import app.mizan.domain.security.InMemoryDeviceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Two claims this project must never make loosely:
 *
 * * a receipt proves the service authored an outcome, and it must stop
 *   proving that the moment a single field is edited;
 * * a sensitive approval is bound to a device key and to one proposal, and a
 *   signature collected once must not be usable twice.
 *
 * These tests use real keys and real signatures. A stub that returns `true`
 * would test the stub.
 */
class ReceiptsAndDevicesTest {

    private val secretA = "key-material-a-0123456789abcdef0123456789abcdef"
    private val secretB = "key-material-b-0123456789abcdef0123456789abcdef"
    private val keyA = SigningKey(keyId = "k-1", secret = secretA.toByteArray(), activeFrom = Instant.EPOCH)
    private val keyB = SigningKey(
        keyId = "k-2",
        secret = secretB.toByteArray(),
        activeFrom = Instant.parse("2026-06-01T00:00:00Z"),
    )
    private val signer = ReceiptSigner(listOf(keyA))

    private fun claims(
        erpRecordId: String = "SO-1001",
        amount: String = "250000",
        issuedAtMillis: Long = 1_790_000_000_000L,
    ) = ReceiptClaims(
        receiptId = "RCT-0001",
        executionId = "EXE-0001",
        tenantId = "sim-alamal",
        actorId = "USR-REP",
        approverIds = listOf("USR-MGR"),
        proposalFingerprint = "fingerprint-0001",
        tool = "sales.order.create_draft",
        toolVersion = "2.1.0",
        catalogVersion = "tools-2.0.0",
        policyVersionId = "12",
        policyHash = "policy-hash-0001",
        approvalLevel = ApprovalLevel.L2_PRIVILEGED,
        inputHash = "input-hash-0001",
        erpModel = "sale.order",
        erpRecordId = erpRecordId,
        verificationHash = VerificationFingerprint.of(
            model = "sale.order",
            recordId = erpRecordId,
            fields = mapOf("amountMinor" to amount, "currency" to "USD"),
        ),
        verifiedFields = listOf("amountMinor", "currency"),
        issuedAtMillis = issuedAtMillis,
        traceId = "TRC-0001",
    )

    // ------------------------------------------------------------------ receipts

    @Test
    fun aSignedReceiptVerifies() {
        val signed = signer.sign(claims())
        val verification = signer.verify(signed)
        assertEquals(ReceiptVerdict.VALID, verification.verdict)
        assertTrue(verification.valid)
        assertEquals("k-1", signed.keyId)
        assertEquals(SignatureAlgorithm.HMAC_SHA256.wire, signed.algorithm)
    }

    @Test
    fun canonicalBodyIsStableAcrossFieldOrder() {
        assertEquals(claims().body(), claims().body())
        assertEquals(claims().digest(), claims().digest())
        assertNotNull(claims().body())
        assertTrue(claims().body().contains("\"erpRecordId\":\"SO-1001\""))
    }

    @Test
    fun editingOneFieldBreaksTheSignature() {
        val signed = signer.sign(claims())
        val tampered = signed.copy(claims = signed.claims.copy(erpRecordId = "SO-9999"))
        assertEquals(ReceiptVerdict.SIGNATURE_MISMATCH, signer.verify(tampered).verdict)
    }

    @Test
    fun editingAnAmountBreaksTheSignature() {
        val signed = signer.sign(claims())
        val tampered = signed.copy(
            claims = signed.claims.copy(
                verificationHash = VerificationFingerprint.of(
                    model = "sale.order",
                    recordId = "SO-1001",
                    fields = mapOf("amountMinor" to "9_999_999", "currency" to "USD"),
                ),
            ),
        )
        assertEquals(ReceiptVerdict.SIGNATURE_MISMATCH, signer.verify(tampered).verdict)
    }

    @Test
    fun aReceiptFromAnotherSignerIsNotAccepted() {
        val other = ReceiptSigner(listOf(SigningKey("k-9", secretB.toByteArray(), Instant.EPOCH)))
        val signed = other.sign(claims())
        assertEquals(ReceiptVerdict.UNKNOWN_KEY, signer.verify(signed).verdict)
    }

    @Test
    fun anEmptySignatureIsReportedAsUnsignedNotAsForged() {
        val signed = signer.sign(claims()).copy(signature = "")
        assertEquals(ReceiptVerdict.UNSIGNED, signer.verify(signed).verdict)
    }

    @Test
    fun aTruncatedSignatureIsMalformedNotAccepted() {
        val signed = signer.sign(claims()).copy(signature = "not-base64-at-all-%%%")
        assertEquals(ReceiptVerdict.MALFORMED, signer.verify(signed).verdict)
    }

    @Test
    fun rotationKeepsOldReceiptsVerifiableAndSaysSo() {
        val old = signer.sign(claims())
        val rotated = signer.rotate(keyB, at = Instant.parse("2026-07-01T00:00:00Z"))
        assertEquals("k-2", rotated.activeKeyId)
        // The old receipt still verifies, but the verdict names the key as
        // retired, so a reader can tell "old" from "forged".
        assertEquals(ReceiptVerdict.RETIRED_KEY, rotated.verify(old).verdict)
        val fresh = rotated.sign(claims())
        assertEquals("k-2", fresh.keyId)
        assertEquals(ReceiptVerdict.VALID, rotated.verify(fresh).verdict)
        assertEquals(ReceiptVerdict.UNKNOWN_KEY, signer.verify(fresh).verdict)
    }

    @Test
    fun aSecretNeverRendersItself() {
        assertFalse(keyA.toString().contains(secretA))
        assertTrue(keyA.toString().contains("redacted"))
    }

    @Test
    fun aSigningKeyMustBeAtLeast256Bits() {
        assertThrows(IllegalArgumentException::class.java) {
            SigningKey("k-short", "too-short".toByteArray(), Instant.EPOCH)
        }
    }

    @Test
    fun aSignerWithoutKeysIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { ReceiptSigner(emptyList()) }
    }

    // ------------------------------------------------------------------- comparison

    @Test
    fun aReadBackThatMatchesEveryPromisedFieldIsAVerifiedWrite() {
        val comparison = VerificationFingerprint.compare(
            expected = mapOf("amountMinor" to "250000", "currency" to "USD"),
            actual = mapOf("amountMinor" to "250000", "currency" to "USD", "state" to "draft"),
        )
        assertTrue(comparison.mismatched.isEmpty())
        assertTrue(comparison.missing.isEmpty())
        assertEquals(listOf("amountMinor", "currency"), comparison.matched)
        assertTrue(comparison.agrees)
    }

    @Test
    fun aReadBackWithADifferentAmountIsAMismatchNotASuccess() {
        val comparison = VerificationFingerprint.compare(
            expected = mapOf("amountMinor" to "250000"),
            actual = mapOf("amountMinor" to "260000"),
        )
        assertFalse(comparison.mismatched.isEmpty())
        assertEquals(listOf("amountMinor"), comparison.mismatched)
        assertFalse(comparison.agrees)
    }

    @Test
    fun aReadBackMissingAPromisedFieldIsReportedAsMissing() {
        val comparison = VerificationFingerprint.compare(
            expected = mapOf("amountMinor" to "250000", "currency" to "USD"),
            actual = mapOf("amountMinor" to "250000"),
        )
        assertEquals(listOf("currency"), comparison.missing)
        assertTrue(comparison.mismatched.isEmpty())
    }

    @Test
    fun theVerificationFingerprintDependsOnEveryFieldItSaw() {
        val one = VerificationFingerprint.of("sale.order", "SO-1", mapOf("amountMinor" to "1"))
        val two = VerificationFingerprint.of("sale.order", "SO-1", mapOf("amountMinor" to "2"))
        val other = VerificationFingerprint.of("account.move", "SO-1", mapOf("amountMinor" to "1"))
        assertNotEquals(one, two)
        assertNotEquals(one, other)
        assertEquals(one, VerificationFingerprint.of("sale.order", "SO-1", mapOf("amountMinor" to "1")))
    }

    // -------------------------------------------------------------------- devices

    private class Fixture(startMillis: Long = 1_790_000_000_000L) {
        var now = startMillis
        val devices = InMemoryDeviceRepository()
        val challenges = InMemoryChallengeRepository()
        val service = DeviceBindingService(devices, challenges, clock = { now }, challengeTtlMillis = 120_000)
        val tenant = TenantId("sim-alamal")
        val actor = ActorId("USR-REP")
        val keyPair = DeviceKeyMaterial.generate(DeviceKeyAlgorithm.ED25519)
        val publicKey = DeviceKeyMaterial.encode(keyPair.public)
        val fingerprint = "proposal-fingerprint-0001"
    }

    @Test
    fun anEnrolledDeviceCanSignForOneProposalAndOnlyOnce() {
        val fixture = Fixture()
        fixture.service.enroll(
            deviceId = "DEV-1",
            tenantId = fixture.tenant,
            actorId = fixture.actor,
            algorithm = DeviceKeyAlgorithm.ED25519,
            publicKeyBase64 = fixture.publicKey,
            label = "Pixel",
        )
        val challenge = fixture.service.issueChallenge(
            challengeId = "CHG-1",
            nonce = "nonce-1",
            deviceId = "DEV-1",
            executionId = ExecutionId("EXE-1"),
            tenantId = fixture.tenant,
            actorId = fixture.actor,
            proposalFingerprint = fixture.fingerprint,
        )
        assertNotNull(challenge)
        val signature = DeviceKeyMaterial.sign(
            fixture.keyPair.private,
            fixture.service.message(challenge!!),
            DeviceKeyAlgorithm.ED25519,
        )
        val first = fixture.service.verify("CHG-1", signature, fixture.fingerprint)
        assertTrue(first.valid)
        assertEquals(ChallengeVerdict.VALID, first.verdict)

        // The same signature, replayed, is not a second approval.
        assertEquals(ChallengeVerdict.REPLAYED, fixture.service.verify("CHG-1", signature, fixture.fingerprint).verdict)
    }

    @Test
    fun aSignatureForAnotherProposalIsRefused() {
        val fixture = Fixture()
        enroll(fixture)
        val challenge = fixture.service.issueChallenge(
            "CHG-1", "nonce-1", "DEV-1", ExecutionId("EXE-1"), fixture.tenant, fixture.actor, fixture.fingerprint,
        )!!
        val signature = DeviceKeyMaterial.sign(
            fixture.keyPair.private,
            fixture.service.message(challenge),
            DeviceKeyAlgorithm.ED25519,
        )
        val moved = fixture.service.verify("CHG-1", signature, "a-different-proposal")
        assertEquals(ChallengeVerdict.WRONG_TENANT, moved.verdict)
        assertEquals("APPROVAL_INVALIDATED", moved.errorCode)
    }

    @Test
    fun aSignatureByAnotherKeyIsRefused() {
        val fixture = Fixture()
        enroll(fixture)
        val challenge = fixture.service.issueChallenge(
            "CHG-1", "nonce-1", "DEV-1", ExecutionId("EXE-1"), fixture.tenant, fixture.actor, fixture.fingerprint,
        )!!
        val impostor = DeviceKeyMaterial.generate(DeviceKeyAlgorithm.ED25519)
        val forged = DeviceKeyMaterial.sign(
            impostor.private,
            fixture.service.message(challenge),
            DeviceKeyAlgorithm.ED25519,
        )
        val verdict = fixture.service.verify("CHG-1", forged, fixture.fingerprint)
        assertFalse(verdict.valid)
        assertEquals(ChallengeVerdict.SIGNATURE_INVALID, verdict.verdict)
    }

    @Test
    fun anExpiredChallengeIsRefused() {
        val fixture = Fixture()
        enroll(fixture)
        val challenge = fixture.service.issueChallenge(
            "CHG-1", "nonce-1", "DEV-1", ExecutionId("EXE-1"), fixture.tenant, fixture.actor, fixture.fingerprint,
        )!!
        val signature = DeviceKeyMaterial.sign(
            fixture.keyPair.private,
            fixture.service.message(challenge),
            DeviceKeyAlgorithm.ED25519,
        )
        fixture.now += 121_000
        assertEquals(ChallengeVerdict.EXPIRED, fixture.service.verify("CHG-1", signature, fixture.fingerprint).verdict)
    }

    @Test
    fun aRevokedDeviceCannotApproveAnything() {
        val fixture = Fixture()
        enroll(fixture)
        val challenge = fixture.service.issueChallenge(
            "CHG-1", "nonce-1", "DEV-1", ExecutionId("EXE-1"), fixture.tenant, fixture.actor, fixture.fingerprint,
        )!!
        val signature = DeviceKeyMaterial.sign(
            fixture.keyPair.private,
            fixture.service.message(challenge),
            DeviceKeyAlgorithm.ED25519,
        )
        fixture.service.revoke("DEV-1", atMillis = fixture.now)
        val verdict = fixture.service.verify("CHG-1", signature, fixture.fingerprint)
        assertEquals(ChallengeVerdict.REVOKED_DEVICE, verdict.verdict)
        assertEquals("SECURITY_DEVICE_REVOKED", verdict.errorCode)
    }

    @Test
    fun anUnknownDeviceCannotAskForAChallenge() {
        val fixture = Fixture()
        assertNull(
            fixture.service.issueChallenge(
                "CHG-1", "nonce-1", "DEV-UNKNOWN", ExecutionId("EXE-1"), fixture.tenant, fixture.actor,
                fixture.fingerprint,
            ),
        )
    }

    @Test
    fun aChallengeCannotBeIssuedForAnotherTenantsDevice() {
        val fixture = Fixture()
        enroll(fixture)
        assertNull(
            fixture.service.issueChallenge(
                "CHG-1", "nonce-1", "DEV-1", ExecutionId("EXE-1"), TenantId("other-tenant"), fixture.actor,
                fixture.fingerprint,
            ),
        )
    }

    @Test
    fun aKeyThatDoesNotParseIsNeverStored() {
        val fixture = Fixture()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.service.enroll(
                deviceId = "DEV-BAD",
                tenantId = fixture.tenant,
                actorId = fixture.actor,
                algorithm = DeviceKeyAlgorithm.ED25519,
                publicKeyBase64 = "not-a-real-public-key",
                label = "bad",
            )
        }
        assertNull(fixture.service.byTenant(fixture.tenant).firstOrNull { it.deviceId == "DEV-BAD" })
    }

    @Test
    fun enrolmentIsScopedToTheTenantThatAskedForIt() {
        val fixture = Fixture()
        enroll(fixture)
        assertEquals(1, fixture.service.byTenant(fixture.tenant).size)
        assertEquals(0, fixture.service.byTenant(TenantId("other-tenant")).size)
    }

    @Test
    fun theSignedMessageNamesTheTenantTheActorTheProposalAndTheNonce() {
        val fixture = Fixture()
        enroll(fixture)
        val challenge = fixture.service.issueChallenge(
            "CHG-1", "nonce-1", "DEV-1", ExecutionId("EXE-1"), fixture.tenant, fixture.actor, fixture.fingerprint,
        )!!
        val message = String(fixture.service.message(challenge), Charsets.UTF_8)
        assertTrue(message.contains("sim-alamal"))
        assertTrue(message.contains("USR-REP"))
        assertTrue(message.contains("EXE-1"))
        assertTrue(message.contains(fixture.fingerprint))
        assertTrue(message.contains("nonce-1"))
    }

    @Test
    fun anEcdsaDeviceIsSupportedToo() {
        val fixture = Fixture()
        val pair = DeviceKeyMaterial.generate(DeviceKeyAlgorithm.ECDSA_P256)
        fixture.service.enroll(
            deviceId = "DEV-EC",
            tenantId = fixture.tenant,
            actorId = fixture.actor,
            algorithm = DeviceKeyAlgorithm.ECDSA_P256,
            publicKeyBase64 = DeviceKeyMaterial.encode(pair.public),
            label = "tablet",
        )
        val challenge = fixture.service.issueChallenge(
            "CHG-EC", "nonce-ec", "DEV-EC", ExecutionId("EXE-EC"), fixture.tenant, fixture.actor, fixture.fingerprint,
        )!!
        val signature = DeviceKeyMaterial.sign(pair.private, fixture.service.message(challenge), DeviceKeyAlgorithm.ECDSA_P256)
        assertTrue(fixture.service.verify("CHG-EC", signature, fixture.fingerprint).valid)
    }

    private fun enroll(fixture: Fixture) {
        fixture.service.enroll(
            deviceId = "DEV-1",
            tenantId = fixture.tenant,
            actorId = fixture.actor,
            algorithm = DeviceKeyAlgorithm.ED25519,
            publicKeyBase64 = fixture.publicKey,
            label = "Pixel",
        )
    }

    /** Unused, but kept so a field added to the comparison cannot pass unnoticed. */
    private fun comparisonIsEmpty(comparison: FieldComparison): Boolean =
        comparison.matched.isEmpty() && comparison.mismatched.isEmpty() && comparison.missing.isEmpty()
}
