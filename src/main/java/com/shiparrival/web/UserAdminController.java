package com.shiparrival.web;

import com.shiparrival.domain.UserRole;
import com.shiparrival.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Administración de cuentas. Todas las rutas exigen rol {@code ADMIN}.
 */
@Slf4j
@Controller
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserService userService;

    @GetMapping
    public String list(HttpServletRequest request, Model model) {
        requireAdmin(request);
        model.addAttribute("users", userService.findAll());
        model.addAttribute("roles", UserRole.values());
        return "users";
    }

    @PostMapping
    public String create(@RequestParam String username,
                         @RequestParam String password,
                         @RequestParam UserRole role,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes) {
        requireAdmin(request);
        try {
            userService.createUser(username, password, role);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Cuenta " + username.trim().toLowerCase() + " creada.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}/password")
    public String resetPassword(@PathVariable Long id,
                                @RequestParam String password,
                                HttpServletRequest request,
                                RedirectAttributes redirectAttributes) {
        CurrentUser admin = requireAdmin(request);
        try {
            userService.resetPassword(admin.id(), id, password);
            redirectAttributes.addFlashAttribute("successMessage", "Contraseña actualizada.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes) {
        CurrentUser admin = requireAdmin(request);
        try {
            boolean enabled = userService.findById(id)
                    .map(user -> !user.isEnabled())
                    .orElse(false);
            userService.setEnabled(admin.id(), id, enabled);
            redirectAttributes.addFlashAttribute("successMessage",
                    enabled ? "Cuenta activada." : "Cuenta desactivada.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}/role")
    public String changeRole(@PathVariable Long id,
                             @RequestParam UserRole role,
                             HttpServletRequest request,
                             RedirectAttributes redirectAttributes) {
        CurrentUser admin = requireAdmin(request);
        try {
            userService.changeRole(admin.id(), id, role);
            redirectAttributes.addFlashAttribute("successMessage", "Rol actualizado.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/users";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         HttpServletRequest request,
                         RedirectAttributes redirectAttributes) {
        CurrentUser admin = requireAdmin(request);
        try {
            userService.deleteUser(admin.id(), id);
            redirectAttributes.addFlashAttribute("successMessage", "Cuenta eliminada.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/users";
    }

    private CurrentUser requireAdmin(HttpServletRequest request) {
        CurrentUser user = (CurrentUser) request.getAttribute(AuthFilter.CURRENT_USER_ATTRIBUTE);
        if (user == null || !user.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return user;
    }
}
