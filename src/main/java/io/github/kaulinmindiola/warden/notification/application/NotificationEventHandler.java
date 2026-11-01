package io.github.kaulinmindiola.warden.notification.application;

import io.github.kaulinmindiola.warden.notification.application.EventPayloads.AccountLink;
import io.github.kaulinmindiola.warden.notification.application.EventPayloads.AccountNotice;
import io.github.kaulinmindiola.warden.notification.application.EventPayloads.Invitation;
import io.github.kaulinmindiola.warden.notification.application.EventPayloads.InvitationAccepted;
import io.github.kaulinmindiola.warden.notification.application.EventPayloads.InvitationRevoked;
import io.github.kaulinmindiola.warden.notification.application.EventPayloads.Recipient;
import io.github.kaulinmindiola.warden.notification.application.EventPayloads.RoleChanged;
import io.github.kaulinmindiola.warden.notification.domain.NewDelivery;
import io.github.kaulinmindiola.warden.notification.domain.NotificationCategory;
import io.github.kaulinmindiola.warden.notification.domain.NotificationRecipient;
import io.github.kaulinmindiola.warden.notification.domain.Template;
import io.github.kaulinmindiola.warden.notification.domain.TemplateKey;
import io.github.kaulinmindiola.warden.notification.infrastructure.DeliveryRepository;
import io.github.kaulinmindiola.warden.notification.infrastructure.RecipientPreferenceRepository;
import io.github.kaulinmindiola.warden.shared.crypto.KeyedHasher;
import io.github.kaulinmindiola.warden.shared.crypto.SealedSecret;
import io.github.kaulinmindiola.warden.shared.id.UuidV7Generator;
import io.github.kaulinmindiola.warden.shared.outbox.DomainEvent;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxEventHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns outbox events into deliveries (SDD V13 fan-out rules, SRS appendix B). It runs inside the
 * dispatch transaction, in the system context, and writes through {@code JdbcClient} only, as the
 * {@link OutboxEventHandler} contract requires.
 *
 * <ol>
 *   <li>Recipients come from the payload; a role change goes to the affected member and every owner
 *       at the time, de-duplicated by recipient key (REQ-FUNC-040).
 *   <li>SECURITY and TRANSACTIONAL notifications are always created; INFORMATIONAL ones unless the
 *       user turned them off in that workspace (REQ-FUNC-048, 050).
 *   <li>Locale: the user's setting, else the event's, else {@code es} (REQ-FUNC-051).
 *   <li>The template version is resolved and pinned now; rendering happens at send time.
 *   <li>Idempotent: an event handed over again creates no second delivery (REQ-FUNC-061).
 * </ol>
 *
 * <p>{@code workspace.InvitationRevoked} (revoked or replaced) and {@code workspace.WorkspaceDeleted}
 * cancel the unsent deliveries of the invitation or of the workspace and drop their sealed tokens
 * (REQ-FUNC-062). {@code workspace.InvitationAccepted} does the same for the invitation, so a dead
 * delivery of a used invitation can no longer be retried (REQ-FUNC-060). Events are dispatched in
 * insertion order, so a cancellation finds the deliveries of the events published before it, even
 * within the same batch. Cancelling twice changes nothing. Event types of other consumers create
 * nothing here.
 */
@Component
public class NotificationEventHandler implements OutboxEventHandler {

    static final String EMAIL_VERIFICATION_REQUESTED = "iam.EmailVerificationRequested";
    static final String PASSWORD_RESET_REQUESTED = "iam.PasswordResetRequested";
    static final String REGISTRATION_ATTEMPTED = "iam.RegistrationAttemptedOnExistingAccount";
    static final String PASSWORD_CHANGED = "iam.PasswordChanged";
    static final String REFRESH_TOKEN_REUSE_DETECTED = "iam.RefreshTokenReuseDetected";
    static final String MEMBER_INVITED = "workspace.MemberInvited";
    static final String MEMBER_ROLE_CHANGED = "workspace.MemberRoleChanged";
    static final String INVITATION_REVOKED = "workspace.InvitationRevoked";
    static final String WORKSPACE_DELETED = "workspace.WorkspaceDeleted";
    static final String INVITATION_ACCEPTED = "workspace.InvitationAccepted";

