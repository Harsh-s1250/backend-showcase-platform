package com.example.platform.controller;

import com.example.platform.entity.Project;
import com.example.platform.repository.ProjectRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

@RestController
public class ProxyController {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "transfer-encoding", "content-length",
            "host", "upgrade", "proxy-authenticate", "proxy-authorization", "te", "trailer"
    );

    private final ProjectRepository projectRepository;
    private final RestClient restClient = RestClient.create();

    public ProxyController(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @RequestMapping("/p/{projectId}/**")
    public ResponseEntity<byte[]> proxy(@PathVariable UUID projectId,
                                        HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {

        Project project = projectRepository.findById(projectId).orElse(null);

        if (project == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Project not found.".getBytes());
        }
        if (project.getHostPort() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("This project is not currently running.".getBytes());
        }

        String fullPath = request.getRequestURI();
        String prefix = "/p/" + projectId;
        String forwardedPath = fullPath.startsWith(prefix)
                ? fullPath.substring(prefix.length())
                : "/";
        if (forwardedPath.isEmpty()) {
            forwardedPath = "/";
        }

        String queryString = request.getQueryString();
        String targetUrl = "http://localhost:" + project.getHostPort() + forwardedPath
                + (queryString != null ? "?" + queryString : "");

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        RestClient.RequestBodySpec requestSpec = restClient.method(method).uri(targetUrl);

        Collections.list(request.getHeaderNames()).forEach(headerName -> {
            if (!HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase())) {
                requestSpec.header(headerName, request.getHeader(headerName));
            }
        });

        try {
            ResponseEntity<byte[]> response = (body != null)
                    ? requestSpec.body(body).retrieve().toEntity(byte[].class)
                    : requestSpec.retrieve().toEntity(byte[].class);

            HttpHeaders responseHeaders = new HttpHeaders();
            response.getHeaders().forEach((name, values) -> {
                if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                    responseHeaders.put(name, values);
                }
            });

            return ResponseEntity.status(response.getStatusCode())
                    .headers(responseHeaders)
                    .body(response.getBody());

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(("Could not reach the running project: " + e.getMessage()).getBytes());
        }
    }
}