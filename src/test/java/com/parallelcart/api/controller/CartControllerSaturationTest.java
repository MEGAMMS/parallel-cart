package com.parallelcart.api.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parallelcart.api.dto.CheckoutResponse;
import com.parallelcart.service.CartService;
import com.parallelcart.service.CheckoutSaturationGuard;
import com.parallelcart.service.SystemSaturatedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = CartController.class)
@Import(GlobalExceptionHandler.class)
class CartControllerSaturationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CartService cartService;

    @MockBean
    private CheckoutSaturationGuard checkoutSaturationGuard;

    @Test
    @WithMockUser
    void checkoutReturns503WhenSystemIsSaturated() throws Exception {
        when(checkoutSaturationGuard.execute(eq("checkout"), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new SystemSaturatedException("System saturated. Try again shortly."));

        mockMvc.perform(post("/api/carts/2/checkout")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"sat-test-1\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SystemSaturated"))
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    @WithMockUser
    void checkoutPassesThroughWhenCapacityExists() throws Exception {
        CheckoutResponse response = new CheckoutResponse(10L, 20L, java.math.BigDecimal.TEN, "PAID");
        when(checkoutSaturationGuard.execute(eq("checkout"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    CheckoutSaturationGuard.ThrowingSupplier<CheckoutResponse> supplier = invocation.getArgument(1);
                    return supplier.get();
                });
        when(cartService.checkout(anyLong(), eq("sat-test-2"))).thenReturn(response);

        mockMvc.perform(post("/api/carts/2/checkout")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"sat-test-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(10))
                .andExpect(jsonPath("$.paymentId").value(20));
    }
}
