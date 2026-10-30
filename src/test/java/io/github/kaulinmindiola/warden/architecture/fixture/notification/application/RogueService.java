package io.github.kaulinmindiola.warden.architecture.fixture.notification.application;

import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;

/** Opens a cross-tenant transaction outside the worker: forbidden by FF-03. */
public class RogueService {

    public int countEverything(TenantTransactions transactions) {
        return transactions.system(() -> 0);
    }
}
