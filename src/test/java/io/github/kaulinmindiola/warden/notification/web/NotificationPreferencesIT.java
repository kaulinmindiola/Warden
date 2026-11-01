package io.github.kaulinmindiola.warden.notification.web;

import static io.github.kaulinmindiola.warden.support.IamTestClient.API;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.iam.PasswordChanged;
import io.github.kaulinmindiola.warden.notification.application.worker.OutboxDispatchWorker;
import io.github.kaulinmindiola.warden.shared.outbox.OutboxPublisher;
import io.github.kaulinmindiola.warden.shared.outbox.Recipient;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.shared.web.ProblemType;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import io.github.kaulinmindiola.warden.workspace.MemberRoleChanged;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET} and {@code PUT /api/v1/me/notification-preferences} (UC-26; REQ-FUNC-049 as reduced
 * for release 1.0, REQ-FUNC-050, 051). The effect on deliveries is checked by dispatching events
 * built from the producing modules' payload classes, as in production.
 */
class NotificationPreferencesIT extends IntegrationTest {

    private static final String PREFERENCES = API + "/me/notification-preferences";
    private static final String ROLE_CHANGED = "workspace.role-changed";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private OutboxPublisher outbox;

    @Autowired
    private OutboxDispatchWorker worker;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private IamTestClient iam;
    private String email;
    private UUID userId;
    private String token;
    private UUID w;
    private UUID x;

    @BeforeEach
    void createUserWithTwoWorkspaces() throws Exception {
        iam = new IamTestClient(context);
        email = iam.registerVerifiedAccount();
        userId = iam.userIdOf(email);
        token = iam.login(email);
        w = createWorkspace("W");
        x = createWorkspace("X");
        dispatchAll();
    }

    @Test
    @Verifies("REQ-FUNC-050")
    void withoutChoicesTheRoleChangeNoticeIsEnabledInEveryWorkspaceAndNoLanguageIsChosen() throws Exception {
        iam.get(PREFERENCES, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value(nullValue()))
                .andExpect(jsonPath("$.preferences", hasSize(2)))
                .andExpect(jsonPath("$.preferences[?(@.workspaceId == '" + w + "')].enabled")
                        .value(true))
                .andExpect(jsonPath("$.preferences[?(@.workspaceId == '" + x + "')].enabled")
                        .value(true))
                .andExpect(jsonPath("$.preferences[0].key").value(ROLE_CHANGED));
    }

    @Test
    @Verifies({"REQ-FUNC-049", "BR-003"})
    void disablingTheRoleChangeNoticeInOneWorkspaceLeavesTheOtherEnabled() throws Exception {
        put("{\"preferences\":[{\"key\":\"" + ROLE_CHANGED + "\",\"workspaceId\":\"" + w + "\",\"enabled\":false}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences[?(@.workspaceId == '" + w + "')].enabled")
                        .value(false))
                .andExpect(jsonPath("$.preferences[?(@.workspaceId == '" + x + "')].enabled")
                        .value(true));

        UUID inW = publishRoleChange(w);
        UUID inX = publishRoleChange(x);
        dispatchAll();

