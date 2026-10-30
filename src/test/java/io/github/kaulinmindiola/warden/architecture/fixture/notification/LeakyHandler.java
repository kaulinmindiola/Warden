package io.github.kaulinmindiola.warden.architecture.fixture.notification;

import io.github.kaulinmindiola.warden.architecture.fixture.iam.Account;

/** Reads another module directly instead of the event payload: forbidden by FF-02. */
public class LeakyHandler {

    public String recipient(Account account) {
        return account.email();
    }
}
