package com.booksmith.identity.web;

import com.booksmith.identity.api.AuthenticatedStaff;
import com.booksmith.identity.api.CurrentStaff;
import com.booksmith.identity.api.StaffRole;
import com.booksmith.identity.internal.staff.StaffSecurity;
import com.booksmith.identity.internal.staff.StaffService;
import com.booksmith.identity.internal.staff.AuditEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** A equipe e o registro de ações (só administradores da equipe). */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
class StaffAdminController {

    private final StaffService staff;
    private final StaffSecurity security;
    private final CurrentStaff currentStaff;

    record InviteRequest(String email, String name, StaffRole role) {
    }

    record RoleRequest(StaffRole role) {
    }

    @GetMapping("/staff")
    List<StaffService.MemberView> list() {
        currentStaff.require(StaffRole.ADMIN);
        return staff.list();
    }

    @PostMapping("/staff")
    @ResponseStatus(HttpStatus.CREATED)
    StaffService.MemberView invite(@RequestBody InviteRequest request) {
        AuthenticatedStaff admin = currentStaff.require(StaffRole.ADMIN);
        StaffService.MemberView member = staff.invite(admin, request.email(), request.name(), request.role());
        security.record("staff.invite", member.id().toString(), member.role().name());
        return member;
    }

    @PostMapping("/staff/{id}/role")
    StaffService.MemberView role(@PathVariable UUID id, @RequestBody RoleRequest request) {
        AuthenticatedStaff admin = currentStaff.require(StaffRole.ADMIN);
        StaffService.MemberView member = staff.role(admin, id, request.role());
        security.record("staff.role", id.toString(), member.role().name());
        return member;
    }

    @PostMapping("/staff/{id}/deactivate")
    StaffService.MemberView deactivate(@PathVariable UUID id) {
        AuthenticatedStaff admin = currentStaff.require(StaffRole.ADMIN);
        StaffService.MemberView member = staff.active(admin, id, false);
        security.record("staff.deactivate", id.toString(), null);
        return member;
    }

    @PostMapping("/staff/{id}/activate")
    StaffService.MemberView activate(@PathVariable UUID id) {
        AuthenticatedStaff admin = currentStaff.require(StaffRole.ADMIN);
        StaffService.MemberView member = staff.active(admin, id, true);
        security.record("staff.activate_account", id.toString(), null);
        return member;
    }

    /** Senha e autenticador novos (os antigos e as sessões acabam na hora). */
    @PostMapping("/staff/{id}/reinvite")
    StaffService.MemberView reinvite(@PathVariable UUID id) {
        AuthenticatedStaff admin = currentStaff.require(StaffRole.ADMIN);
        StaffService.MemberView member = staff.reinvite(admin, id);
        security.record("staff.reinvite", id.toString(), null);
        return member;
    }

    @GetMapping("/audit")
    List<AuditEntry> audit(@RequestParam(defaultValue = "200") int limit) {
        currentStaff.require(StaffRole.ADMIN);
        return security.audit(limit);
    }
}
