package com.parallelcart.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class InstanceIdResponseHeaderFilter extends OncePerRequestFilter {

    private final String instanceId;

    public InstanceIdResponseHeaderFilter(
            @Value("${app.instance-id:${HOSTNAME:parallel-cart-app}}") String instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader("X-App-Instance", instanceId);
        filterChain.doFilter(request, response);
    }
}
