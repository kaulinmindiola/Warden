/**
 * Tenant context of database transactions (SDD V07): transaction-local settings read by Row Level
 * Security policies and the Hibernate filter {@code tenant}, which tenant-scoped entities apply
 * with {@code @Filter(name = "tenant")} on their {@code workspace_id} column.
 *
 * <p>Exposed to the other modules as the {@code tenancy} named interface.
 */
@NamedInterface("tenancy")
@FilterDef(
        name = "tenant",
        parameters = @ParamDef(name = "workspaceId", type = UUID.class),
        defaultCondition = "workspace_id = :workspaceId")
package io.github.kaulinmindiola.warden.shared.tenancy;

import java.util.UUID;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.springframework.modulith.NamedInterface;
