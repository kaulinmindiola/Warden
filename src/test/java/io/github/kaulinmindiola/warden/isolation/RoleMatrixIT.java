package io.github.kaulinmindiola.warden.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.kaulinmindiola.warden.shared.security.Role;
import io.github.kaulinmindiola.warden.support.IamTestClient;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

/**
 * Endpoint × role matrix (REQ-FUNC-024): for every registered endpoint, each role below the
 * minimum gets 403 {@code insufficient-role}, and the minimum and every higher role succeed.
 */
class RoleMatrixIT extends IntegrationTest {

    private static final String INSUFFICIENT_ROLE = "urn:warden:problem:insufficient-role";

    @Autowired
    private WebApplicationContext context;

    static List<WorkspaceEndpoint> endpoints() {
        return IsolationRegistry.ENDPOINTS;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @Verifies("REQ-FUNC-024")
    void rolesBelowTheMinimumGet403AndTheOthersSucceed(WorkspaceEndpoint endpoint) throws Exception {
        WorkspaceFixture fixture =
                WorkspaceFixture.create(new IamTestClient(context), JdbcClient.create(superuserDataSource()));

        for (Role role : Role.values()) {
            MvcResult result = fixture.perform(endpoint.requestFor(fixture.workspace()), fixture.tokenWithRole(role));

            if (role.isAtLeast(endpoint.minimumRole())) {
                assertThat(result.getResponse().getStatus())
                        .as("%s as %s", endpoint, role)
                        .isEqualTo(endpoint.successStatus());
            } else {
                assertThat(result.getResponse().getStatus())
                        .as("%s as %s", endpoint, role)
                        .isEqualTo(403);
                assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.type"))
                        .isEqualTo(INSUFFICIENT_ROLE);
            }
        }
    }
}
