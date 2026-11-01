package io.github.kaulinmindiola.warden.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kaulinmindiola.warden.iam.EmailVerificationRequested;
import io.github.kaulinmindiola.warden.iam.PasswordChanged;
import io.github.kaulinmindiola.warden.notification.application.worker.OutboxDispatchWorker;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.crypto.SecretCipher;
import io.github.kaulinmindiola.warden.shared.outbox.DomainEvent;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher;
import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.github.kaulinmindiola.warden.workspace.InvitationAccepted;
import io.github.kaulinmindiola.warden.workspace.MemberInvited;
import io.github.kaulinmindiola.warden.workspace.MemberRoleChanged;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The fan-out of events into deliveries (REQ-FUNC-040, 047, 048, 050, 051, 061), with the events
 * built from the producing modules' own payload classes and dispatched by the worker as in
 * production. Deliveries are inspected as the superuser; preferences and language settings are
 * seeded the same way, because their API arrives with the preferences block.
 */
class NotificationFanoutIT extends IntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-01T10:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-07-02T10:00:00Z");
    private static final String EXPIRES_TEXT = "2026-07-02 10:00 UTC";

    @Autowired
    private OutboxPublisher outbox;

    @Autowired
    private OutboxDispatchWorker worker;

    @Autowired
    private NotificationEventHandler handler;

    @Autowired
    private TenantTransactions tenantTransactions;

    @Autowired
    private SecretCipher secretCipher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void dispatchWhatOtherTestsLeftPending() {
        clock.setInstant(NOW);
        while (worker.runOnce() > 0) {
            // keep dispatching until no pending event is left
        }
    }

    @Test
    @Verifies({"REQ-FUNC-047", "REQ-FUNC-048"})
    void aVerificationRequestBecomesOnePendingDeliveryWithItsTemplatePinnedAndItsTokenSealed() {
        UUID user = UUID.randomUUID();
        SealedSecret secret = secretCipher.seal("wot_verification-token", EXPIRES);
        DomainEvent event = publish(
                EmailVerificationRequested.TYPE,
                null,
                new EmailVerificationRequested(new Recipient(user, "ana@mail.com", "en"), secret, EXPIRES));

        worker.runOnce();

        List<Map<String, Object>> rows = deliveriesOf(event.eventId());
        assertThat(rows).hasSize(1);
        Map<String, Object> delivery = rows.getFirst();
        assertThat(delivery)
                .containsEntry("recipient_key", user.toString())
                .containsEntry("channel", "EMAIL")
                .containsEntry("scope", "SYSTEM")
                .containsEntry("template_key", "account.email-verification")
                .containsEntry("locale", "en")
                .containsEntry("template_version", 1)
                .containsEntry("recipient_email", "ana@mail.com")
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 0)
                .containsEntry("secret_key_id", secret.keyId())
                .containsEntry("origin_trace_id", event.traceId())
                .containsEntry("expires_at_variable", EXPIRES_TEXT)
                .containsEntry("variable_count", 1);
        assertThat(delivery.get("workspace_id")).isNull();
        assertThat((byte[]) delivery.get("secret_ciphertext"))
                .isEqualTo(Base64.getDecoder().decode(secret.ciphertext()));
        assertThat(instantOf(event.eventId(), "next_attempt_at")).isEqualTo(NOW);
        assertThat(instantOf(event.eventId(), "secret_expires_at")).isEqualTo(EXPIRES);
        assertThat(outboxPayload(event.eventId())).doesNotContain(secret.ciphertext());
    }

    @Test
    @Verifies("REQ-FUNC-061")
    void handingTheSameEventOverThreeTimesCreatesOneDeliveryPerRecipient() {
        DomainEvent event = publish(
                MemberRoleChanged.TYPE,
                UUID.randomUUID(),
                new MemberRoleChanged(
                        "Acme",
                        member("affected@mail.com"),
                        List.of(member("owner1@mail.com"), member("owner2@mail.com")),
                        Role.MEMBER,
                        Role.ADMIN,
                        UUID.randomUUID()));

        worker.runOnce();
        handOverAgain(event);
        handOverAgain(event);

        assertThat(deliveriesOf(event.eventId())).hasSize(3);
    }

    @Test
    @Verifies("REQ-FUNC-040")
    void aRoleChangeGoesToTheAffectedMemberAndEveryOwnerOnce() {
        UUID workspace = UUID.randomUUID();
        Recipient affected = member("affected@mail.com");
        Recipient firstOwner = member("owner1@mail.com");
        Recipient secondOwner = member("owner2@mail.com");
        DomainEvent toMember = publish(
                MemberRoleChanged.TYPE,
                workspace,
                new MemberRoleChanged(
                        "Acme",
                        affected,
                        List.of(firstOwner, secondOwner),
                        Role.MEMBER,
                        Role.ADMIN,
                        firstOwner.userId()));
        DomainEvent toOwner = publish(
                MemberRoleChanged.TYPE,
                workspace,
                new MemberRoleChanged(
                        "Acme",
                        secondOwner,
                        List.of(firstOwner, secondOwner),
                        Role.OWNER,
                        Role.ADMIN,
                        firstOwner.userId()));

        worker.runOnce();

        assertThat(deliveriesOf(toMember.eventId()))
                .extracting(row -> row.get("recipient_key"))
                .containsExactlyInAnyOrder(
                        affected.userId().toString(),
                        firstOwner.userId().toString(),
                        secondOwner.userId().toString());
        assertThat(deliveriesOf(toMember.eventId()))
                .allSatisfy(row -> assertThat(row)
                        .containsEntry("scope", "WORKSPACE")
                        .containsEntry("workspace_id", workspace)
                        .containsEntry("template_key", "workspace.role-changed")
                        .containsEntry("workspace_name_variable", "Acme")
                        .containsEntry("affected_member_variable", "affected@mail.com")
                        .containsEntry("previous_role_variable", "MEMBER")
                        .containsEntry("new_role_variable", "ADMIN"));
        assertThat(deliveriesOf(toOwner.eventId()))
                .extracting(row -> row.get("recipient_key"))
                .containsExactlyInAnyOrder(
                        firstOwner.userId().toString(), secondOwner.userId().toString());
    }

    @Test
    @Verifies({"REQ-FUNC-048", "REQ-FUNC-050"})
    void optOutsSilenceOnlyInformationalNotificationsInTheirWorkspace() {
        UUID workspace = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        Recipient optedOut = member("quiet@mail.com");
        Recipient owner = member("owner@mail.com");
        for (String key : List.of(
                "workspace.role-changed",
                "workspace.invitation",
                "account.password-changed",
                "security.refresh-reuse")) {
            seedPreference(optedOut.userId(), key, workspace, false);
        }

        DomainEvent passwordChanged =
                publish(PasswordChanged.TYPE, null, new PasswordChanged(optedOut, NOW.minusSeconds(60)));
        DomainEvent invited = publish(
                MemberInvited.TYPE,
                workspace,
                new MemberInvited(
                        UUID.randomUUID(),
                        "Acme",
                        Role.MEMBER,
                        new Recipient(null, "quiet@mail.com", "es"),
                        secretCipher.seal("wot_invitation-token", EXPIRES),
                        EXPIRES));
        DomainEvent roleChanged = publish(
                MemberRoleChanged.TYPE,
                workspace,
                new MemberRoleChanged("Acme", optedOut, List.of(owner), Role.MEMBER, Role.VIEWER, owner.userId()));
        DomainEvent roleChangedElsewhere = publish(
                MemberRoleChanged.TYPE,
                otherWorkspace,
                new MemberRoleChanged("Other", optedOut, List.of(owner), Role.MEMBER, Role.VIEWER, owner.userId()));

        worker.runOnce();

        assertThat(deliveriesOf(passwordChanged.eventId())).as("security").hasSize(1);
        assertThat(deliveriesOf(invited.eventId())).as("transactional").hasSize(1);
        assertThat(deliveriesOf(roleChanged.eventId()))
                .as("informational, turned off in this workspace")
                .extracting(row -> row.get("recipient_key"))
                .containsExactly(owner.userId().toString());
        assertThat(deliveriesOf(roleChangedElsewhere.eventId()))
                .as("informational, enabled by default in another workspace")
                .extracting(row -> row.get("recipient_key"))
                .containsExactlyInAnyOrder(
                        optedOut.userId().toString(), owner.userId().toString());
    }

    @Test
    @Verifies("REQ-FUNC-051")
    void theLanguageIsTheUsersChoiceThenTheEventsThenSpanish() {
        Recipient choseEnglish = new Recipient(UUID.randomUUID(), "chose@mail.com", "es");
        seedLocale(choseEnglish.userId(), "en");
        Recipient carriesEnglish = new Recipient(UUID.randomUUID(), "carries@mail.com", "en");
        Recipient carriesSpanish = new Recipient(UUID.randomUUID(), "spanish@mail.com", "es");

        DomainEvent chose = publish(PasswordChanged.TYPE, null, new PasswordChanged(choseEnglish, NOW));
        DomainEvent carries = publish(PasswordChanged.TYPE, null, new PasswordChanged(carriesEnglish, NOW));
        DomainEvent spanish = publish(PasswordChanged.TYPE, null, new PasswordChanged(carriesSpanish, NOW));

        worker.runOnce();

        assertThat(deliveriesOf(chose.eventId()).getFirst()).containsEntry("locale", "en");
        assertThat(deliveriesOf(carries.eventId()).getFirst()).containsEntry("locale", "en");
        assertThat(deliveriesOf(spanish.eventId()).getFirst()).containsEntry("locale", "es");
    }

    @Test
    @Verifies("REQ-FUNC-047")
    void anInvitationIsAddressedByAKeyedHashAndKeepsItsInvitation() {
        UUID workspace = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        DomainEvent event = publish(
                MemberInvited.TYPE,
                workspace,
                new MemberInvited(
                        invitationId,
                        "Acme",
                        Role.ADMIN,
                        new Recipient(null, "invitee@mail.com", "en"),
                        secretCipher.seal("wot_invitation-token", EXPIRES),
                        EXPIRES));

        worker.runOnce();

        Map<String, Object> delivery = deliveriesOf(event.eventId()).getFirst();
        assertThat((String) delivery.get("recipient_key")).startsWith("email:").doesNotContain("invitee@mail.com");
        assertThat(delivery)
                .containsEntry("template_key", "workspace.invitation")
                .containsEntry("scope", "WORKSPACE")
                .containsEntry("workspace_id", workspace)
                .containsEntry("invitation_id", invitationId)
                .containsEntry("locale", "en")
                .containsEntry("workspace_name_variable", "Acme")
                .containsEntry("role_variable", "ADMIN")
                .containsEntry("expires_at_variable", EXPIRES_TEXT)
                .containsEntry("variable_count", 3);
    }

    @Test
    void eventsWithoutANotificationCreateNoDelivery() {
        DomainEvent accepted =
                publish(InvitationAccepted.TYPE, UUID.randomUUID(), new InvitationAccepted(UUID.randomUUID()));

        worker.runOnce();

        assertThat(deliveriesOf(accepted.eventId())).isEmpty();
        assertThat(outboxPublished(accepted.eventId())).isTrue();
    }

    private DomainEvent publish(String type, UUID workspaceId, Object payload) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> outbox.publish(type, workspaceId, payload));
    }

    /** Hands an already dispatched event to the handler again, as after a crash before commit. */
    private void handOverAgain(DomainEvent event) {
        tenantTransactions.system(() -> {
            handler.handle(event);
            return null;
        });
    }

    private static Recipient member(String email) {
        return new Recipient(UUID.randomUUID(), email, "es");
    }

    /**
     * The deliveries of an event, with each template variable as a text column ({@code
     * <name>_variable}) and the number of variables, so that no JSON parsing is needed.
     */
    private static List<Map<String, Object>> deliveriesOf(UUID eventId) {
        return superuser().sql("""
                        SELECT recipient_key, channel, scope, workspace_id, invitation_id, template_key, locale,
                               template_version, recipient_email, status, attempts, origin_trace_id,
                               secret_ciphertext, secret_key_id,
                               variables ->> 'expiresAt' AS expires_at_variable,
                               variables ->> 'workspaceName' AS workspace_name_variable,
                               variables ->> 'role' AS role_variable,
                               variables ->> 'affectedMember' AS affected_member_variable,
                               variables ->> 'previousRole' AS previous_role_variable,
                               variables ->> 'newRole' AS new_role_variable,
                               (SELECT count(*)::int FROM jsonb_object_keys(variables)) AS variable_count
                        FROM notification_delivery
                        WHERE event_id = ?
                        ORDER BY recipient_key
                        """).param(eventId).query().listOfRows();
    }

    /** A timestamp column of the single delivery of an event. */
    private static Instant instantOf(UUID eventId, String column) {
        return superuser()
                .sql("SELECT " + column + " FROM notification_delivery WHERE event_id = ?")
                .param(eventId)
                .query((row, rowNumber) ->
                        row.getObject(column, OffsetDateTime.class).toInstant())
                .single();
    }

    private static String outboxPayload(UUID eventId) {
        return superuser()
                .sql("SELECT payload::text FROM outbox_event WHERE id = ?")
                .param(eventId)
                .query(String.class)
                .single();
    }

    private static boolean outboxPublished(UUID eventId) {
        return superuser()
                .sql("SELECT published_at IS NOT NULL FROM outbox_event WHERE id = ?")
                .param(eventId)
                .query(Boolean.class)
                .single();
    }

    private static void seedPreference(UUID userId, String key, UUID workspaceId, boolean enabled) {
        superuser()
                .sql("""
                        INSERT INTO notification_preference (user_id, template_key, workspace_id, enabled, updated_at)
                        VALUES (?, ?, ?, ?, now())
                        """)
                .param(userId)
                .param(key)
                .param(workspaceId)
                .param(enabled)
                .update();
    }

    private static void seedLocale(UUID userId, String locale) {
        superuser()
                .sql("INSERT INTO recipient_setting (user_id, locale, updated_at) VALUES (?, ?, now())")
                .param(userId)
                .param(locale)
                .update();
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
