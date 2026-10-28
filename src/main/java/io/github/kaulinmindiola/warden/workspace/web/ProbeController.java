package io.github.kaulinmindiola.warden.workspace.web;

import io.github.kaulinmindiola.warden.shared.security.RequiresWorkspaceRole;
import io.github.kaulinmindiola.warden.shared.security.Role;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Experiment for the R7 gate (step 2.16), never merged: a workspace-scoped endpoint that declares
 * its role (so FF-05 passes) but is missing from the isolation registry (so R7 must fail the build).
 */
@RestController
class ProbeController {

    @GetMapping("/workspaces/{workspaceId}/probe")
    @RequiresWorkspaceRole(Role.VIEWER)
    ResponseEntity<Void> probe(@PathVariable UUID workspaceId) {
        return ResponseEntity.noContent().build();
    }
}
