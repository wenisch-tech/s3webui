package tech.wenisch.s3webui.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import tech.wenisch.s3webui.config.IamProperties;
import tech.wenisch.s3webui.entity.GrantType;
import tech.wenisch.s3webui.entity.UserRole;

@Controller
@RequiredArgsConstructor
public class AdminController {

    private final IamProperties iamProperties;

    @GetMapping("/admin/settings")
    public String settings(Model model) {
        model.addAttribute("grantTypes", GrantType.values());
        model.addAttribute("userRoles", UserRole.values());
        model.addAttribute("iamEnabled", iamProperties.isEnabled());
        return "admin/settings";
    }
}
