package io.github.kaulinmindiola.warden.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import io.github.kaulinmindiola.warden.shared.web.ApiWebConfiguration;
import io.github.kaulinmindiola.warden.support.IntegrationTest;
import io.github.kaulinmindiola.warden.support.Verifies;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Endpoint sweep for REQ-SEC-001: every Warden endpoint answers 401 without a token, except the
 * public ones. Endpoints are discovered from the handler mappings, so new endpoints are covered
 * without changing this test.
 */
class AuthenticationByDefaultIT extends IntegrationTest {

    private static final String BASE_PACKAGE = "io.github.kaulinmindiola.warden";
    private static final String API = ApiWebConfiguration.API_PREFIX;

    /** The exceptions listed by REQ-SEC-001; health checks live on the management port. */
    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            "POST " + API + "/auth/register",
            "POST " + API + "/auth/login",
            "POST " + API + "/auth/refresh",
            "POST " + API + "/auth/email/verify",
            "POST " + API + "/auth/password/forgot",
            "POST " + API + "/auth/password/reset");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @Verifies("REQ-SEC-001")
    void everyEndpointExceptThePublicOnesRequiresAToken() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        Set<String> swept = new TreeSet<>();
        List<String> unprotected = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> handler :
                handlerMapping.getHandlerMethods().entrySet()) {
            if (!handler.getValue().getBeanType().getPackageName().startsWith(BASE_PACKAGE)) {
                continue;
            }
            Set<RequestMethod> methods = handler.getKey().getMethodsCondition().getMethods();
            Set<RequestMethod> toCall = methods.isEmpty() ? Set.of(RequestMethod.GET) : methods;
            for (String pattern : handler.getKey().getPatternValues()) {
                for (RequestMethod method : toCall) {
                    String endpoint = method.name() + " " + pattern;
                    swept.add(endpoint);
                    if (PUBLIC_ENDPOINTS.contains(endpoint)) {
                        continue;
                    }
                    String path =
                            pattern.replaceAll("\\{[^/]+}", UUID.randomUUID().toString());
                    int status = mvc.perform(request(HttpMethod.valueOf(method.name()), path))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    if (status != 401) {
                        unprotected.add(endpoint + " -> " + status);
                    }
                }
            }
        }

        System.out.println("REQ-SEC-001 endpoint sweep: " + swept);
        assertThat(swept).contains("POST " + API + "/auth/register", "POST " + API + "/auth/login");
        assertThat(unprotected).isEmpty();
    }
}