    private static final Set<String> LOCALES = Set.of("es", "en");
    private static final String DEFAULT_LOCALE = "es";
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** How instants appear in notifications: unambiguous and independent of the locale. */
    private static final DateTimeFormatter INSTANT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final JsonMapper jsonMapper;
    private final TemplateResolver templates;
    private final DeliveryRepository deliveries;
    private final RecipientPreferenceRepository preferences;
    private final KeyedHasher keyedHasher;
    private final UuidV7Generator ids;
    private final Clock clock;

    public NotificationEventHandler(
            JsonMapper jsonMapper,
            TemplateResolver templates,
            DeliveryRepository deliveries,
            RecipientPreferenceRepository preferences,
            KeyedHasher keyedHasher,
            UuidV7Generator ids,
            Clock clock) {
        this.jsonMapper = jsonMapper;
        this.templates = templates;
        this.deliveries = deliveries;
        this.preferences = preferences;
        this.keyedHasher = keyedHasher;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public void handle(DomainEvent event) {
        switch (event.type()) {
            case INVITATION_REVOKED -> {
                deliveries.cancelUnsentOfInvitation(
                        read(event, InvitationRevoked.class).invitationId());
                return;
            }
            case INVITATION_ACCEPTED -> {
                deliveries.cancelUnsentOfInvitation(
                        read(event, InvitationAccepted.class).invitationId());
                return;
            }
            case WORKSPACE_DELETED -> {
                requireSupportedVersion(event);
                if (event.workspaceId() == null) {
                    throw new IllegalStateException("A workspace deletion event carries no workspace");
                }
                deliveries.cancelUnsentOfWorkspace(event.workspaceId());
                return;
            }
            default -> {
                // not a cancellation: fan out below, if the type has a notification
            }
        }
        Fanout fanout = fanoutOf(event);
        if (fanout == null) {
            return;
        }
        Instant now = clock.instant();
        for (Map.Entry<String, NotificationRecipient> entry :
                fanout.recipientsByKey().entrySet()) {
            NotificationRecipient recipient = entry.getValue();
            if (optedOut(fanout.key(), recipient, event.workspaceId())) {
                continue;
            }
            Template template = templates.resolve(fanout.key(), localeOf(recipient));
            deliveries.insertIfAbsent(
                    ids.next(),
                    new NewDelivery(
                            event.eventId(),
                            entry.getKey(),
                            fanout.key(),
                            event.workspaceId(),
                            fanout.invitationId(),
                            template.locale(),
                            template.version(),
                            recipient.email(),
                            fanout.variables(),
                            fanout.secret(),
                            event.traceId()),
                    now);
        }
    }

    /** What an event becomes, or {@code null} when it creates no delivery. */
    private Fanout fanoutOf(DomainEvent event) {
        return switch (event.type()) {
            case EMAIL_VERIFICATION_REQUESTED -> accountLink(event, TemplateKey.ACCOUNT_EMAIL_VERIFICATION);
            case PASSWORD_RESET_REQUESTED -> accountLink(event, TemplateKey.ACCOUNT_PASSWORD_RESET);
            case REGISTRATION_ATTEMPTED -> accountNotice(event, TemplateKey.ACCOUNT_REGISTRATION_ATTEMPT);
            case PASSWORD_CHANGED -> accountNotice(event, TemplateKey.ACCOUNT_PASSWORD_CHANGED);
            case REFRESH_TOKEN_REUSE_DETECTED -> accountNotice(event, TemplateKey.SECURITY_REFRESH_REUSE);
            case MEMBER_INVITED -> invitation(event);
            case MEMBER_ROLE_CHANGED -> roleChanged(event);
            default -> null;
        };
    }

    private Fanout accountLink(DomainEvent event, TemplateKey key) {
        AccountLink payload = read(event, AccountLink.class);
        return new Fanout(
                key,
                recipients(List.of(payload.recipient())),
                Map.of("expiresAt", format(payload.expiresAt())),
                payload.secret().toSealedSecret(),
                null);
    }

    private Fanout accountNotice(DomainEvent event, TemplateKey key) {
        AccountNotice payload = read(event, AccountNotice.class);
        return new Fanout(
                key,
                recipients(List.of(payload.recipient())),
                Map.of("occurredAt", format(payload.occurredAt())),
                null,
                null);
    }

    private Fanout invitation(DomainEvent event) {
        Invitation payload = read(event, Invitation.class);
        return new Fanout(
                TemplateKey.WORKSPACE_INVITATION,
                recipients(List.of(payload.recipient())),
                Map.of(
                        "workspaceName", payload.workspaceName(),
                        "role", payload.role(),
                        "expiresAt", format(payload.expiresAt())),
                payload.secret().toSealedSecret(),
                payload.invitationId());
    }

    private Fanout roleChanged(DomainEvent event) {
        RoleChanged payload = read(event, RoleChanged.class);
        List<Recipient> recipients = new ArrayList<>();
        recipients.add(payload.affected());
        recipients.addAll(payload.owners());
        return new Fanout(
                TemplateKey.WORKSPACE_ROLE_CHANGED,
                recipients(recipients),
                Map.of(
                        "workspaceName", payload.workspaceName(),
                        "affectedMember", payload.affected().email(),
                        "previousRole", payload.previousRole(),
                        "newRole", payload.newRole()),
                null,
                null);
    }

    /** Payload of a consumed type; only schema version 1 exists in release 1.0. */
    private <T> T read(DomainEvent event, Class<T> payloadType) {
        requireSupportedVersion(event);
        return jsonMapper.readValue(event.payload(), payloadType);
    }

    private static void requireSupportedVersion(DomainEvent event) {
        if (event.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "Unsupported schema version " + event.schemaVersion() + " of " + event.type());
        }
    }

