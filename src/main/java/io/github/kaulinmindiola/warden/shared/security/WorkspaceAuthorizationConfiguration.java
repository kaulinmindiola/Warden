package io.github.kaulinmindiola.warden.shared.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link WorkspaceAuthorizationInterceptor} for every handler. It acts only on methods
 * annotated with {@link RequiresWorkspaceRole}, so a workspace route is checked whatever its path.
 */
@Configuration(proxyBeanMethods = false)
public class WorkspaceAuthorizationConfiguration implements WebMvcConfigurer {

    private final ObjectProvider<MembershipLookup> membershipLookups;
    private final ObjectProvider<MeterRegistry> meterRegistries;

    public WorkspaceAuthorizationConfiguration(
            ObjectProvider<MembershipLookup> membershipLookups, ObjectProvider<MeterRegistry> meterRegistries) {
        this.membershipLookups = membershipLookups;
        this.meterRegistries = meterRegistries;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new WorkspaceAuthorizationInterceptor(
                new DeferredMembershipLookup(membershipLookups),
                meterRegistries.getIfAvailable(SimpleMeterRegistry::new)));
    }

    /**
     * Looks the implementation up on every call, because it lives in another module. Without an
     * implementation, memberships are unavailable and requests are denied with 503.
     */
    static final class DeferredMembershipLookup implements MembershipLookup {

        private final ObjectProvider<MembershipLookup> delegates;

        DeferredMembershipLookup(ObjectProvider<MembershipLookup> delegates) {
            this.delegates = delegates;
        }

        @Override
        public Optional<WorkspaceAccess> find(UUID workspaceId, UUID userId) {
            return delegate().find(workspaceId, userId);
        }

        @Override
        public List<UUID> workspaceIdsOf(UUID userId) {
            return delegate().workspaceIdsOf(userId);
        }

        private MembershipLookup delegate() {
            MembershipLookup delegate = delegates.getIfAvailable();
            if (delegate == null) {
                throw new MembershipUnavailableException("No MembershipLookup implementation is available");
            }
            return delegate;
        }
    }
}