        assertThat(deliveriesToUser(inW)).isZero();
        assertThat(deliveriesToUser(inX)).isEqualTo(1);
    }

    @Test
    @Verifies("REQ-FUNC-049")
    void aPreferenceCanBeTurnedBackOn() throws Exception {
        put(preference(w, false)).andExpect(status().isOk());

        put(preference(w, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences[?(@.workspaceId == '" + w + "')].enabled")
                        .value(true));
    }

    @Test
    @Verifies({"REQ-FUNC-049", "REQ-FUNC-023"})
    void aWorkspaceTheUserDoesNotBelongToIsNotFoundAndNothingChanges() throws Exception {
        UUID foreign = UUID.randomUUID();

        put("{\"locale\":\"en\",\"preferences\":[" + item(w, false) + "," + item(foreign, false) + "]}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ProblemType.NOT_FOUND.uri().toString()));

        iam.get(PREFERENCES, token)
                .andExpect(jsonPath("$.locale").value(nullValue()))
                .andExpect(jsonPath("$.preferences[?(@.workspaceId == '" + w + "')].enabled")
                        .value(true));
    }

    @Test
    @Verifies({"REQ-FUNC-049", "BR-003"})
    void securityNotificationsCannotBeDisabled() throws Exception {
        put("{\"preferences\":[{\"key\":\"security.refresh-reuse\",\"enabled\":false}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value(ProblemType.PREFERENCE_NOT_OPTIONAL.uri().toString()));
        put("{\"preferences\":[{\"key\":\"workspace.invitation\",\"workspaceId\":\"" + w + "\",\"enabled\":false}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value(ProblemType.PREFERENCE_NOT_OPTIONAL.uri().toString()));
    }

    @Test
    @Verifies("REQ-FUNC-051")
    void anUnsupportedLanguageOrAnIncompletePreferenceIsAValidationError() throws Exception {
        put("{\"locale\":\"fr\",\"preferences\":[{\"key\":\"unknown.key\",\"enabled\":false},"
                        + "{\"key\":\"" + ROLE_CHANGED + "\",\"enabled\":false},"
                        + "{\"key\":\"" + ROLE_CHANGED + "\",\"workspaceId\":\"" + w + "\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value(ProblemType.VALIDATION_ERROR.uri().toString()))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "locale",
                                "preferences[0].key",
                                "preferences[1].workspaceId",
                                "preferences[2].enabled")));
    }

    @Test
    @Verifies("REQ-FUNC-051")
    void changingTheLanguageAppliesToTheNextDeliveries() throws Exception {
        UUID before = publishPasswordChanged();
        dispatchAll();

        put("{\"locale\":\"en\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("en"));
        UUID after = publishPasswordChanged();
        dispatchAll();

        assertThat(localeOfDelivery(before)).isEqualTo("es");
        assertThat(localeOfDelivery(after)).isEqualTo("en");
    }

    private ResultActions put(String body) throws Exception {
        return iam.mvc()
                .perform(MockMvcRequestBuilders.put(PREFERENCES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static String preference(UUID workspaceId, boolean enabled) {
        return "{\"preferences\":[" + item(workspaceId, enabled) + "]}";
    }

    private static String item(UUID workspaceId, boolean enabled) {
        return "{\"key\":\"" + ROLE_CHANGED + "\",\"workspaceId\":\"" + workspaceId + "\",\"enabled\":" + enabled + "}";
    }

    private UUID createWorkspace(String name) throws Exception {
        String body = iam.postJson(API + "/workspaces", Map.of("name", name), token)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** A role change of the user in the workspace, notified to the user and another owner. */
    private UUID publishRoleChange(UUID workspaceId) {
        MemberRoleChanged payload = new MemberRoleChanged(
                "Team",
                new Recipient(userId, email, "es"),
                List.of(new Recipient(UUID.randomUUID(), "owner-" + UUID.randomUUID() + "@mail.com", "es")),
                Role.MEMBER,
                Role.VIEWER,
                UUID.randomUUID());
        return publish(MemberRoleChanged.TYPE, workspaceId, payload);
    }

    private UUID publishPasswordChanged() {
        return publish(
                PasswordChanged.TYPE, null, new PasswordChanged(new Recipient(userId, email, "es"), clock.instant()));
    }

    private UUID publish(String type, UUID workspaceId, Object payload) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> outbox.publish(type, workspaceId, payload))
                .eventId();
    }

    private void dispatchAll() {
        while (worker.runOnce() > 0) {
            // keep dispatching until no pending event is left
        }
    }

    private long deliveriesToUser(UUID eventId) {
        return superuser()
                .sql("SELECT count(*) FROM notification_delivery WHERE event_id = ? AND recipient_key = ?")
                .param(eventId)
                .param(userId.toString())
                .query(Long.class)
                .single();
    }

    private String localeOfDelivery(UUID eventId) {
        return superuser()
                .sql("SELECT locale FROM notification_delivery WHERE event_id = ? AND recipient_key = ?")
                .param(eventId)
                .param(userId.toString())
                .query(String.class)
                .single();
    }

    private static JdbcClient superuser() {
        return JdbcClient.create(superuserDataSource());
    }
}
