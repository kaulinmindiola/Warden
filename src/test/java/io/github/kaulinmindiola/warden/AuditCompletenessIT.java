package io.github.kaulinmindiola.warden;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static io.github.kaulinmindiola.warden.support.IamTestClient.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.shared.audit.AuditAction;
import io.github.kaulinmindiola.warden.shared.audit.AuditOutcome;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Completeness of the audit log (REQ-SEC-015): each audited action, performed through the API,
 * produces exactly one record with its time, actor, workspace (when it applies), outcome and trace
 * id, and no record contains a password, a token or an e-mail address.
 *
 * <p>Each case uses fresh accounts, so the records of a case are found by action and actor. The
 * test clock stands still, so every record carries exactly its instant.
 *
 * <p>Template changes have no operation yet: workspace template customization arrives with release
 * 1.1 (REQ-FUNC-054, D-01). It is listed in {@link #ARRIVING_IN_PHASE_4}, and a test fails if an
 * action is neither covered here nor listed there.
 */
class AuditCompletenessIT extends IntegrationTest {

    /** Actions whose operations do not exist yet; phase 4 moves them into {@link #cases()}. */
    static final Set<AuditAction> ARRIVING_IN_PHASE_4 = EnumSet.of(AuditAction.TEMPLATE_CHANGED);

    private static final String WRONG_PASSWORD = "not the right password";
    private static final String NEW_PASSWORD = "a brand new correct horse";
    private static final String TRACE_ID = "[0-9a-f]{32}";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SecretCipher secretCipher;

    private IamTestClient iam;

    static Stream<AuditCase> cases() {
        return Stream.of(
                new AuditCase(AuditAction.LOGIN, AuditOutcome.SUCCESS, AuditCompletenessIT::loginSucceeds),
                new AuditCase(AuditAction.LOGIN, AuditOutcome.FAILURE, AuditCompletenessIT::loginFails),
                new AuditCase(
                        AuditAction.REFRESH_TOKEN_REUSE_DETECTED,
                        AuditOutcome.SUCCESS,
                        AuditCompletenessIT::refreshTokenIsReused),
                new AuditCase(AuditAction.LOGOUT, AuditOutcome.SUCCESS, AuditCompletenessIT::logout),
                new AuditCase(
                        AuditAction.SESSION_TERMINATED, AuditOutcome.SUCCESS, AuditCompletenessIT::terminateSession),
                new AuditCase(AuditAction.PASSWORD_RESET, AuditOutcome.SUCCESS, AuditCompletenessIT::resetPassword),
                new AuditCase(
                        AuditAction.WORKSPACE_CREATED, AuditOutcome.SUCCESS, AuditCompletenessIT::createWorkspace),
                new AuditCase(AuditAction.MEMBER_ROLE_CHANGED, AuditOutcome.SUCCESS, AuditCompletenessIT::changeRole),
                new AuditCase(AuditAction.MEMBER_REMOVED, AuditOutcome.SUCCESS, AuditCompletenessIT::removeMember),
                new AuditCase(AuditAction.MEMBER_LEFT, AuditOutcome.SUCCESS, AuditCompletenessIT::leaveWorkspace),
                new AuditCase(
                        AuditAction.INVITATION_CREATED, AuditOutcome.SUCCESS, AuditCompletenessIT::createInvitation),
                new AuditCase(
                        AuditAction.INVITATION_REVOKED, AuditOutcome.SUCCESS, AuditCompletenessIT::revokeInvitation),
                new AuditCase(
                        AuditAction.INVITATION_ACCEPTED, AuditOutcome.SUCCESS, AuditCompletenessIT::acceptInvitation),
                new AuditCase(
                        AuditAction.WORKSPACE_DELETED, AuditOutcome.SUCCESS, AuditCompletenessIT::deleteWorkspace),
                new AuditCase(AuditAction.DELIVERY_RETRIED, AuditOutcome.SUCCESS, AuditCompletenessIT::retryDelivery));
    }

    @BeforeEach
    void setUp() {
        iam = new IamTestClient(context);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @Verifies("REQ-SEC-015")
    void eachActionProducesExactlyOneCompleteRecordWithoutSecrets(AuditCase auditCase) throws Exception {
        Performed performed = auditCase.scenario().perform(this);

        List<AuditRecord> records = records(auditCase.action(), performed.actorId());

        assertThat(records).as("records of %s by its actor", auditCase).hasSize(1);
        AuditRecord record = records.getFirst();
        assertThat(record.occurredAt()).isEqualTo(clock.instant());
        assertThat(record.workspaceId()).isEqualTo(performed.workspaceId());
        assertThat(record.outcome()).isEqualTo(auditCase.outcome().name());
        assertThat(record.traceId()).matches(TRACE_ID);
        assertThat(record.json())
                .doesNotContain("@")
                .doesNotContain("wrt_")
                .doesNotContain("wot_")
                .doesNotContain("eyJ");
        for (String secret : performed.secrets()) {
            assertThat(record.json()).doesNotContain(secret);
        }
    }

    @Test
    @Verifies("REQ-SEC-015")
    void everyAuditedActionIsCoveredHereOrArrivesInPhase4() {
        Set<AuditAction> covered = cases().map(AuditCase::action)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(AuditAction.class)));

        assertThat(covered).doesNotContainAnyElementsOf(ARRIVING_IN_PHASE_4);
        Set<AuditAction> accountedFor = EnumSet.copyOf(covered);
        accountedFor.addAll(ARRIVING_IN_PHASE_4);
        assertThat(accountedFor).containsExactlyInAnyOrder(AuditAction.values());
    }

    // Scenarios: each performs one audited action through the API and returns who performed it.

    private Performed loginSucceeds() throws Exception {
        String email = iam.registerNewAccount();
        String accessToken = iam.login(email);
        return new Performed(iam.userIdOf(email), null, List.of(PASSWORD, accessToken));
    }

    private Performed loginFails() throws Exception {
        String email = iam.registerNewAccount();
        iam.postJson(API + "/auth/login", Map.of("email", email, "password", WRONG_PASSWORD), null)
                .andExpect(status().isUnauthorized());
        return new Performed(iam.userIdOf(email), null, List.of(WRONG_PASSWORD, PASSWORD));
    }

    private Performed refreshTokenIsReused() throws Exception {
        String email = iam.registerNewAccount();
        IamTestClient.Tokens tokens = iam.loginTokens(email);
        String rotated = JsonPath.read(
                iam.postJson(API + "/auth/refresh", Map.of("refresh_token", tokens.refreshToken()), null)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.refresh_token");
        iam.postJson(API + "/auth/refresh", Map.of("refresh_token", tokens.refreshToken()), null)
                .andExpect(status().isUnauthorized());
        return new Performed(
                iam.userIdOf(email), null, List.of(tokens.accessToken(), tokens.refreshToken(), rotated, PASSWORD));
    }

    private Performed logout() throws Exception {
        String email = iam.registerNewAccount();
        String accessToken = iam.login(email);
        iam.postJson(API + "/auth/logout", Map.of(), accessToken).andExpect(status().isNoContent());
        return new Performed(iam.userIdOf(email), null, List.of(accessToken));
    }

    private Performed terminateSession() throws Exception {
        String email = iam.registerNewAccount();
        String current = iam.login(email);
        String other = iam.login(email);
        iam.delete(API + "/auth/sessions/" + IamTestClient.sessionIdOf(other), current)
                .andExpect(status().is2xxSuccessful());
        return new Performed(iam.userIdOf(email), null, List.of(current, other));
    }

    private Performed resetPassword() throws Exception {
        String email = iam.registerNewAccount();
        UUID userId = iam.userIdOf(email);
        iam.postJson(API + "/auth/password/forgot", Map.of("email", email), null)
                .andExpect(status().isAccepted());
        String resetToken = iam.latestPasswordResetToken(userId);
        iam.postJson(API + "/auth/password/reset", Map.of("token", resetToken, "newPassword", NEW_PASSWORD), null)
                .andExpect(status().isNoContent());
        return new Performed(userId, null, List.of(resetToken, NEW_PASSWORD, PASSWORD));
    }

    private Performed createWorkspace() throws Exception {
        Owner owner = newOwner();
        return new Performed(owner.userId(), owner.workspaceId(), List.of(owner.accessToken()));
    }

    private Performed changeRole() throws Exception {
        Owner owner = newOwner();
        UUID member = addMember(owner.workspaceId(), iam.registerNewAccount());
        iam.mvc()
                .perform(MockMvcRequestBuilders.put(
                                API + "/workspaces/" + owner.workspaceId() + "/members/" + member + "/role")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());
        return new Performed(owner.userId(), owner.workspaceId(), List.of(owner.accessToken()));
    }

    private Performed removeMember() throws Exception {
        Owner owner = newOwner();
        UUID member = addMember(owner.workspaceId(), iam.registerNewAccount());
        iam.delete(API + "/workspaces/" + owner.workspaceId() + "/members/" + member, owner.accessToken())
                .andExpect(status().isNoContent());
        return new Performed(owner.userId(), owner.workspaceId(), List.of(owner.accessToken()));
    }

    private Performed leaveWorkspace() throws Exception {
        Owner owner = newOwner();
        String memberEmail = iam.registerNewAccount();
        UUID member = addMember(owner.workspaceId(), memberEmail);
        String memberToken = iam.login(memberEmail);
        iam.postJson(API + "/workspaces/" + owner.workspaceId() + "/leave", Map.of(), memberToken)
                .andExpect(status().isNoContent());
        return new Performed(member, owner.workspaceId(), List.of(memberToken));
    }

    private Performed createInvitation() throws Exception {
        Owner owner = newOwner();
        UUID invitationId = invite(owner, "invited-" + UUID.randomUUID() + "@mail.com");
        return new Performed(
                owner.userId(), owner.workspaceId(), List.of(owner.accessToken(), invitationToken(invitationId)));
    }

    private Performed revokeInvitation() throws Exception {
        Owner owner = newOwner();
        UUID invitationId = invite(owner, "invited-" + UUID.randomUUID() + "@mail.com");
        iam.delete(API + "/workspaces/" + owner.workspaceId() + "/invitations/" + invitationId, owner.accessToken())
                .andExpect(status().isNoContent());
        return new Performed(
                owner.userId(), owner.workspaceId(), List.of(owner.accessToken(), invitationToken(invitationId)));
    }

    private Performed acceptInvitation() throws Exception {
        Owner owner = newOwner();
        String inviteeEmail = iam.registerVerifiedAccount();
        String token = invitationToken(invite(owner, inviteeEmail));
        String inviteeAccessToken = iam.login(inviteeEmail);
        iam.postJson(API + "/invitations/accept", Map.of("token", token), inviteeAccessToken)
                .andExpect(status().isOk());
        return new Performed(iam.userIdOf(inviteeEmail), owner.workspaceId(), List.of(token, inviteeAccessToken));
    }

    private Performed deleteWorkspace() throws Exception {
        Owner owner = newOwner();
        iam.delete(API + "/workspaces/" + owner.workspaceId(), owner.accessToken())
                .andExpect(status().isNoContent());
        return new Performed(owner.userId(), owner.workspaceId(), List.of(owner.accessToken()));
    }

    private Performed retryDelivery() throws Exception {
        Owner owner = newOwner();
        UUID deliveryId = seedDeadDelivery(owner.workspaceId());
        iam.postJson(
                        API + "/workspaces/" + owner.workspaceId() + "/notifications/deliveries/" + deliveryId
                                + "/retry",
                        Map.of(),
                        owner.accessToken())
                .andExpect(status().isAccepted());
        return new Performed(owner.userId(), owner.workspaceId(), List.of(owner.accessToken()));
    }

    // Helpers

    /** A verified account, logged in, that owns a new workspace. */
    private Owner newOwner() throws Exception {
        String email = iam.registerVerifiedAccount();
        String accessToken = iam.login(email);
        String body = iam.postJson(API + "/workspaces", Map.of("name", "Audited"), accessToken)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Owner(iam.userIdOf(email), accessToken, UUID.fromString(JsonPath.read(body, "$.id")));
    }

    /** Makes the account a MEMBER of the workspace, seeded as the superuser. */
    private UUID addMember(UUID workspaceId, String email) {
        UUID userId = iam.userIdOf(email);
        superuser()
                .sql(
                        "INSERT INTO workspace_member (workspace_id, user_id, role, joined_at) VALUES (?, ?, 'MEMBER', now())")
                .param(workspaceId)
                .param(userId)
                .update();
        return userId;
    }

    private UUID invite(Owner owner, String email) throws Exception {
        String body = iam.postJson(
                        API + "/workspaces/" + owner.workspaceId() + "/invitations",
                        Map.of("email", email, "role", "MEMBER"),
                        owner.accessToken())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** Opens the sealed token of the invitation event, as the notification module will. */
    private String invitationToken(UUID invitationId) {
        Map<String, Object> secret =
                superuser().sql("""
                        SELECT payload -> 'secret' ->> 'ciphertext' AS ciphertext,
                               payload -> 'secret' ->> 'keyId' AS key_id,
                               payload -> 'secret' ->> 'expiresAt' AS expires_at
                        FROM outbox_event
                        WHERE type = 'workspace.MemberInvited' AND payload ->> 'invitationId' = ?
                        """).param(invitationId.toString()).query().singleRow();
        return secretCipher.open(new SealedSecret(
                (String) secret.get("ciphertext"), (String) secret.get("key_id"), Instant.parse((String)
                        secret.get("expires_at"))));
    }

    /** A role-change notice of the workspace that failed five times, seeded as the superuser. */
    private static UUID seedDeadDelivery(UUID workspaceId) {
        UUID deliveryId = UUID.randomUUID();
        superuser()
                .sql("""
                        INSERT INTO notification_delivery (
                            id, event_id, recipient_key, channel, scope, workspace_id, template_key, locale,
                            template_version, recipient_email, variables, status, attempts, next_attempt_at,
                            provider_used, last_error_class, origin_trace_id, created_at)
                        VALUES (?, ?, ?, 'EMAIL', 'WORKSPACE', ?, 'workspace.role-changed', 'es',
                                1, ?, CAST(? AS jsonb), 'DEAD', 5, now(),
                                'B', 'TRANSIENT_FAILURE', 'seeded', now())
                        """)
                .param(deliveryId)
                .param(UUID.randomUUID())
                .param("email:seeded-" + deliveryId)
                .param(workspaceId)
                .param("seeded-" + deliveryId + "@mail.test")
                .param("""
                        {"workspaceName": "Audited", "affectedMember": "member@mail.test",
                         "previousRole": "MEMBER", "newRole": "ADMIN"}""")
                .update();
        return deliveryId;
    }

    private static List<AuditRecord> records(AuditAction action, UUID actorId) {
        return new ArrayList<>(superuser()
                .sql("""
                        SELECT occurred_at, workspace_id, outcome, trace_id, to_jsonb(a)::text AS json
                        FROM audit_log a
                        WHERE action = ? AND actor_user_id = ?
                        """)
                .param(action.name())
                .param(actorId)
                .query((rs, rowNum) -> new AuditRecord(
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getString("outcome"),
                        rs.getString("trace_id"),
                        rs.getString("json")))
                .list());
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }

    /** One row of the table: an action, its expected outcome and how to perform it. */
    record AuditCase(AuditAction action, AuditOutcome outcome, Scenario scenario) {

        @Override
        public String toString() {
            return action + " " + outcome;
        }
    }

    @FunctionalInterface
    interface Scenario {
        Performed perform(AuditCompletenessIT test) throws Exception;
    }

    /**
     * Who performed the action, in which workspace, and the secrets the action involved.
     *
     * @param workspaceId the workspace of the record, or {@code null} for account actions
     */
    record Performed(UUID actorId, UUID workspaceId, List<String> secrets) {}

    record Owner(UUID userId, String accessToken, UUID workspaceId) {}

    record AuditRecord(Instant occurredAt, UUID workspaceId, String outcome, String traceId, String json) {}
}
