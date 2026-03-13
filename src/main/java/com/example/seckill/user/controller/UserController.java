package com.example.seckill.user.controller;

import com.example.seckill.user.domain.User;
import com.example.seckill.user.dto.LoginRequest;
import com.example.seckill.user.dto.RegisterRequest;
import com.example.seckill.user.service.UserService;
import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request);
        return ApiResponse.success();
    }

    @PostMapping("/login")
    public ApiResponse<String> login(@Valid @RequestBody LoginRequest request) {
        User user = userService.login(request);
        String token = "mock-token-for-" + user.getUsername();
        return ApiResponse.success(token);
    }

    @Data
    public static class ApiResponse<T> {
        private int code;
        private String msg;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> resp = new ApiResponse<>();
            resp.setCode(0);
            resp.setMsg("success");
            resp.setData(data);
            return resp;
        }

        public static <T> ApiResponse<T> success() {
            return success(null);
        }

        public static <T> ApiResponse<T> fail(String msg) {
            ApiResponse<T> resp = new ApiResponse<>();
            resp.setCode(-1);
            resp.setMsg(msg);
            return resp;
        }
    }
}

