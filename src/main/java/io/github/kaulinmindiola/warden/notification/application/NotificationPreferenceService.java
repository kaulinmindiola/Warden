package io.github.kaulinmindiola.warden.notification.application;

import io.github.kaulinmindiola.warden.notification.application.NotificationPreferences.Preference;
import io.github.kaulinmindiola.warden.notification.domain.NotificationCategory;
import io.github.kaulinmindiola.warden.notification.domain.TemplateKey;
import io.github.kaulinmindiola.warden.notification.infrastructure.RecipientPreferenceRepository;
import io.github.kaulinmindiola.warden.shared.security.MembershipLookup;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads and changes a user's notification language and preferences (UC-26).
 *
 * <ul>
 *   <li>Language: {@code es} or {@code en} (REQ-FUNC-051); it applies to the deliveries created
 *       afterwards.
 *   <li>Release 1.0 offers one optional notification, {@code workspace.role-changed}, per workspace
 *       the user belongs to (REQ-FUNC-049 as reduced by D-01). Membership is checked through
 *       {@link MembershipLookup}: a workspace the user is not in is not found.
 *   <li>SECURITY and TRANSACTIONAL notifications cannot be turned off (BR-003).
 * </ul>
 *
 * <p>An update is validated as a whole and applied in one transaction, or not at all. Memberships
 * are read before that transaction, because the lookup opens a transaction of its own.
 */
@Service
public class NotificationPreferenceService {

    static final String UNSUPPORTED_LOCALE = "must be one of: es, en";
    static final String UNKNOWN_KEY = "must be a notification key";
    static final String REQUIRED = "is required";

    private static final Set<String> LOCALES = Set.of("es", "en");

    private final RecipientPreferenceRepository preferences;
    private final MembershipLookup memberships;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public NotificationPreferenceService(
            RecipientPreferenceRepository preferences,
            MembershipLookup memberships,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.preferences = preferences;
        this.memberships = memberships;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** The user's language and the optional notifications of every workspace they belong to. */
    public NotificationPreferences get(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        String key = TemplateKey.WORKSPACE_ROLE_CHANGED.key();
        Set<UUID> disabled = preferences.findDisabledWorkspaces(userId, key);
        List<Preference> perWorkspace = memberships.workspaceIdsOf(userId).stream()
                .map(workspaceId -> new Preference(key, workspaceId, !disabled.contains(workspaceId)))
                .toList();
        return new NotificationPreferences(preferences.findLocale(userId).orElse(null), perWorkspace);
    }

    /**
     * Changes the language, when given, and the listed preferences.
     *
     * @param locale {@code es}, {@code en} or {@code null} to keep the current language
     * @param changes the preferences to set; {@code null} or empty changes none
     * @return the settings after the change
     * @throws InvalidPreferencesException for an unsupported language or an incomplete preference
     * @throws PreferenceNotOptionalException for an attempt to disable a mandatory notification
     * @throws PreferenceWorkspaceNotFoundException for a workspace the user is not a member of
     */
    public NotificationPreferences update(UUID userId, String locale, List<PreferenceChange> changes) {
        Objects.requireNonNull(userId, "userId");
        List<PreferenceChange> requested = changes == null ? List.of() : changes;
        List<PreferenceChange> optional = validate(locale, requested);
        for (PreferenceChange change : optional) {
            if (memberships.find(change.workspaceId(), userId).isEmpty()) {
                throw new PreferenceWorkspaceNotFoundException();
            }
        }
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            if (locale != null) {
                preferences.saveLocale(userId, locale, now);
            }
            for (PreferenceChange change : optional) {
                preferences.savePreference(userId, change.key(), change.workspaceId(), change.enabled(), now);
            }
        });
        return get(userId);
    }

    /**
     * Checks every field and returns the changes to store: those of optional notifications. A
     * mandatory notification may be listed as enabled, which changes nothing.
     */
    private static List<PreferenceChange> validate(String locale, List<PreferenceChange> requested) {
        Map<String, String> violations = new LinkedHashMap<>();
        if (locale != null && !LOCALES.contains(locale)) {
            violations.put("locale", UNSUPPORTED_LOCALE);
        }
        List<PreferenceChange> optional = new ArrayList<>();
        String mandatoryDisabled = null;
        for (int index = 0; index < requested.size(); index++) {
            String field = "preferences[" + index + "]";
            PreferenceChange change = requested.get(index);
            if (change == null) {
                violations.put(field, REQUIRED);
                continue;
            }
            Optional<TemplateKey> key = TemplateKey.fromKey(change.key());
            if (key.isEmpty()) {
                violations.put(field + ".key", UNKNOWN_KEY);
                continue;
            }
            if (change.enabled() == null) {
                violations.put(field + ".enabled", REQUIRED);
                continue;
            }
            if (key.get().category() != NotificationCategory.INFORMATIONAL) {
                if (!change.enabled() && mandatoryDisabled == null) {
                    mandatoryDisabled = change.key();
                }
                continue;
            }
            if (change.workspaceId() == null) {
                violations.put(field + ".workspaceId", REQUIRED);
                continue;
            }
            optional.add(change);
        }
        if (!violations.isEmpty()) {
            throw new InvalidPreferencesException(violations);
        }
        if (mandatoryDisabled != null) {
            throw new PreferenceNotOptionalException(mandatoryDisabled);
        }
        return optional;
    }
}
