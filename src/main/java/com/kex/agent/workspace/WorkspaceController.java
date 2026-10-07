// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
package com.kex.agent.workspace;

import java.security.Principal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/agent/workspace/requests")
class WorkspaceController {
    private final WorkspaceService service;
    WorkspaceController(WorkspaceService service) { this.service = service; }
    @GetMapping List<WorkspaceRequest> list(Principal actor) { return service.list(actor); }
    @GetMapping("/{id}") WorkspaceRequest get(Principal actor, @PathVariable String id) { return service.get(actor, id); }
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> stream(Principal actor, @Valid @RequestBody WorkspaceRequest.Input input) { return service.stream(actor, input); }
    @PostMapping(path = "/{id}/recover/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> recover(Principal actor, @PathVariable String id, @Valid @RequestBody WorkspaceRequest.Recovery input) { return service.resumeRead(actor, id, input); }
    record Link(@NotBlank @Size(max = 100) String taskId) { }
    @PostMapping("/{id}/plan") WorkspaceRequest link(Principal actor, @PathVariable String id, @Valid @RequestBody Link input) { return service.link(actor, id, input.taskId()); }
}