    /** Recipients by recipient key, in payload order, each key once. */
    private Map<String, NotificationRecipient> recipients(List<Recipient> payloadRecipients) {
        Map<String, NotificationRecipient> byKey = new LinkedHashMap<>();
        for (Recipient payloadRecipient : payloadRecipients) {
            NotificationRecipient recipient = payloadRecipient.toRecipient();
            byKey.putIfAbsent(recipientKey(recipient), recipient);
        }
        return byKey;
    }

    /** The user id, or a keyed hash of the address for someone without account (SDD V13). */
    private String recipientKey(NotificationRecipient recipient) {
        UUID userId = recipient.userId();
        return userId != null ? userId.toString() : "email:" + keyedHasher.hmac(recipient.email());
    }

    private boolean optedOut(TemplateKey key, NotificationRecipient recipient, UUID workspaceId) {
        return key.category() == NotificationCategory.INFORMATIONAL
                && recipient.userId() != null
                && workspaceId != null
                && preferences.isDisabled(recipient.userId(), key.key(), workspaceId);
    }

    private String localeOf(NotificationRecipient recipient) {
        if (recipient.userId() != null) {
            String chosen = preferences.findLocale(recipient.userId()).orElse(null);
            if (chosen != null) {
                return chosen;
            }
        }
        String carried = recipient.fallbackLocale();
        return carried != null && LOCALES.contains(carried) ? carried : DEFAULT_LOCALE;
    }

    private static String format(Instant instant) {
        return INSTANT_FORMAT.format(instant);
    }

    /** The deliveries one event asks for, before preferences and locales are applied. */
    private record Fanout(
            TemplateKey key,
            Map<String, NotificationRecipient> recipientsByKey,
            Map<String, String> variables,
            SealedSecret secret,
            UUID invitationId) {}
}
