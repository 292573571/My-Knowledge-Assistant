package com.example.workbench.auth;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminUserService {

    private final AppUserRepository userRepository;
    private final AdminAuthorizationService authorizationService;

    public AdminUserService(AppUserRepository userRepository, AdminAuthorizationService authorizationService) {
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> list(AppUser actor) {
        authorizationService.requireAdmin(actor);
        return userRepository.findAllByOrderByCreatedAtDesc().stream().map(this::response).toList();
    }

    @Transactional
    public AdminUserResponse changeRole(AppUser actor, String publicId, UpdateSystemRoleRequest request) {
        authorizationService.requireSuperAdmin(actor);
        if (request.systemRole() == SystemRole.SUPER_ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能通过用户管理接口授予超级管理员角色");
        }
        AppUser user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if ("admin".equalsIgnoreCase(user.getAccount())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "admin 账号必须保持超级管理员角色");
        }
        user.changeSystemRole(request.systemRole());
        return response(userRepository.save(user));
    }

    private AdminUserResponse response(AppUser user) {
        return new AdminUserResponse(user.getPublicId(), user.getAccount(), user.getEmail(), user.getPhone(),
                user.getUserName(), authorizationService.effectiveRole(user), user.getCreatedAt());
    }
}
