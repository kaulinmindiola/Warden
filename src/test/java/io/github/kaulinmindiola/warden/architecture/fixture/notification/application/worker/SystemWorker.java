package io.github.kaulinmindiola.warden.architecture.fixture.notification.application.worker;

import io.github.kaulinmindiola.warden.shared.tenancy.TenantTransactions;

/** Opens a cross-tenant transaction from the worker: allowed by FF-03. */
public class SystemWorker {

    public int claimDue(TenantTransactions transactions) {
        return transactions.system(() -> 0);
    }
}
