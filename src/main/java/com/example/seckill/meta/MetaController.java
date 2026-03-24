package com.example.seckill.meta;

import com.example.seckill.user.controller.UserController.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.util.Map;

@RestController
@RequestMapping("/api/meta")
public class MetaController {

    @Value("${server.port:8080}")
    private int serverPort;

    private final MetaDbService metaDbService;

    public MetaController(MetaDbService metaDbService) {
        this.metaDbService = metaDbService;
    }

    @GetMapping("/whoami")
    public ApiResponse<WhoAmIResponse> whoami() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        return ApiResponse.success(new WhoAmIResponse(hostname, serverPort));
    }

    @GetMapping("/db/write")
    public ApiResponse<Map<String, Object>> dbWrite() {
        return ApiResponse.success(metaDbService.queryWriteDbMeta());
    }

    @GetMapping("/db/read")
    public ApiResponse<Map<String, Object>> dbRead() {
        return ApiResponse.success(metaDbService.queryReadDbMeta());
    }

    public record WhoAmIResponse(String hostname, int port) {}
}

