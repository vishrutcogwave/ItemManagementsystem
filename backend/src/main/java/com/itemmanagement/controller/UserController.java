package com.itemmanagement.controller;

import com.itemmanagement.dto.common.StatusRequest;
import com.itemmanagement.dto.master.UserResponse;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.service.UserService;
import com.itemmanagement.util.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public PageResponse<UserResponse> list(@RequestParam(required = false) String search,
                                           @RequestParam(required = false) StoreStatus status,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return userService.list(search, status, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public UserResponse get(@PathVariable Long id) {
        return userService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public UserResponse create(@Valid @RequestBody UserResponse.CreateRequest request) {
        return userService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public UserResponse update(@PathVariable Long id,
                               @Valid @RequestBody UserResponse.UpdateRequest request) {
        return userService.update(id, request);
    }

    @PostMapping("/{id}/branches")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public UserResponse assignBranches(@PathVariable Long id,
                                       @RequestBody UserResponse.AssignBranchesRequest request) {
        return userService.assignBranches(id, request.branchIds());
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public UserResponse updateStatus(@PathVariable Long id,
                                     @Valid @RequestBody StatusRequest request) {
        return userService.updateStatus(id, request.status());
    }

    @GetMapping("/roles/available")
    @PreAuthorize("hasAuthority('ROLE_VIEW') or hasAuthority('USER_MANAGE')")
    public List<com.itemmanagement.dto.master.RoleResponse> rolesForUsers() {
        return userService.roles();
    }
}
