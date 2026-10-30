package com.itemmanagement.controller;

import com.itemmanagement.dto.master.RoleResponse;
import com.itemmanagement.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RoleController {

    private final UserService userService;

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('ROLE_VIEW')")
    public List<RoleResponse> list() {
        return userService.roles();
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public RoleResponse create(@Valid @RequestBody RoleResponse.Request request) {
        return userService.createRole(request);
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public RoleResponse update(@PathVariable Long id,
                               @Valid @RequestBody RoleResponse.Request request) {
        return userService.updateRole(id, request);
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    public List<RoleResponse.PermissionResponse> permissions() {
        return userService.permissions();
    }
}
